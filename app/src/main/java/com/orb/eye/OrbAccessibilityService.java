package com.orb.eye;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.litongjava.android.paddle.ocr.OcrResultModel;
import com.litongjava.android.paddle.ocr.Predictor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class OrbAccessibilityService extends AccessibilityService {
    private static final String TAG = "OrbEye";
    private static final int PORT = 7333;
    private static final int MAX_NOTIFICATIONS = 50;
    private static final String OCR_ENGINE_AUTO = "auto";
    private static final String OCR_ENGINE_MLKIT = "mlkit";
    private static final String OCR_ENGINE_PADDLE = "paddle";
    private static final int OCR_MIN_TOKEN_SIZE_PX = 1;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private ExecutorService fastPool; // lightweight: ping, screen, tap, click, clipboard, etc.
    private ExecutorService ocrPool;  // heavyweight: /ocr, /ocr-screen (CPU-bound, slow)

    // ===== Notification buffer =====
    private final CopyOnWriteArrayList<JSONObject> notificationBuffer = new CopyOnWriteArrayList<>();

    // ===== Wait for UI change =====
    volatile CountDownLatch uiChangeLatch = new CountDownLatch(1);
    volatile String lastWindowPackage = "";
    volatile String lastWindowClass = "";
    private final Object paddleLock = new Object();
    private Predictor paddlePredictor;
    private String paddleInitError = "";

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Orb Eye v2.4 service connected");
        startHttpServer();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int type = event.getEventType();

        // Capture notifications
        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            captureNotification(event);
        }

        // Signal UI change for /wait
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (event.getPackageName() != null) {
                    lastWindowPackage = event.getPackageName().toString();
                }
                if (event.getClassName() != null) {
                    lastWindowClass = event.getClassName().toString();
                }
            }
            uiChangeLatch.countDown();
        }
    }

    private void captureNotification(AccessibilityEvent event) {
        try {
            JSONObject notif = new JSONObject();
            notif.put("timestamp", System.currentTimeMillis());
            notif.put("package", event.getPackageName() != null ? event.getPackageName().toString() : "");

            JSONArray textArr = new JSONArray();
            if (event.getText() != null) {
                for (CharSequence cs : event.getText()) {
                    if (cs != null) textArr.put(cs.toString());
                }
            }
            notif.put("text", textArr);

            Parcelable parcel = event.getParcelableData();
            if (parcel instanceof Notification) {
                Notification n = (Notification) parcel;
                if (n.extras != null) {
                    String title = n.extras.getString(Notification.EXTRA_TITLE, "");
                    CharSequence body = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                    CharSequence bigText = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                    notif.put("title", title);
                    notif.put("body", body != null ? body.toString() : "");
                    if (bigText != null) notif.put("bigText", bigText.toString());
                }
            }

            notificationBuffer.add(notif);
            while (notificationBuffer.size() > MAX_NOTIFICATIONS) {
                notificationBuffer.remove(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Notification capture error: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Orb Eye service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopHttpServer();
        releasePaddlePredictor();
    }

    // ===== HTTP Server =====

    private static boolean isOcrRoute(String route) {
        return "/ocr".equals(route) || "/ocr-screen".equals(route);
    }

    private void startHttpServer() {
        // Fast pool: handles all lightweight requests (ping/info/screen/tap/click/clipboard…).
        // Must never be blocked by OCR — kept at 8 threads so concurrent non-OCR calls stay responsive.
        fastPool = Executors.newFixedThreadPool(8);
        // OCR pool: CPU-bound screenshot+recognition, can take 5-30 s each.
        // Kept small (2) to limit memory pressure; fast pool never waits on it.
        ocrPool = Executors.newFixedThreadPool(2);
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.i(TAG, "HTTP server listening on port " + PORT);
                while (!Thread.interrupted()) {
                    Socket client = serverSocket.accept();
                    // Read the request line in the fast pool to determine the route,
                    // then hand off to ocrPool for OCR routes so fast threads stay free.
                    fastPool.execute(() -> dispatchRequest(client));
                }
            } catch (Exception e) {
                Log.e(TAG, "Server error: " + e.getMessage());
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void dispatchRequest(Socket client) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String requestLine = reader.readLine();
            if (requestLine == null) { client.close(); return; }
            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            String route = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
            if (isOcrRoute(route)) {
                // Hand off to OCR pool; this fast-pool thread is freed immediately.
                ocrPool.execute(() -> handleRequestFrom(client, reader, requestLine));
            } else {
                handleRequestFrom(client, reader, requestLine);
            }
        } catch (Exception e) {
            Log.e(TAG, "Dispatch error: " + e.getMessage());
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private void stopHttpServer() {
        try {
            if (fastPool != null) fastPool.shutdownNow();
            if (ocrPool != null) ocrPool.shutdownNow();
            if (serverSocket != null) serverSocket.close();
            if (serverThread != null) serverThread.interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Stop error: " + e.getMessage());
        }
    }

    private void handleRequestFrom(Socket client, BufferedReader reader, String requestLine) {
        try {
            // requestLine and reader are pre-supplied by dispatchRequest.
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int n = reader.read(buf, off, contentLength - off);
                    if (n <= 0) break;
                    off += n;
                }
                body = new String(buf, 0, off);
                if (off < contentLength) {
                    Log.w(TAG, "request body truncated: read=" + off + " expected=" + contentLength);
                }
            }

            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts[1];
            String route = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

            // /exec: support both JSON body and raw JS body
            if ("/exec".equals(route)) {
                String query = path.contains("?") ? path.substring(path.indexOf("?") + 1) : "";
                JSONObject bodyJson = parseExecBody(body, query);
                boolean stream = bodyJson.optBoolean("stream", false);
                if (!stream) {
                    stream = query.contains("stream=1") || query.contains("stream=true");
                }
                if (stream) {
                    handleExecStreaming(client, bodyJson);
                    return;
                }
            }

            String response;
            try {
                response = routeRequest(method, path, body);
            } catch (Exception e) {
                response = errorJson("Internal error: " + e.getMessage(), "INTERNAL_ERROR");
            }

            OutputStream out = client.getOutputStream();
            byte[] responseBytes = response.getBytes("UTF-8");
            String http = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Content-Length: " + responseBytes.length + "\r\n"
                    + "\r\n";
            out.write(http.getBytes("UTF-8"));
            out.write(responseBytes);
            out.flush();
            client.close();
        } catch (Exception e) {
            Log.e(TAG, "Request error: " + e.getMessage());
        }
    }

    // ===== Router =====

    private String routeRequest(String method, String path, String body) throws Exception {
        String route = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
        String query = path.contains("?") ? path.substring(path.indexOf("?") + 1) : "";

        switch (route) {
            case "/ping":
                return "{\"ok\":true,\"service\":\"orb-eye\",\"version\":\"" + OrbScriptEngine.VERSION + "\"}";

            case "/tree":
                return getUiTree(query);

            case "/inspect":
                return getUiInspect(query);

            case "/screen":
                return getScreenElements(query);

            case "/focused":
                return getFocusedElement();

            case "/tap":
                return handleTap(new JSONObject(body));

            case "/click":
                return handleClick(new JSONObject(body));

            case "/input":
                return handleInput(new JSONObject(body));

            case "/setText":
                return handleSetText(new JSONObject(body));

            case "/scroll":
                return handleScroll(new JSONObject(body));

            case "/swipe":
                return handleSwipe(new JSONObject(body));

            case "/longpress":
                return handleLongPress(new JSONObject(body));

            case "/back":
                performGlobalAction(GLOBAL_ACTION_BACK);
                return "{\"ok\":true}";

            case "/home":
                performGlobalAction(GLOBAL_ACTION_HOME);
                return "{\"ok\":true}";

            case "/recents":
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                return "{\"ok\":true}";

            case "/notifications":
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                return "{\"ok\":true}";

            case "/notify":
                return getNotifications(query);

            case "/info":
                return getAppInfo();

            case "/wait":
                return handleWait(query);

            // ===== v2.1 New Endpoints =====

            case "/find":
                return handleFind(new JSONObject(body));

            case "/screenshot":
                return handleScreenshot();

            case "/ocr":
                if ("GET".equals(method)) {
                    return handleOcr(queryToJson(query));
                }
                return handleOcr(body == null || body.isEmpty() ? new JSONObject() : new JSONObject(body));

            case "/ocr-screen":
                // Alias of /ocr for explicit "current screen OCR" semantics.
                if ("GET".equals(method)) {
                    return handleOcr(queryToJson(query));
                }
                return handleOcr(body == null || body.isEmpty() ? new JSONObject() : new JSONObject(body));

            case "/clipboard":
                return "GET".equals(method) ? handleClipboardGet() : handleClipboardSet(new JSONObject(body));

            case "/gesture":
                return handleGesture(new JSONObject(body));

            case "/wait-text":
                return handleWaitText(new JSONObject(body));

            case "/exec":
                return handleExec(parseExecBody(body, query));

            case "/stopjs":
            case "/stop-js":
                return handleStopJs(method, body, query);

            default:
                return errorJson("Unknown route: " + route, "NOT_FOUND");
        }
    }

    // ===== /exec — JavaScript scripting engine (v2.4) =====

    /**
     * Parse /exec body: if it looks like JSON (starts with '{'), parse as
     * {@code {"script":"...", "timeout":..., "stream":...}}.
     * Otherwise treat the entire body as raw JavaScript.
     * Query params ?timeout=N and ?stream=1 are also honored.
     */
    private JSONObject parseExecBody(String body, String query) {
        JSONObject obj = new JSONObject();
        String trimmed = body != null ? body.trim() : "";
        if (trimmed.startsWith("{")) {
            try {
                obj = new JSONObject(trimmed);
            } catch (Exception ignored) {
                // malformed JSON — fall through to raw JS
            }
        }
        try {
            // Raw JS body: treat whole body as script.
            if (!trimmed.startsWith("{")) {
                obj.put("script", body != null ? body : "");
            }
            // Normalize execution id aliases from JSON body.
            if (!obj.has("executionId") && obj.has("id")) {
                Long parsed = parseLongSafely(obj.opt("id"));
                if (parsed != null) {
                    obj.put("executionId", parsed);
                }
            }
            // Parse timeout/stream/executionId from query string.
            if (query != null && !query.isEmpty()) {
                for (String kv : query.split("&")) {
                    String[] pair = kv.split("=", 2);
                    if (pair.length < 2) continue;
                    if ("timeout".equals(pair[0])) {
                        if (!obj.has("timeout")) {
                            try { obj.put("timeout", Integer.parseInt(pair[1])); } catch (Exception ignored) {}
                        }
                    } else if ("stream".equals(pair[0])) {
                        if (!obj.has("stream")) {
                            obj.put("stream", "1".equals(pair[1]) || "true".equals(pair[1]));
                        }
                    } else if ("executionId".equals(pair[0]) || "id".equals(pair[0])) {
                        if (!obj.has("executionId")) {
                            Long parsed = parseLongSafely(pair[1]);
                            if (parsed != null) obj.put("executionId", parsed);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "parseExecBody error: " + e.getMessage());
        }
        return obj;
    }

    private OrbScriptEngine scriptEngine;

    private String handleStopJs(String method, String body, String query) {
        try {
            JSONObject result = new JSONObject();
            Long executionId = parseExecutionId(query, body);
            int stopped;
            if (executionId != null) {
                boolean stoppedOne = scriptEngine != null && scriptEngine.stopExecution(executionId);
                stopped = stoppedOne ? 1 : 0;
                result.put("mode", "single");
                result.put("execution_id", executionId);
            } else {
                stopped = scriptEngine != null ? scriptEngine.stopAllRunningScripts() : 0;
                result.put("mode", "all");
            }
            result.put("ok", true);
            result.put("method", method);
            result.put("stopped", stopped);
            return result.toString();
        } catch (Exception e) {
            return errorJson("Failed to stop scripts: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    private Long parseExecutionId(String query, String body) {
        Long fromQuery = parseExecutionIdFromQuery(query);
        if (body == null || body.trim().isEmpty()) {
            return fromQuery;
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            return fromQuery;
        }
        try {
            JSONObject obj = new JSONObject(trimmed);
            if (obj.has("executionId")) {
                Long parsed = parseLongSafely(obj.opt("executionId"));
                if (parsed != null) return parsed;
            }
            if (obj.has("id")) {
                Long parsed = parseLongSafely(obj.opt("id"));
                if (parsed != null) return parsed;
            }
        } catch (Exception ignored) {
            // ignore malformed JSON and fallback to query value
        }
        return fromQuery;
    }

    private Long parseExecutionIdFromQuery(String query) {
        if (query == null || query.isEmpty()) return null;
        for (String kv : query.split("&")) {
            String[] pair = kv.split("=", 2);
            if (pair.length < 2) continue;
            if ("executionId".equals(pair[0]) || "id".equals(pair[0])) {
                Long parsed = parseLongSafely(pair[1]);
                if (parsed != null) return parsed;
            }
        }
        return null;
    }

    private Long parseLongSafely(Object raw) {
        if (raw == null) return null;
        try {
            if (raw instanceof Number) {
                return ((Number) raw).longValue();
            }
            String text = raw.toString().trim();
            if (text.isEmpty()) return null;
            return Long.parseLong(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String handleExec(JSONObject body) throws Exception {
        String script = body.optString("script", "");
        if (script.isEmpty()) {
            return errorJson("Provide 'script' field with JavaScript code", "INVALID_ARGS");
        }
        int timeoutMs = body.optInt("timeout", 60000);
        if (scriptEngine == null) {
            scriptEngine = new OrbScriptEngine(this);
        }
        Long executionId = parseLongSafely(body.opt("executionId"));
        return scriptEngine.execute(script, timeoutMs, executionId);
    }

    private void handleExecStreaming(Socket client, JSONObject body) {
        try {
            String script = body.optString("script", "");
            if (script.isEmpty()) {
                // Write error as normal HTTP response and close
                OutputStream out = client.getOutputStream();
                byte[] err = errorJson("Provide 'script' field with JavaScript code", "INVALID_ARGS").getBytes("UTF-8");
                String hdr = "HTTP/1.1 400 Bad Request\r\n"
                        + "Content-Type: application/json; charset=utf-8\r\n"
                        + "Content-Length: " + err.length + "\r\n\r\n";
                out.write(hdr.getBytes("UTF-8"));
                out.write(err);
                out.flush();
                client.close();
                return;
            }
            int timeoutMs = body.optInt("timeout", 60000);
            if (scriptEngine == null) {
                scriptEngine = new OrbScriptEngine(this);
            }
            Long executionId = parseLongSafely(body.opt("executionId"));

            // Send chunked NDJSON response header (no Content-Length)
            OutputStream out = client.getOutputStream();
            String hdr = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/x-ndjson; charset=utf-8\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "\r\n";
            out.write(hdr.getBytes("UTF-8"));
            out.flush();

            // Wrap OutputStream with chunked encoding
            ChunkedOutputStream chunked = new ChunkedOutputStream(out);
            scriptEngine.executeStreaming(script, timeoutMs, executionId, chunked);

            // Send final zero-length chunk to signal end
            chunked.finish();
            out.flush();
            client.close();
        } catch (Exception e) {
            Log.e(TAG, "exec streaming error: " + e.getMessage());
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Minimal chunked transfer-encoding wrapper. Each write becomes one HTTP chunk.
     */
    private static class ChunkedOutputStream extends OutputStream {
        private final OutputStream inner;

        ChunkedOutputStream(OutputStream inner) { this.inner = inner; }

        @Override
        public void write(int b) throws java.io.IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] data, int off, int len) throws java.io.IOException {
            if (len == 0) return;
            // chunk = hex-size CRLF data CRLF
            inner.write((Integer.toHexString(len) + "\r\n").getBytes("UTF-8"));
            inner.write(data, off, len);
            inner.write("\r\n".getBytes("UTF-8"));
        }

        @Override
        public void flush() throws java.io.IOException { inner.flush(); }

        /** Send the terminating zero-length chunk. */
        void finish() throws java.io.IOException {
            inner.write("0\r\n\r\n".getBytes("UTF-8"));
        }

        @Override
        public void close() throws java.io.IOException { inner.close(); }
    }

    // Package-visible helpers for OrbScriptEngine to reuse existing implementations.

    String handleScreenshotForScript() throws Exception {
        return handleScreenshot();
    }

    String handleOcrForScript() throws Exception {
        Bitmap source = captureScreenshotBitmap();
        if (source == null) return "[]";
        try {
            OcrRunResult ocrResult = runOcrWithEngine(source, OCR_ENGINE_AUTO);
            List<OcrLineData> lines = ocrResult.lines;
            JSONArray arr = new JSONArray();
            for (OcrLineData line : lines) {
                arr.put(lineToJson(line, 0, 0));
            }
            return arr.toString();
        } finally {
            if (!source.isRecycled()) source.recycle();
        }
    }

    String handleOcrFindForScript(String text) throws Exception {
        Bitmap source = captureScreenshotBitmap();
        if (source == null) return null;
        try {
            OcrRunResult ocrResult = runOcrWithEngine(source, OCR_ENGINE_AUTO);
            List<OcrLineData> lines = ocrResult.lines;
            for (OcrLineData line : lines) {
                if (line.text != null && line.text.contains(text)) {
                    return lineToJson(line, 0, 0).toString();
                }
            }
            return null;
        } finally {
            if (!source.isRecycled()) source.recycle();
        }
    }

    String getClipboardText() throws Exception {
        final String[] holder = {""};
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                    CharSequence t = item.getText();
                    holder[0] = t != null ? t.toString() : "";
                }
            } catch (Exception e) { /* ignore */ }
            finally { latch.countDown(); }
        });
        latch.await(3, TimeUnit.SECONDS);
        return holder[0];
    }

    void setClipboardText(String text) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("orb", text));
                }
            } catch (Exception e) { /* ignore */ }
            finally { latch.countDown(); }
        });
        latch.await(3, TimeUnit.SECONDS);
    }

    // ===== Notifications =====

    private String getNotifications(String query) throws Exception {
        boolean clear = query.contains("clear=true");

        // Package filter (include)
        String filterPkg = null;
        if (query.contains("package=")) {
            filterPkg = query.split("package=")[1].split("&")[0];
        }

        // Exclude packages (comma-separated)
        List<String> excludePkgs = new ArrayList<>();
        if (query.contains("exclude=")) {
            String excludeVal = query.split("exclude=")[1].split("&")[0];
            for (String p : excludeVal.split(",")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) excludePkgs.add(trimmed);
            }
        }

        JSONArray arr = new JSONArray();
        for (JSONObject n : notificationBuffer) {
            String pkg = n.optString("package");
            if (filterPkg != null && !pkg.equals(filterPkg)) continue;
            if (excludePkgs.contains(pkg)) continue;
            arr.put(n);
        }

        if (clear) {
            notificationBuffer.clear();
        }

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("notifications", arr);
        result.put("count", arr.length());
        return result.toString();
    }

    // ===== SET_TEXT (direct text injection) =====

    private String handleSetText(JSONObject body) throws Exception {
        String text = body.getString("text");
        String targetId = body.optString("id", "");
        boolean append = body.optBoolean("append", false);

        AccessibilityNodeInfo target = null;

        if (!targetId.isEmpty()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(targetId);
                if (nodes != null && !nodes.isEmpty()) {
                    target = nodes.get(0);
                }
            }
        }

        if (target == null) {
            target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }

        if (target == null) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                target = findFirstEditable(root);
            }
        }

        if (target == null) return errorJson("No editable field found", "NO_EDITABLE");

        String newText = text;
        if (append && target.getText() != null) {
            newText = target.getText().toString() + text;
        }

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText);
        boolean set = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);

        JSONObject result = new JSONObject();
        result.put("ok", set);
        result.put("text", newText);
        return result.toString();
    }

    // ===== Wait for UI change =====

    private String handleWait(String query) throws Exception {
        long timeoutMs = 5000;
        if (query.contains("timeout=")) {
            try {
                timeoutMs = Long.parseLong(query.split("timeout=")[1].split("&")[0]);
            } catch (Exception ignored) {}
        }

        uiChangeLatch = new CountDownLatch(1);
        boolean changed = uiChangeLatch.await(timeoutMs, TimeUnit.MILLISECONDS);

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("changed", changed);
        result.put("timeoutMs", timeoutMs);
        result.put("package", lastWindowPackage);
        result.put("activity", lastWindowClass);
        return result.toString();
    }

    // ===== Wait for specific text (v2.8) =====

    /**
     * POST /wait-text
     * Body: {"text":"支付成功", "timeout":5000, "contains":true, "interval":500}
     * Blocks until the specified text appears in the UI tree or timeout.
     */
    private String handleWaitText(JSONObject body) throws Exception {
        String text = body.optString("text", "");
        if (text.isEmpty()) {
            return errorJson("Provide 'text' field", "INVALID_ARGS");
        }
        long timeoutMs = body.optLong("timeout", 5000);
        boolean contains = body.optBoolean("contains", true);
        long intervalMs = body.optLong("interval", 500);

        long startTime = System.currentTimeMillis();
        long deadline = startTime + timeoutMs;
        int attempts = 0;

        while (System.currentTimeMillis() < deadline) {
            attempts++;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                boolean found = searchTextInTree(root, text, contains);
                root.recycle();
                if (found) {
                    JSONObject result = new JSONObject();
                    result.put("ok", true);
                    result.put("found", true);
                    result.put("text", text);
                    result.put("attempts", attempts);
                    result.put("elapsedMs", System.currentTimeMillis() - startTime);
                    return result.toString();
                }
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            Thread.sleep(Math.min(intervalMs, remaining));
        }

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("found", false);
        result.put("text", text);
        result.put("attempts", attempts);
        result.put("elapsedMs", System.currentTimeMillis() - startTime);
        result.put("timeoutMs", timeoutMs);
        return result.toString();
    }

    private boolean searchTextInTree(AccessibilityNodeInfo node, String text, boolean contains) {
        if (node == null) return false;
        CharSequence nodeText = node.getText();
        CharSequence nodeDesc = node.getContentDescription();

        if (nodeText != null) {
            String s = nodeText.toString();
            if (contains ? s.contains(text) : s.equals(text)) return true;
        }
        if (nodeDesc != null) {
            String s = nodeDesc.toString();
            if (contains ? s.contains(text) : s.equals(text)) return true;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean found = searchTextInTree(child, text, contains);
                child.recycle();
                if (found) return true;
            }
        }
        return false;
    }

    // ===== App Info =====

    private String getAppInfo() throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("package", lastWindowPackage);
        result.put("activity", lastWindowClass);

        if (root != null) {
            result.put("windowPackage", root.getPackageName() != null ? root.getPackageName().toString() : "");
            result.put("windowChildCount", root.getChildCount());
            root.recycle();
        }

        return result.toString();
    }

    private JSONObject queryToJson(String query) throws Exception {
        JSONObject out = new JSONObject();
        if (query == null || query.isEmpty()) return out;

        String[] pairs = query.split("&");
        JSONObject region = new JSONObject();
        boolean hasRegion = false;
        for (String pair : pairs) {
            if (pair == null || pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8.name());
            String val = kv.length > 1
                    ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name())
                    : "";

            switch (key) {
                case "text":
                    out.put("text", val);
                    break;
                case "contains":
                    out.put("contains", "true".equalsIgnoreCase(val) || "1".equals(val));
                    break;
                case "tap":
                    out.put("tap", "true".equalsIgnoreCase(val) || "1".equals(val));
                    break;
                case "index":
                    out.put("index", Integer.parseInt(val));
                    break;
                case "x":
                case "y":
                case "width":
                case "height":
                case "left":
                case "top":
                case "right":
                case "bottom":
                    region.put(key, Integer.parseInt(val));
                    hasRegion = true;
                    break;
                default:
                    // ignore unknown params
                    break;
            }
        }
        if (hasRegion) out.put("region", region);
        return out;
    }

    // ===== Swipe gesture =====

    private String handleSwipe(JSONObject body) throws Exception {
        int x1 = body.getInt("x1");
        int y1 = body.getInt("y1");
        int x2 = body.getInt("x2");
        int y2 = body.getInt("y2");
        long duration = body.optLong("duration", 300);

        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        boolean completed = dispatchGestureAndWait(builder.build(), duration + 5000);

        JSONObject result = new JSONObject();
        result.put("ok", completed);
        return result.toString();
    }

    // ===== Long press =====

    private String handleLongPress(JSONObject body) throws Exception {
        int x = body.getInt("x");
        int y = body.getInt("y");
        long duration = body.optLong("duration", 1000);

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        boolean completed = dispatchGestureAndWait(builder.build(), duration + 5000);

        JSONObject result = new JSONObject();
        result.put("ok", completed);
        result.put("x", x);
        result.put("y", y);
        return result.toString();
    }

    // ===== UI Tree =====

    String getUiTree(String query) throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorJson("No active window", "NOT_FOUND");

        try {
            String filterPkg = getQueryParam(query, "package");
            int maxDepth = clampInt(getQueryInt(query, "maxDepth", 15), 0, 40);

            JSONObject tree = nodeToJson(root, 0, maxDepth, filterPkg);
            return tree != null ? tree.toString() : errorJson("No matching content", "NOT_FOUND");
        } finally {
            root.recycle();
        }
    }

    private JSONObject nodeToJson(AccessibilityNodeInfo node, int depth, int maxDepth, String filterPkg) throws Exception {
        if (filterPkg != null && node.getPackageName() != null
                && !node.getPackageName().toString().equals(filterPkg)) {
            return null;
        }

        JSONObject obj = new JSONObject();
        obj.put("class", node.getClassName() != null ? node.getClassName().toString() : "");
        obj.put("text", node.getText() != null ? node.getText().toString() : "");
        obj.put("desc", node.getContentDescription() != null ? node.getContentDescription().toString() : "");
        obj.put("id", node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "");
        obj.put("pkg", node.getPackageName() != null ? node.getPackageName().toString() : "");
        obj.put("clickable", node.isClickable());
        obj.put("editable", node.isEditable());
        obj.put("focused", node.isFocused());
        obj.put("selected", node.isSelected());
        obj.put("enabled", node.isEnabled());
        obj.put("scrollable", node.isScrollable());
        obj.put("checked", node.isChecked());

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        obj.put("bounds", bounds.flattenToString());
        obj.put("centerX", bounds.centerX());
        obj.put("centerY", bounds.centerY());
        obj.put("hash", System.identityHashCode(node));

        if (depth < maxDepth) {
            JSONArray children = new JSONArray();
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    JSONObject childJson = nodeToJson(child, depth + 1, maxDepth, filterPkg);
                    if (childJson != null) children.put(childJson);
                    child.recycle();
                }
            }
            if (children.length() > 0) obj.put("children", children);
        }

        return obj;
    }

    /**
     * GET /inspect — inspector-friendly flattened node list (AutoJs6-like fields).
     * Query params:
     *   package=xxx        — filter by package name
     *   maxDepth=25        — limit recursion depth (0 = root only)
     *   clickableOnly=true — only clickable/long-clickable nodes
     *   textOnly=true      — only nodes with text or contentDescription
     *   enabledOnly=true   — only enabled nodes
     *   visibleOnly=true   — only nodes visible to user
     *   contains=xxx       — fuzzy match in text/desc/id/class
     */
    String getUiInspect(String query) throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorJson("No active window", "NOT_FOUND");

        try {
            String filterPkg = getQueryParam(query, "package");
            int maxDepth = clampInt(getQueryInt(query, "maxDepth", 25), 0, 60);
            boolean clickableOnly = getQueryBoolean(query, "clickableOnly", false)
                    || getQueryBoolean(query, "clickable", false);
            boolean textOnly = getQueryBoolean(query, "textOnly", false);
            boolean enabledOnly = getQueryBoolean(query, "enabledOnly", false);
            boolean visibleOnly = getQueryBoolean(query, "visibleOnly", false);
            String contains = getQueryParam(query, "contains");

            JSONArray nodes = new JSONArray();
            collectInspectNodes(root, nodes,
                    filterPkg, maxDepth,
                    clickableOnly, textOnly, enabledOnly, visibleOnly, contains,
                    0, -1, "0");

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("count", nodes.length());
            result.put("nodes", nodes);
            return result.toString();
        } finally {
            root.recycle();
        }
    }

    private void collectInspectNodes(
            AccessibilityNodeInfo node,
            JSONArray out,
            String filterPkg,
            int maxDepth,
            boolean clickableOnly,
            boolean textOnly,
            boolean enabledOnly,
            boolean visibleOnly,
            String contains,
            int depth,
            int indexInParent,
            String path
    ) throws Exception {
        if (node == null) return;

        String pkg = node.getPackageName() != null ? node.getPackageName().toString() : "";
        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        String text = node.getText() != null ? node.getText().toString() : "";
        String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
        String id = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";

        boolean packageMatch = filterPkg == null || filterPkg.isEmpty() || filterPkg.equals(pkg);
        boolean clickable = node.isClickable();
        boolean longClickable = node.isLongClickable();
        boolean enabled = node.isEnabled();
        boolean visibleToUser = node.isVisibleToUser();
        boolean hasText = !text.isEmpty() || !desc.isEmpty();
        boolean containsMatch = contains == null || contains.isEmpty()
                || containsIgnoreCase(text, contains)
                || containsIgnoreCase(desc, contains)
                || containsIgnoreCase(id, contains)
                || containsIgnoreCase(className, contains);

        boolean passClickable = !clickableOnly || clickable || longClickable;
        boolean passText = !textOnly || hasText;
        boolean passEnabled = !enabledOnly || enabled;
        boolean passVisible = !visibleOnly || visibleToUser;

        if (packageMatch && containsMatch && passClickable && passText && passEnabled && passVisible) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);

            JSONObject item = new JSONObject();
            item.put("path", path);
            item.put("depth", depth);
            item.put("indexInParent", indexInParent);
            item.put("childCount", node.getChildCount());
            item.put("class", className);
            item.put("id", id);
            item.put("text", text);
            item.put("desc", desc);
            item.put("pkg", pkg);
            item.put("clickable", clickable);
            item.put("longClickable", longClickable);
            item.put("scrollable", node.isScrollable());
            item.put("editable", node.isEditable());
            item.put("enabled", enabled);
            item.put("focusable", node.isFocusable());
            item.put("focused", node.isFocused());
            item.put("selected", node.isSelected());
            item.put("checkable", node.isCheckable());
            item.put("checked", node.isChecked());
            item.put("visibleToUser", visibleToUser);
            item.put("bounds", bounds.flattenToString());
            item.put("left", bounds.left);
            item.put("top", bounds.top);
            item.put("right", bounds.right);
            item.put("bottom", bounds.bottom);
            item.put("centerX", bounds.centerX());
            item.put("centerY", bounds.centerY());
            out.put(item);
        }

        if (depth >= maxDepth) return;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectInspectNodes(child, out,
                        filterPkg, maxDepth,
                        clickableOnly, textOnly, enabledOnly, visibleOnly, contains,
                        depth + 1, i, path + "." + i);
                child.recycle();
            }
        }
    }

    private String getQueryParam(String query, String key) {
        if (query == null || query.isEmpty() || key == null || key.isEmpty()) return null;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            if (pair == null || pair.isEmpty()) continue;
            try {
                String[] kv = pair.split("=", 2);
                String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8.name());
                if (!key.equals(k)) continue;
                return kv.length > 1
                        ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name())
                        : "";
            } catch (Exception ignored) {
                // ignore malformed query fragment
            }
        }
        return null;
    }

    private boolean getQueryBoolean(String query, String key, boolean defaultValue) {
        String val = getQueryParam(query, key);
        if (val == null || val.isEmpty()) return defaultValue;
        return "1".equals(val)
                || "true".equalsIgnoreCase(val)
                || "yes".equalsIgnoreCase(val)
                || "y".equalsIgnoreCase(val);
    }

    private int getQueryInt(String query, String key, int defaultValue) {
        String val = getQueryParam(query, key);
        if (val == null || val.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean containsIgnoreCase(String haystack, String needle) {
        if (needle == null || needle.isEmpty()) return true;
        if (haystack == null || haystack.isEmpty()) return false;
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    // ===== Screen Elements (v2.1 enhanced) =====

    /**
     * GET /screen — flat list of elements with optional filters.
     * Query params:
     *   scrollable=true   — only elements inside a scrollable container
     *   editable=true     — only editable elements
     *   package=xxx       — filter by package name
     * Each element now includes centerX/centerY for direct use with /tap.
     */
    private String getScreenElements(String query) throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorJson("No active window", "NOT_FOUND");

        boolean onlyScrollable = query.contains("scrollable=true");
        boolean onlyEditable = query.contains("editable=true");
        String filterPkg = null;
        if (query.contains("package=")) {
            filterPkg = query.split("package=")[1].split("&")[0];
        }

        JSONArray elements = new JSONArray();
        collectScreenElements(root, elements, onlyScrollable, onlyEditable, filterPkg, false);

        root.recycle();
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("elements", elements);
        return result.toString();
    }

    /**
     * Recursively collect screen elements. When onlyScrollable=true, only yield
     * elements that are descendants of a scrollable container.
     */
    private void collectScreenElements(AccessibilityNodeInfo node, JSONArray out,
            boolean onlyScrollable, boolean onlyEditable, String filterPkg,
            boolean insideScrollable) throws Exception {

        if (node == null) return;

        String pkg = node.getPackageName() != null ? node.getPackageName().toString() : "";
        if (filterPkg != null && !pkg.equals(filterPkg)) {
            // Skip but still traverse children (they may belong to different pkg in split-screen)
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    collectScreenElements(child, out, onlyScrollable, onlyEditable, filterPkg, insideScrollable);
                    child.recycle();
                }
            }
            return;
        }

        boolean nowInsideScrollable = insideScrollable || node.isScrollable();

        String text = node.getText() != null ? node.getText().toString() : "";
        String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
        boolean isEditable = node.isEditable();

        // Apply filters
        boolean passScrollable = !onlyScrollable || nowInsideScrollable;
        boolean passEditable = !onlyEditable || isEditable;

        if (passScrollable && passEditable && (!text.isEmpty() || !desc.isEmpty())) {
            JSONObject item = new JSONObject();
            if (!text.isEmpty()) item.put("text", text);
            if (!desc.isEmpty()) item.put("desc", desc);
            item.put("id", node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "");
            item.put("clickable", node.isClickable());
            item.put("editable", isEditable);
            item.put("scrollable", node.isScrollable());

            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            item.put("bounds", bounds.flattenToString());
            item.put("centerX", bounds.centerX());
            item.put("centerY", bounds.centerY());

            out.put(item);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectScreenElements(child, out, onlyScrollable, onlyEditable, filterPkg, nowInsideScrollable);
                child.recycle();
            }
        }
    }

    // ===== Focused Element =====

    private String getFocusedElement() throws Exception {
        AccessibilityNodeInfo focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) focused = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (focused == null) return errorJson("No focused element", "NOT_FOUND");

        JSONObject obj = nodeToJson(focused, 0, 0, null);
        focused.recycle();
        return obj != null ? obj.toString() : errorJson("Node unavailable", "NOT_FOUND");
    }

    // ===== Tap by coordinates =====

    private String handleTap(JSONObject body) throws Exception {
        int x = body.getInt("x");
        int y = body.getInt("y");
        long duration = body.optLong("duration", 100);

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        boolean completed = dispatchGestureAndWait(builder.build(), 5000);

        JSONObject result = new JSONObject();
        result.put("ok", completed);
        result.put("x", x);
        result.put("y", y);
        return result.toString();
    }

    // ===== Click by text/desc/id =====

    private String handleClick(JSONObject body) throws Exception {
        String targetText = body.optString("text", "");
        String targetDesc = body.optString("desc", "");
        String targetId = body.optString("id", "");
        String targetBounds = body.optString("bounds", "");

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorJson("No active window", "NOT_FOUND");

        AccessibilityNodeInfo target = null;

        if (!targetText.isEmpty()) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(targetText);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (n.isClickable()) { target = n; break; }
                }
                if (target == null) target = findClickableParent(nodes.get(0));
                if (target == null) target = nodes.get(0);
            }
        }

        if (target == null && !targetId.isEmpty()) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(targetId);
            if (nodes != null && !nodes.isEmpty()) target = nodes.get(0);
        }

        if (target == null && !targetDesc.isEmpty()) {
            target = findNodeByDesc(root, targetDesc);
        }

        if (target == null && !targetBounds.isEmpty()) {
            target = findNodeByBounds(root, targetBounds);
        }

        if (target == null) {
            root.recycle();
            return errorJson("Element not found", "NOT_FOUND");
        }

        boolean clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) {
            AccessibilityNodeInfo parent = findClickableParent(target);
            if (parent != null) clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }

        JSONObject result = new JSONObject();
        result.put("ok", clicked);
        result.put("text", target.getText() != null ? target.getText().toString() : "");
        root.recycle();
        return result.toString();
    }

    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node.getParent();
        int depth = 0;
        while (current != null && depth < 5) {
            if (current.isClickable()) return current;
            current = current.getParent();
            depth++;
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeByDesc(AccessibilityNodeInfo root, String desc) {
        Queue<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node.getContentDescription() != null
                    && node.getContentDescription().toString().contains(desc)) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeByBounds(AccessibilityNodeInfo root, String boundsStr) {
        Queue<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.flattenToString().equals(boundsStr)) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    // ===== Input Text (v2.1 enhanced) =====

    /**
     * POST /input
     * Body: {"text":"..."}                   — set text (clears first by default)
     *       {"text":"...", "append":true}     — append to existing
     *       {"clear":true}                    — clear without writing
     * Response includes previousText for caller verification.
     */
    private String handleInput(JSONObject body) throws Exception {
        boolean clearOnly = body.optBoolean("clear", false);
        String text = body.optString("text", "");
        boolean append = body.optBoolean("append", false);

        // If neither clear nor text provided, error
        if (!clearOnly && text.isEmpty()) {
            return errorJson("Provide 'text' or 'clear':true", "INVALID_ARGS");
        }

        AccessibilityNodeInfo focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) focused = findFirstEditable(root);
        }

        if (focused == null) return errorJson("No editable field found", "NO_EDITABLE");

        // Capture previous text before modification
        String previousText = focused.getText() != null ? focused.getText().toString() : "";

        if (clearOnly) {
            Bundle clearArgs = new Bundle();
            clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
            boolean cleared = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);
            JSONObject result = new JSONObject();
            result.put("ok", cleared);
            result.put("previousText", previousText);
            result.put("action", "clear");
            return result.toString();
        }

        // Set text
        String newText;
        if (append) {
            newText = previousText + text;
        } else {
            // Default: explicit clear first to handle apps that may accumulate on SET_TEXT
            Bundle clearArgs = new Bundle();
            clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);
            newText = text;
        }

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText);
        boolean set = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);

        JSONObject result = new JSONObject();
        result.put("ok", set);
        result.put("text", newText);
        result.put("previousText", previousText);
        result.put("action", append ? "append" : "set");
        return result.toString();
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo root) {
        Queue<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node.isEditable()) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    // ===== Scroll (v2.1 enhanced) =====

    /**
     * POST /scroll
     * Body: {"direction":"down"}                                    — scroll first scrollable
     *       {"direction":"down", "target":"text in container"}      — find container by text
     *       {"direction":"down", "count":3}                         — repeat N times (300ms gap)
     */
    private String handleScroll(JSONObject body) throws Exception {
        String direction = body.optString("direction", "down");
        String targetText = body.optString("target", "");
        int count = Math.max(1, body.optInt("count", 1));

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorJson("No active window", "NOT_FOUND");

        AccessibilityNodeInfo scrollable;
        if (!targetText.isEmpty()) {
            scrollable = findScrollableContaining(root, targetText);
        } else {
            scrollable = findFirstScrollable(root);
        }

        if (scrollable == null) {
            root.recycle();
            return errorJson("No scrollable element found", "NOT_FOUND");
        }

        int action = (direction.equals("up") || direction.equals("left"))
                ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;

        boolean lastResult = false;
        for (int i = 0; i < count; i++) {
            lastResult = scrollable.performAction(action);
            if (i < count - 1) {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
        }

        root.recycle();
        JSONObject result = new JSONObject();
        result.put("ok", lastResult);
        result.put("direction", direction);
        result.put("count", count);
        return result.toString();
    }

    private AccessibilityNodeInfo findFirstScrollable(AccessibilityNodeInfo root) {
        Queue<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node.isScrollable()) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    /**
     * Find the closest scrollable ancestor of a node containing targetText.
     */
    private AccessibilityNodeInfo findScrollableContaining(AccessibilityNodeInfo root, String targetText) {
        // First find a node with the target text
        AccessibilityNodeInfo textNode = null;
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(targetText);
        if (matches != null && !matches.isEmpty()) {
            textNode = matches.get(0);
        }
        if (textNode == null) return null;

        // Walk up to find scrollable ancestor
        AccessibilityNodeInfo current = textNode;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current.isScrollable()) return current;
            current = current.getParent();
            depth++;
        }

        // Fallback: return first scrollable in the whole tree
        return findFirstScrollable(root);
    }

    // ===== /find — Precise element search (v2.1) =====

    /**
     * POST /find
     * Body:
     *   {"text":"label"}            — match by visible text (contains)
     *   {"desc":"description"}      — match by content description (contains)
     *   {"id":"resource-id"}        — match by view ID (exact)
     * Optional:
     *   "clickable": true            — only return clickable nodes
     *   "index": N                   — return Nth match (0-based, default 0)
     *
     * Returns the matched element with centerX/centerY ready for /tap.
     */
    private String handleFind(JSONObject body) throws Exception {
        String text = body.optString("text", "");
        String desc = body.optString("desc", "");
        String id = body.optString("id", "");
        boolean onlyClickable = body.optBoolean("clickable", false);
        int index = body.optInt("index", 0);

        if (text.isEmpty() && desc.isEmpty() && id.isEmpty()) {
            return errorJson("Provide 'text', 'desc', or 'id'", "INVALID_ARGS");
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorJson("No active window", "NOT_FOUND");

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();

        if (!text.isEmpty()) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
            if (found != null) candidates.addAll(found);
        } else if (!id.isEmpty()) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
            if (found != null) candidates.addAll(found);
        } else {
            // desc search: manual traversal (no built-in API for content description)
            collectByDesc(root, desc, candidates);
        }

        // Apply clickable filter
        if (onlyClickable) {
            List<AccessibilityNodeInfo> filtered = new ArrayList<>();
            for (AccessibilityNodeInfo n : candidates) {
                if (n.isClickable()) {
                    filtered.add(n);
                } else {
                    AccessibilityNodeInfo parent = findClickableParent(n);
                    if (parent != null) filtered.add(parent);
                }
            }
            candidates = filtered;
        }

        if (candidates.isEmpty()) {
            root.recycle();
            return errorJson("No matching element found", "NOT_FOUND");
        }

        if (index >= candidates.size()) {
            root.recycle();
            return errorJson("Index " + index + " out of range (found " + candidates.size() + ")", "NOT_FOUND");
        }

        AccessibilityNodeInfo target = candidates.get(index);
        Rect bounds = new Rect();
        target.getBoundsInScreen(bounds);

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("text", target.getText() != null ? target.getText().toString() : "");
        result.put("desc", target.getContentDescription() != null ? target.getContentDescription().toString() : "");
        result.put("id", target.getViewIdResourceName() != null ? target.getViewIdResourceName() : "");
        result.put("bounds", bounds.flattenToString());
        result.put("centerX", bounds.centerX());
        result.put("centerY", bounds.centerY());
        result.put("clickable", target.isClickable());
        result.put("editable", target.isEditable());
        result.put("scrollable", target.isScrollable());
        result.put("matchCount", candidates.size());
        result.put("index", index);

        root.recycle();
        return result.toString();
    }

    private void collectByDesc(AccessibilityNodeInfo node, String descQuery, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.toString().contains(descQuery)) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectByDesc(child, descQuery, out);
        }
    }

    // ===== /screenshot — Take screenshot via AccessibilityService (v2.1) =====

    /**
     * GET /screenshot
     * Uses AccessibilityService.takeScreenshot (API 30+).
     * Returns: {"ok":true, "image":"data:image/png;base64,...", "width":W, "height":H}
     */
    private String handleScreenshot() throws Exception {
        Bitmap bmp = null;
        try {
            bmp = captureScreenshotBitmap();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
            String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("image", "data:image/png;base64," + b64);
            result.put("width", bmp.getWidth());
            result.put("height", bmp.getHeight());
            return result.toString();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Screenshot failed";
            if (msg.contains("API 30+")) return errorJson(msg, "NOT_SUPPORTED");
            if (msg.contains("timed out")) return errorJson(msg, "TIMEOUT");
            return errorJson(msg, "SCREENSHOT_FAILED");
        } finally {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
    }

    private static class OcrLineData {
        String text;
        Rect bounds;

        OcrLineData(String text, Rect bounds) {
            this.text = text;
            this.bounds = bounds;
        }
    }

    /**
     * POST /ocr
     * Body:
     *   {"text":"找朋友帮忙付","contains":true,"tap":false,"index":0}
     *   {"imageBase64":"data:image/png;base64,..."} // optional; default uses current screenshot
     *   {"region":{"x":100,"y":200,"width":400,"height":300}} // optional crop
     *
     * Returns OCR lines with coordinates. If text provided, also returns matches.
     * If tap=true and a match is selected, performs tap on selected match center.
     */
    private String handleOcr(JSONObject body) throws Exception {
        long startedAt = System.currentTimeMillis();
        Bitmap source = null;
        Bitmap input = null;
        try {
            String imageBase64 = body.optString("imageBase64", "");
            boolean useInlineImage = imageBase64 != null && !imageBase64.isEmpty();
            source = useInlineImage ? decodeBase64Bitmap(imageBase64) : captureScreenshotBitmap();
            if (source == null) {
                return errorJson("OCR input image unavailable", "OCR_FAILED");
            }

            Rect cropRect = null;
            JSONObject region = body.optJSONObject("region");
            if (region != null) {
                cropRect = parseCropRect(region, source.getWidth(), source.getHeight());
                if (cropRect == null) {
                    return errorJson("Invalid OCR region", "INVALID_ARGS");
                }
                input = Bitmap.createBitmap(source, cropRect.left, cropRect.top, cropRect.width(), cropRect.height());
            } else {
                input = source;
            }

            String requestedEngine = normalizeOcrEngine(body.optString("engine", OCR_ENGINE_AUTO));
            OcrRunResult ocrResult = runOcrWithEngine(input, requestedEngine);
            List<OcrLineData> lines = ocrResult.lines;
            int offsetX = cropRect != null ? cropRect.left : 0;
            int offsetY = cropRect != null ? cropRect.top : 0;

            String targetText = body.optString("text", "");
            boolean contains = body.optBoolean("contains", true);
            int index = Math.max(0, body.optInt("index", 0));
            boolean tap = body.optBoolean("tap", false);
            long tapDuration = Math.max(30L, body.optLong("tapDuration", 100L));

            JSONArray linesJson = new JSONArray();
            JSONArray matches = new JSONArray();
            for (int i = 0; i < lines.size(); i++) {
                OcrLineData line = lines.get(i);
                JSONObject item = lineToJson(line, offsetX, offsetY);
                item.put("index", i);
                linesJson.put(item);

                if (targetText != null && !targetText.isEmpty()) {
                    String lineText = line.text != null ? line.text : "";
                    boolean hit = contains ? lineText.contains(targetText) : lineText.equals(targetText);
                    if (hit) matches.put(item);
                }
            }

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("engine", ocrResult.engine);
            result.put("requestedEngine", requestedEngine);
            result.put("source", useInlineImage ? "imageBase64" : "screenshot");
            result.put("imageWidth", source.getWidth());
            result.put("imageHeight", source.getHeight());
            result.put("ocrWidth", input.getWidth());
            result.put("ocrHeight", input.getHeight());
            result.put("offsetX", offsetX);
            result.put("offsetY", offsetY);
            result.put("lineCount", linesJson.length());
            result.put("lines", linesJson);
            result.put("matchCount", matches.length());
            result.put("matches", matches);
            result.put("elapsedMs", System.currentTimeMillis() - startedAt);

            if (targetText != null && !targetText.isEmpty()) {
                boolean matched = matches.length() > index;
                result.put("matched", matched);
                result.put("selectedIndex", index);
                if (matched) {
                    JSONObject selected = matches.getJSONObject(index);
                    result.put("selected", selected);
                    if (tap) {
                        int x = selected.getInt("centerX");
                        int y = selected.getInt("centerY");
                        boolean tapped = dispatchTapPoint(x, y, tapDuration);
                        result.put("tap", tapped);
                        result.put("tapX", x);
                        result.put("tapY", y);
                    } else {
                        result.put("tap", false);
                    }
                } else {
                    result.put("selected", JSONObject.NULL);
                    result.put("tap", false);
                }
            }

            return result.toString();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "OCR failed";
            if (msg.contains("API 30+")) return errorJson(msg, "NOT_SUPPORTED");
            if (msg.contains("timed out")) return errorJson(msg, "TIMEOUT");
            return errorJson(msg, "OCR_FAILED");
        } finally {
            if (input != null && input != source && !input.isRecycled()) input.recycle();
            if (source != null && !source.isRecycled()) source.recycle();
        }
    }

    private Bitmap captureScreenshotBitmap() throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new IllegalStateException("takeScreenshot requires API 30+, device is API " + Build.VERSION.SDK_INT);
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> bitmapRef = new AtomicReference<>(null);
        AtomicReference<String> errorRef = new AtomicReference<>(null);

        takeScreenshot(Display.DEFAULT_DISPLAY,
                getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult screenshot) {
                        try {
                            Bitmap hw = Bitmap.wrapHardwareBuffer(
                                    screenshot.getHardwareBuffer(),
                                    screenshot.getColorSpace()
                            );
                            if (hw == null) {
                                errorRef.set("Bitmap wrap failed");
                                return;
                            }
                            Bitmap bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                            bitmapRef.set(bmp);
                        } catch (Exception e) {
                            errorRef.set("Bitmap encode failed: " + e.getMessage());
                        } finally {
                            try {
                                screenshot.getHardwareBuffer().close();
                            } catch (Exception ignored) {}
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        errorRef.set("takeScreenshot failed with code " + errorCode);
                        latch.countDown();
                    }
                });

        boolean done = latch.await(10, TimeUnit.SECONDS);
        if (!done) throw new IllegalStateException("Screenshot timed out");
        if (errorRef.get() != null) throw new IllegalStateException(errorRef.get());

        Bitmap bmp = bitmapRef.get();
        if (bmp == null) throw new IllegalStateException("Screenshot bitmap unavailable");
        return bmp;
    }

    private Bitmap decodeBase64Bitmap(String imageBase64) throws Exception {
        String raw = imageBase64;
        int comma = raw.indexOf(",");
        if (raw.startsWith("data:image") && comma > 0) {
            raw = raw.substring(comma + 1);
        }
        byte[] bytes = Base64.decode(raw, Base64.DEFAULT);
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bmp == null) throw new IllegalStateException("Decode imageBase64 failed");
        return bmp;
    }

    private Rect parseCropRect(JSONObject region, int imageWidth, int imageHeight) {
        int left;
        int top;
        int right;
        int bottom;

        if (region.has("left") || region.has("top") || region.has("right") || region.has("bottom")) {
            left = clamp(region.optInt("left", 0), 0, imageWidth - 1);
            top = clamp(region.optInt("top", 0), 0, imageHeight - 1);
            right = clamp(region.optInt("right", imageWidth), left + 1, imageWidth);
            bottom = clamp(region.optInt("bottom", imageHeight), top + 1, imageHeight);
        } else {
            int x = clamp(region.optInt("x", 0), 0, imageWidth - 1);
            int y = clamp(region.optInt("y", 0), 0, imageHeight - 1);
            int w = region.optInt("width", imageWidth - x);
            int h = region.optInt("height", imageHeight - y);
            if (w <= 0 || h <= 0) return null;
            left = x;
            top = y;
            right = clamp(x + w, x + 1, imageWidth);
            bottom = clamp(y + h, y + 1, imageHeight);
        }

        if (right <= left || bottom <= top) return null;
        return new Rect(left, top, right, bottom);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class OcrRunResult {
        final String engine;
        final List<OcrLineData> lines;

        OcrRunResult(String engine, List<OcrLineData> lines) {
            this.engine = engine;
            this.lines = lines;
        }
    }

    private String normalizeOcrEngine(String engine) {
        if (engine == null) return OCR_ENGINE_AUTO;
        String v = engine.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return OCR_ENGINE_AUTO;
        if ("mlkit_chinese".equals(v) || "mlkit".equals(v)) return OCR_ENGINE_MLKIT;
        if ("paddle".equals(v) || "paddle_lite".equals(v)) return OCR_ENGINE_PADDLE;
        if ("auto".equals(v)) return OCR_ENGINE_AUTO;
        return OCR_ENGINE_AUTO;
    }

    private OcrRunResult runOcrWithEngine(Bitmap bitmap, String engine) throws Exception {
        String normalized = normalizeOcrEngine(engine);
        if (OCR_ENGINE_PADDLE.equals(normalized)) {
            List<OcrLineData> lines = splitOcrLinesOnWhitespace(runOcrPaddle(bitmap));
            return new OcrRunResult("paddle_lite", lines);
        }
        if (OCR_ENGINE_MLKIT.equals(normalized)) {
            List<OcrLineData> lines = splitOcrLinesOnWhitespace(runOcrMlkit(bitmap));
            return new OcrRunResult("mlkit_chinese", lines);
        }

        // auto: try Paddle first, then fallback to ML Kit.
        try {
            List<OcrLineData> lines = splitOcrLinesOnWhitespace(runOcrPaddle(bitmap));
            if (lines != null && !lines.isEmpty()) {
                return new OcrRunResult("paddle_lite", lines);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Paddle OCR unavailable in auto mode, fallback to ML Kit: " + t.getMessage());
        }
        List<OcrLineData> lines = splitOcrLinesOnWhitespace(runOcrMlkit(bitmap));
        return new OcrRunResult("mlkit_chinese", lines);
    }

    private List<OcrLineData> splitOcrLinesOnWhitespace(List<OcrLineData> lines) {
        List<OcrLineData> out = new ArrayList<>();
        if (lines == null) return out;
        for (OcrLineData line : lines) {
            if (line == null || line.bounds == null) continue;
            out.addAll(splitSingleOcrLine(line));
        }
        return out;
    }

    private List<OcrLineData> splitSingleOcrLine(OcrLineData line) {
        List<OcrLineData> out = new ArrayList<>();
        String text = line.text != null ? line.text : "";
        if (text.isEmpty()) {
            out.add(line);
            return out;
        }

        List<int[]> spans = new ArrayList<>();
        int length = text.length();
        int i = 0;
        while (i < length) {
            while (i < length && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= length) break;
            int start = i;
            while (i < length && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            spans.add(new int[]{start, i});
        }

        if (spans.size() <= 1) {
            out.add(line);
            return out;
        }

        Rect b = line.bounds;
        int width = Math.max(OCR_MIN_TOKEN_SIZE_PX, b.width());
        int height = Math.max(OCR_MIN_TOKEN_SIZE_PX, b.height());
        boolean horizontal = width >= height;

        for (int[] span : spans) {
            int start = span[0];
            int end = span[1];
            String token = text.substring(start, end);
            if (token.isEmpty()) continue;

            Rect tokenBounds;
            if (horizontal) {
                int left = b.left + Math.round(width * (start / (float) length));
                int right = b.left + Math.round(width * (end / (float) length));
                left = clamp(left, b.left, b.right - OCR_MIN_TOKEN_SIZE_PX);
                right = clamp(right, left + OCR_MIN_TOKEN_SIZE_PX, b.right);
                tokenBounds = new Rect(left, b.top, right, b.bottom);
            } else {
                int top = b.top + Math.round(height * (start / (float) length));
                int bottom = b.top + Math.round(height * (end / (float) length));
                top = clamp(top, b.top, b.bottom - OCR_MIN_TOKEN_SIZE_PX);
                bottom = clamp(bottom, top + OCR_MIN_TOKEN_SIZE_PX, b.bottom);
                tokenBounds = new Rect(b.left, top, b.right, bottom);
            }
            out.add(new OcrLineData(token, tokenBounds));
        }

        if (out.isEmpty()) {
            out.add(line);
        }
        return out;
    }

    private Predictor ensurePaddlePredictor() {
        synchronized (paddleLock) {
            if (paddlePredictor != null && paddlePredictor.isLoaded()) {
                return paddlePredictor;
            }
            try {
                Predictor predictor = new Predictor();
                boolean ok = predictor.init(getApplicationContext());
                if (!ok) {
                    paddleInitError = "predictor init returned false";
                    return null;
                }
                paddlePredictor = predictor;
                paddleInitError = "";
                Log.i(TAG, "Paddle OCR predictor initialized");
                return paddlePredictor;
            } catch (Throwable t) {
                paddleInitError = t.getMessage() != null ? t.getMessage() : t.toString();
                Log.e(TAG, "Paddle OCR init failed: " + paddleInitError);
                return null;
            }
        }
    }

    private void releasePaddlePredictor() {
        synchronized (paddleLock) {
            if (paddlePredictor != null) {
                try {
                    paddlePredictor.releaseModel();
                } catch (Throwable ignored) {}
                paddlePredictor = null;
            }
        }
    }

    private Rect rectFromPaddlePoints(List<Point> points, int imageWidth, int imageHeight) {
        if (points == null || points.isEmpty()) return null;
        int left = imageWidth;
        int top = imageHeight;
        int right = 0;
        int bottom = 0;
        for (Point p : points) {
            if (p == null) continue;
            left = Math.min(left, p.x);
            top = Math.min(top, p.y);
            right = Math.max(right, p.x);
            bottom = Math.max(bottom, p.y);
        }
        if (right <= left || bottom <= top) return null;
        left = clamp(left, 0, Math.max(0, imageWidth - 1));
        top = clamp(top, 0, Math.max(0, imageHeight - 1));
        right = clamp(right, left + 1, Math.max(left + 1, imageWidth));
        bottom = clamp(bottom, top + 1, Math.max(top + 1, imageHeight));
        return new Rect(left, top, right, bottom);
    }

    private List<OcrLineData> runOcrPaddle(Bitmap bitmap) throws Exception {
        Predictor predictor = ensurePaddlePredictor();
        if (predictor == null) {
            throw new IllegalStateException("Paddle OCR unavailable: " + paddleInitError);
        }

        synchronized (paddleLock) {
            predictor.setInputImage(bitmap);
            boolean ok = predictor.runModel();
            if (!ok) {
                throw new IllegalStateException("Paddle OCR runModel returned false");
            }
            ArrayList<OcrResultModel> results = predictor.outputResults();
            List<OcrLineData> out = new ArrayList<>();
            for (OcrResultModel model : results) {
                if (model == null) continue;
                String text = model.getLabel();
                if (text == null || text.trim().isEmpty()) continue;
                Rect rect = rectFromPaddlePoints(model.getPoints(), bitmap.getWidth(), bitmap.getHeight());
                if (rect == null) continue;
                out.add(new OcrLineData(text, rect));
            }
            return out;
        }
    }

    private List<OcrLineData> runOcrMlkit(Bitmap bitmap) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<OcrLineData>> outRef = new AtomicReference<>(new ArrayList<>());
        AtomicReference<String> errorRef = new AtomicReference<>(null);

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        com.google.mlkit.vision.text.TextRecognizer recognizer =
                TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

        recognizer.process(image)
                .addOnSuccessListener(result -> {
                    try {
                        List<OcrLineData> out = new ArrayList<>();
                        for (Text.TextBlock block : result.getTextBlocks()) {
                            List<Text.Line> lines = block.getLines();
                            if (lines != null && !lines.isEmpty()) {
                                for (Text.Line line : lines) {
                                    Rect b = line.getBoundingBox();
                                    String t = line.getText();
                                    if (b == null || t == null || t.isEmpty()) continue;
                                    out.add(new OcrLineData(t, b));
                                }
                            } else {
                                Rect b = block.getBoundingBox();
                                String t = block.getText();
                                if (b != null && t != null && !t.isEmpty()) {
                                    out.add(new OcrLineData(t, b));
                                }
                            }
                        }
                        outRef.set(out);
                    } catch (Exception e) {
                        errorRef.set("OCR result parse failed: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                })
                .addOnFailureListener(e -> {
                    errorRef.set("OCR failed: " + e.getMessage());
                    latch.countDown();
                });

        boolean done = latch.await(12, TimeUnit.SECONDS);
        recognizer.close();
        if (!done) throw new IllegalStateException("OCR timed out");
        if (errorRef.get() != null) throw new IllegalStateException(errorRef.get());
        return outRef.get();
    }

    private JSONObject lineToJson(OcrLineData line, int offsetX, int offsetY) throws Exception {
        Rect b = line.bounds;
        int left = b.left + offsetX;
        int top = b.top + offsetY;
        int right = b.right + offsetX;
        int bottom = b.bottom + offsetY;

        JSONObject item = new JSONObject();
        item.put("text", line.text);
        item.put("left", left);
        item.put("top", top);
        item.put("right", right);
        item.put("bottom", bottom);
        item.put("bounds", "[" + left + "," + top + "][" + right + "," + bottom + "]");
        item.put("centerX", (left + right) / 2);
        item.put("centerY", (top + bottom) / 2);
        return item;
    }

    /**
     * Dispatch a gesture and block until it completes or is cancelled.
     * Returns true only when the gesture finishes successfully.
     */
    private boolean dispatchGestureAndWait(GestureDescription gesture, long timeoutMs) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);

        boolean dispatched = dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                result.set(true);
                latch.countDown();
            }
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                result.set(false);
                latch.countDown();
            }
        }, null);

        if (!dispatched) return false;

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        return result.get();
    }

    private boolean dispatchTapPoint(int x, int y, long duration) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
            return dispatchGestureAndWait(builder.build(), 5000);
        } catch (Exception e) {
            return false;
        }
    }

    // ===== /clipboard — Read/write clipboard (v2.1) =====

    /**
     * GET  /clipboard  — read current clipboard text
     * POST /clipboard  body: {"text":"..."} — write to clipboard
     */
    private String handleClipboardGet() throws Exception {
        final String[] textHolder = {null};
        final boolean[] hasPrimaryClipHolder = {false};
        final int[] itemCountHolder = {0};
        final JSONArray mimeTypesHolder = new JSONArray();
        final CountDownLatch latch = new CountDownLatch(1);

        // ClipboardManager must be accessed on main thread
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    hasPrimaryClipHolder[0] = cm.hasPrimaryClip();
                    ClipDescription desc = cm.getPrimaryClipDescription();
                    if (desc != null) {
                        for (int i = 0; i < desc.getMimeTypeCount(); i++) {
                            mimeTypesHolder.put(desc.getMimeType(i));
                        }
                    }
                }
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                    ClipData clipData = cm.getPrimaryClip();
                    itemCountHolder[0] = clipData.getItemCount();
                    if (clipData.getItemCount() > 0) {
                        ClipData.Item item = clipData.getItemAt(0);
                        // coerceToText is more robust than getText() when clip item is URI/intent.
                        CharSequence text = item.coerceToText(getApplicationContext());
                        textHolder[0] = text != null ? text.toString() : "";
                    } else {
                        textHolder[0] = "";
                    }
                } else {
                    textHolder[0] = "";
                }
            } catch (Exception e) {
                textHolder[0] = "";
            } finally {
                latch.countDown();
            }
        });

        boolean done = latch.await(3, TimeUnit.SECONDS);
        if (!done) return errorJson("Clipboard read timed out", "TIMEOUT");

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("text", textHolder[0]);
        result.put("hasPrimaryClip", hasPrimaryClipHolder[0]);
        result.put("itemCount", itemCountHolder[0]);
        result.put("mimeTypes", mimeTypesHolder);
        return result.toString();
    }

    private String handleClipboardSet(JSONObject body) throws Exception {
        String text = body.optString("text", "");
        if (text.isEmpty() && !body.has("text")) {
            return errorJson("Provide 'text' field", "INVALID_ARGS");
        }

        final boolean[] successHolder = {false};
        final CountDownLatch latch = new CountDownLatch(1);

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    ClipData clip = ClipData.newPlainText("orb", text);
                    cm.setPrimaryClip(clip);
                    successHolder[0] = true;
                }
            } catch (Exception e) {
                successHolder[0] = false;
            } finally {
                latch.countDown();
            }
        });

        boolean done = latch.await(3, TimeUnit.SECONDS);
        if (!done) return errorJson("Clipboard write timed out", "TIMEOUT");

        JSONObject result = new JSONObject();
        result.put("ok", successHolder[0]);
        result.put("text", text);
        return result.toString();
    }

    // ===== /gesture — Composite gestures (v2.1) =====

    /**
     * POST /gesture
     * Pinch in/out:
     *   {"type":"pinch_in",  "x":540, "y":1200, "distance":200, "durationMs":300}
     *   {"type":"pinch_out", "x":540, "y":1200, "distance":200, "durationMs":300}
     * Multi-stroke custom:
     *   {"type":"multi", "strokes":[
     *     {"path":[[x1,y1],[x2,y2]], "startMs":0, "durationMs":300},
     *     ...
     *   ]}
     */
    private String handleGesture(JSONObject body) throws Exception {
        String type = body.optString("type", "");

        switch (type) {
            case "pinch_in":
            case "pinch_out":
                return handlePinch(body, type.equals("pinch_in"));

            case "multi":
                return handleMultiStroke(body);

            default:
                return errorJson("Unknown gesture type: " + type + ". Use pinch_in, pinch_out, or multi", "INVALID_ARGS");
        }
    }

    private String handlePinch(JSONObject body, boolean pinchIn) throws Exception {
        int cx = body.optInt("x", 540);
        int cy = body.optInt("y", 1200);
        int distance = body.optInt("distance", 200);
        long durationMs = body.optLong("durationMs", 300);

        int half = distance / 2;

        // Two finger paths: horizontal spread (left-right)
        // pinch_in:  fingers start far apart, end at center
        // pinch_out: fingers start at center, end far apart
        int startX1, startX2, endX1, endX2;
        if (pinchIn) {
            startX1 = cx - half; startX2 = cx + half;
            endX1   = cx;        endX2   = cx;
        } else {
            startX1 = cx;        startX2 = cx;
            endX1   = cx - half; endX2   = cx + half;
        }

        Path path1 = new Path();
        path1.moveTo(startX1, cy);
        path1.lineTo(endX1, cy);

        Path path2 = new Path();
        path2.moveTo(startX2, cy);
        path2.lineTo(endX2, cy);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path1, 0, durationMs));
        builder.addStroke(new GestureDescription.StrokeDescription(path2, 0, durationMs));

        boolean completed = dispatchGestureAndWait(builder.build(), durationMs + 5000);

        JSONObject result = new JSONObject();
        result.put("ok", completed);
        result.put("type", pinchIn ? "pinch_in" : "pinch_out");
        result.put("x", cx);
        result.put("y", cy);
        result.put("distance", distance);
        return result.toString();
    }

    private String handleMultiStroke(JSONObject body) throws Exception {
        JSONArray strokes = body.optJSONArray("strokes");
        if (strokes == null || strokes.length() == 0) {
            return errorJson("'strokes' array required for type=multi", "INVALID_ARGS");
        }

        GestureDescription.Builder builder = new GestureDescription.Builder();

        for (int i = 0; i < strokes.length(); i++) {
            JSONObject stroke = strokes.getJSONObject(i);
            JSONArray pathArr = stroke.getJSONArray("path");
            long startMs = stroke.optLong("startMs", 0);
            long durationMs = stroke.optLong("durationMs", 300);

            if (pathArr.length() < 2) {
                return errorJson("Each stroke path needs at least 2 points", "INVALID_ARGS");
            }

            Path p = new Path();
            JSONArray firstPt = pathArr.getJSONArray(0);
            p.moveTo((float) firstPt.getDouble(0), (float) firstPt.getDouble(1));
            for (int j = 1; j < pathArr.length(); j++) {
                JSONArray pt = pathArr.getJSONArray(j);
                p.lineTo((float) pt.getDouble(0), (float) pt.getDouble(1));
            }

            builder.addStroke(new GestureDescription.StrokeDescription(p, startMs, durationMs));
        }

        boolean completed = dispatchGestureAndWait(builder.build(), 30000);

        JSONObject result = new JSONObject();
        result.put("ok", completed);
        result.put("type", "multi");
        result.put("strokeCount", strokes.length());
        return result.toString();
    }

    // ===== Error helper (v2.1 unified format) =====

    /**
     * Unified error response: {"ok":false, "error":"message", "code":"ERROR_CODE"}
     */
    private String errorJson(String msg, String code) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("ok", false);
            obj.put("error", msg);
            obj.put("code", code);
            return obj.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + msg.replace("\"", "'") + "\",\"code\":\"" + code + "\"}";
        }
    }

    /**
     * Legacy overload without code — uses UNKNOWN for backward compat.
     */
    private String errorJson(String msg) {
        return errorJson(msg, "UNKNOWN");
    }
}
