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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
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
    private static final int INSPECTOR_DEFAULT_MAX_DEPTH = 40;
    private static final int FIND_MAX_DIAGNOSTIC_NODES = 1200;
    private static final int FIND_MAX_DIAGNOSTIC_DEPTH = 24;
    private static final long FIND_MAX_DIAGNOSTIC_MS = 1200L;
    private static final String FIND_REASON_TEXT_MISMATCH = "text_mismatch";
    private static final String FIND_REASON_DESC_MISMATCH = "desc_mismatch";
    private static final String FIND_REASON_ID_MISMATCH = "id_mismatch";
    private static final String FIND_REASON_NOT_CLICKABLE = "not_clickable";
    private static final String FIND_REASON_NEED_SCROLL = "need_scroll";
    private static final String FIND_REASON_OFF_SCREEN = "off_screen";
    private static final String FIND_REASON_ANOTHER_WINDOW = "another_window";
    private static final String FIND_REASON_INDEX_OUT_OF_RANGE = "index_out_of_range";
    private static final String FIND_REASON_NO_ACTIVE_WINDOW = "no_active_window";
    private static final int[] INSPECTOR_LEVEL_COLORS = new int[] {
            0xFF00BCD4, // cyan
            0xFF4CAF50, // green
            0xFFFFC107, // amber
            0xFFFF5722, // deep orange
            0xFF03A9F4, // light blue
            0xFF8BC34A, // light green
            0xFFFF9800, // orange
            0xFFF44336  // red
    };

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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ===== Floating inspector (AutoJs6-style) =====
    private final Object inspectorLock = new Object();
    private WindowManager inspectorWindowManager;
    private InspectorBoundsView inspectorBoundsView;
    private LinearLayout inspectorPanelView;
    private WindowManager.LayoutParams inspectorPanelLayoutParams;
    private LinearLayout inspectorBreadcrumbPanelView;
    private WindowManager.LayoutParams inspectorBreadcrumbLayoutParams;
    private HorizontalScrollView inspectorBreadcrumbScrollView;
    private LinearLayout inspectorBreadcrumbContainer;
    private LinearLayout inspectorControlView;
    private WindowManager.LayoutParams inspectorControlLayoutParams;
    private Button inspectorControlToggleButton;
    private Button inspectorControlRefreshButton;
    private TextView inspectorControlOpacityTextView;
    private ListView inspectorTreeListView;
    private TextView inspectorInfoTextView;
    private Button inspectorParentButton;
    private Button inspectorInfoToggleButton;
    private TextView inspectorHeaderTextView;
    private InspectorTreeAdapter inspectorTreeAdapter;
    private final ArrayList<InspectorNode> inspectorAllNodes = new ArrayList<>();
    private final ArrayList<InspectorNode> inspectorVisibleNodes = new ArrayList<>();
    private InspectorNode inspectorRootNode;
    private InspectorNode inspectorSelectedNode;
    private int inspectorMaxDepth = INSPECTOR_DEFAULT_MAX_DEPTH;
    private float inspectorOpacity = 0.88f;
    private boolean inspectorInfoExpanded = true;
    private boolean inspectorRunning = false;
    private String inspectorLastError = "";

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Orb Eye v2.4 service connected");
        startHttpServer();
        mainHandler.post(() -> {
            try {
                ensureInspectorControllerOnMain();
            } catch (Exception e) {
                inspectorLastError = e.getMessage() != null ? e.getMessage() : "controller init failed";
                Log.e(TAG, "Inspector controller init failed: " + inspectorLastError);
            }
        });
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
        shutdownInspector();
        releasePaddlePredictor();
    }

    // ===== HTTP Server =====

    private static boolean isOcrRoute(String route) {
        return "/ocr".equals(route) || "/ocr-screen".equals(route) || "/enrich".equals(route);
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
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
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
                body = readUtf8Body(reader, contentLength);
            }

            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts[1];
            String route = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
            String query = path.contains("?") ? path.substring(path.indexOf("?") + 1) : "";

            // /exec: support both JSON body and raw JS body
            if ("/exec".equals(route)) {
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

            String contentType = "application/json; charset=utf-8";
            if ("/inspect".equals(route) && "html".equalsIgnoreCase(getQueryParam(query, "format"))) {
                contentType = "text/html; charset=utf-8";
            }

            OutputStream out = client.getOutputStream();
            byte[] responseBytes = response.getBytes("UTF-8");
            String http = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + contentType + "\r\n"
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

    private String readUtf8Body(BufferedReader reader, int contentLengthBytes) throws Exception {
        if (reader == null || contentLengthBytes <= 0) return "";
        StringBuilder body = new StringBuilder(Math.max(32, contentLengthBytes));
        int consumedBytes = 0;

        while (consumedBytes < contentLengthBytes) {
            int ch = reader.read();
            if (ch < 0) break;
            char c = (char) ch;

            if (Character.isHighSurrogate(c)) {
                int next = reader.read();
                if (next >= 0) {
                    char low = (char) next;
                    body.append(c).append(low);
                    consumedBytes += new String(new char[] { c, low }).getBytes(StandardCharsets.UTF_8).length;
                } else {
                    body.append(c);
                    consumedBytes += String.valueOf(c).getBytes(StandardCharsets.UTF_8).length;
                }
            } else {
                body.append(c);
                consumedBytes += String.valueOf(c).getBytes(StandardCharsets.UTF_8).length;
            }
        }

        if (consumedBytes < contentLengthBytes) {
            Log.w(TAG, "request body truncated: bytesRead=" + consumedBytes + " expected=" + contentLengthBytes);
        }
        return body.toString();
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

            case "/inspector/start":
            case "/inspector/open":
                return handleInspectorStart(query);

            case "/inspector/refresh":
                return handleInspectorRefresh(query);

            case "/inspector/stop":
            case "/inspector/close":
                return handleInspectorStop();

            case "/inspector/state":
                return handleInspectorState();

            case "/inspector/controller/show":
                return handleInspectorControllerShow();

            case "/inspector/controller/hide":
                return handleInspectorControllerHide();

            case "/inspector/opacity":
            case "/inspector/alpha":
                return handleInspectorOpacity(query);

            case "/screen":
                return getScreenElements(query);

            case "/enrich":
                if ("GET".equals(method)) {
                    return handleEnrich(query, null);
                }
                return handleEnrich(query, body == null || body.isEmpty() ? new JSONObject() : new JSONObject(body));

            case "/summary":
                return getScreenSummary(query);

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
     *   format=html        — render an in-browser tree inspector page
     */
    String getUiInspect(String query) throws Exception {
        String format = getQueryParam(query, "format");
        boolean htmlFormat = "html".equalsIgnoreCase(format);
        String mode = getQueryParam(query, "mode");
        boolean layoutMode = "layout".equalsIgnoreCase(mode);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            if (htmlFormat) {
                return renderInspectHtmlError("No active window");
            }
            return errorJson("No active window", "NOT_FOUND");
        }

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

            if (htmlFormat) {
                if (layoutMode) {
                    int sw = getResources().getDisplayMetrics().widthPixels;
                    int sh = getResources().getDisplayMetrics().heightPixels;
                    return renderInspectLayoutHtml(nodes, sw, sh, filterPkg, maxDepth);
                }
                return renderInspectHtml(
                        nodes,
                        filterPkg,
                        maxDepth,
                        clickableOnly,
                        textOnly,
                        enabledOnly,
                        visibleOnly,
                        contains
                );
            }

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("count", nodes.length());
            result.put("nodes", nodes);
            return result.toString();
        } finally {
            root.recycle();
        }
    }

    private String renderInspectHtml(
            JSONArray nodes,
            String filterPkg,
            int maxDepth,
            boolean clickableOnly,
            boolean textOnly,
            boolean enabledOnly,
            boolean visibleOnly,
            String contains
    ) throws Exception {
        StringBuilder sb = new StringBuilder(Math.max(4096, nodes.length() * 240));
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<title>Orb Eye Inspect</title>");
        sb.append("<style>");
        sb.append("body{margin:0;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;background:#0f1115;color:#e6edf3;}");
        sb.append(".wrap{padding:14px 16px 24px 16px;max-width:1400px;margin:0 auto;}");
        sb.append(".top{position:sticky;top:0;background:#0f1115cc;backdrop-filter:blur(6px);padding:10px 0 12px 0;border-bottom:1px solid #222a36;z-index:10;}");
        sb.append(".title{font-size:18px;font-weight:700;margin:0 0 6px 0;color:#8cc8ff;}");
        sb.append(".meta{font-size:12px;color:#9fb1c6;line-height:1.5;}");
        sb.append(".badge{display:inline-block;padding:1px 6px;border-radius:999px;border:1px solid #384355;margin-right:6px;font-size:11px;color:#c6d1de;}");
        sb.append(".node{margin:6px 0;border:1px solid #232c38;border-radius:8px;background:#121720;}");
        sb.append(".node summary{cursor:pointer;padding:8px 10px;list-style:none;outline:none;}");
        sb.append(".node summary::-webkit-details-marker{display:none;}");
        sb.append(".row{display:flex;flex-wrap:wrap;gap:8px;align-items:center;}");
        sb.append(".depth{color:#74c0fc;font-weight:700;}");
        sb.append(".path{color:#a5d8ff;}");
        sb.append(".label{color:#f1f5f9;font-weight:600;}");
        sb.append(".cls{color:#94d2bd;}");
        sb.append(".flags{margin-left:auto;display:flex;gap:6px;flex-wrap:wrap;}");
        sb.append(".flag{font-size:11px;padding:1px 5px;border-radius:5px;border:1px solid #3b4a61;color:#b7c7db;}");
        sb.append(".detail{padding:0 10px 10px 10px;color:#c9d6e2;font-size:12px;line-height:1.45;}");
        sb.append(".detail div{margin-top:4px;word-break:break-all;}");
        sb.append("</style></head><body><div class=\"wrap\">");

        sb.append("<div class=\"top\">");
        sb.append("<h1 class=\"title\">Orb Eye /inspect (HTML)</h1>");
        sb.append("<a href=\"/inspect?format=html&mode=layout\" style=\"font-size:11px;color:#74c0fc;text-decoration:none;margin-left:12px;padding:2px 8px;border:1px solid #384355;border-radius:5px;\">&#x25a3; Layout View</a>");
        sb.append("<div class=\"meta\">");
        sb.append("<span class=\"badge\">count=").append(nodes.length()).append("</span>");
        sb.append("<span class=\"badge\">maxDepth=").append(maxDepth).append("</span>");
        if (filterPkg != null && !filterPkg.isEmpty()) {
            sb.append("<span class=\"badge\">package=").append(escapeHtml(filterPkg)).append("</span>");
        }
        if (contains != null && !contains.isEmpty()) {
            sb.append("<span class=\"badge\">contains=").append(escapeHtml(contains)).append("</span>");
        }
        if (clickableOnly) sb.append("<span class=\"badge\">clickableOnly</span>");
        if (textOnly) sb.append("<span class=\"badge\">textOnly</span>");
        if (enabledOnly) sb.append("<span class=\"badge\">enabledOnly</span>");
        if (visibleOnly) sb.append("<span class=\"badge\">visibleOnly</span>");
        sb.append("</div></div>");

        for (int i = 0; i < nodes.length(); i++) {
            JSONObject item = nodes.getJSONObject(i);
            int depth = item.optInt("depth", 0);
            String path = item.optString("path", "");
            String className = item.optString("class", "");
            String text = item.optString("text", "");
            String desc = item.optString("desc", "");
            String id = item.optString("id", "");
            String pkg = item.optString("pkg", "");
            String bounds = item.optString("bounds", "");
            int centerX = item.optInt("centerX", 0);
            int centerY = item.optInt("centerY", 0);

            String label = !text.isEmpty() ? text
                    : (!desc.isEmpty() ? desc
                    : (!id.isEmpty() ? id : className));
            label = shortenInspectorText(label, 80);
            String classShort = shortenInspectorText(className, 72);

            int indent = Math.max(0, depth) * 14;
            sb.append("<details class=\"node\" ").append(depth <= 1 ? "open" : "").append(" style=\"margin-left:")
                    .append(indent).append("px\">");
            sb.append("<summary><div class=\"row\">");
            sb.append("<span class=\"depth\">D").append(depth).append("</span>");
            sb.append("<span class=\"path\">").append(escapeHtml(path)).append("</span>");
            sb.append("<span class=\"label\">").append(escapeHtml(label)).append("</span>");
            sb.append("<span class=\"cls\">").append(escapeHtml(classShort)).append("</span>");
            sb.append("<span class=\"flags\">");
            if (item.optBoolean("clickable", false)) sb.append("<span class=\"flag\">click</span>");
            if (item.optBoolean("longClickable", false)) sb.append("<span class=\"flag\">long</span>");
            if (item.optBoolean("editable", false)) sb.append("<span class=\"flag\">edit</span>");
            if (item.optBoolean("scrollable", false)) sb.append("<span class=\"flag\">scroll</span>");
            if (item.optBoolean("visibleToUser", false)) sb.append("<span class=\"flag\">visible</span>");
            if (!item.optBoolean("enabled", true)) sb.append("<span class=\"flag\">disabled</span>");
            sb.append("</span>");
            sb.append("</div></summary>");

            sb.append("<div class=\"detail\">");
            sb.append("<div><b>id</b>: ").append(escapeHtml(id)).append("</div>");
            sb.append("<div><b>text</b>: ").append(escapeHtml(text)).append("</div>");
            sb.append("<div><b>desc</b>: ").append(escapeHtml(desc)).append("</div>");
            sb.append("<div><b>pkg</b>: ").append(escapeHtml(pkg)).append("</div>");
            sb.append("<div><b>bounds</b>: ").append(escapeHtml(bounds)).append("</div>");
            sb.append("<div><b>center</b>: ").append(centerX).append(", ").append(centerY)
                    .append(" &nbsp;&nbsp; <b>childCount</b>: ").append(item.optInt("childCount", 0)).append("</div>");
            sb.append("</div></details>");
        }

        sb.append("</div></body></html>");
        return sb.toString();
    }

    /**
     * Renders a layout-simulation HTML page for /inspect?format=html&mode=layout.
     * Shows a scaled phone screen with node bounding boxes overlaid on a screenshot background.
     * Left sidebar: searchable node list. Right panel: selected node detail.
     */
    private String renderInspectLayoutHtml(JSONArray nodes, int sw, int sh, String filterPkg, int maxDepth) throws Exception {
        StringBuilder sb = new StringBuilder(65536);

        // ── Head + CSS ─────────────────────────────────────────────────────────────
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<title>Orb Eye \u2022 Layout</title><style>");
        sb.append("*{box-sizing:border-box;margin:0;padding:0}");
        sb.append("body{background:#0d1117;color:#e6edf3;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;overflow:hidden;height:100vh;display:flex;flex-direction:column}");
        // topbar
        sb.append("#tb{height:40px;background:#161b22;border-bottom:1px solid #21262d;display:flex;align-items:center;gap:8px;padding:0 12px;flex-shrink:0}");
        sb.append("#tb h1{font-size:13px;font-weight:700;color:#58a6ff;white-space:nowrap}");
        sb.append(".tbg{padding:1px 7px;border-radius:999px;border:1px solid #30363d;font-size:10px;color:#8b949e;white-space:nowrap}");
        sb.append(".tbn{font-size:11px;color:#8b949e;text-decoration:none;padding:2px 8px;border:1px solid #30363d;border-radius:5px}");
        sb.append(".tbn:hover{color:#e6edf3;border-color:#6e7681}");
        // layout
        sb.append("#app{display:flex;flex:1;overflow:hidden;min-height:0}");
        // sidebar
        sb.append("#sb{width:248px;flex-shrink:0;display:flex;flex-direction:column;border-right:1px solid #21262d;overflow:hidden}");
        sb.append("#sb-top{padding:7px 8px;border-bottom:1px solid #21262d;flex-shrink:0}");
        sb.append("#q{width:100%;padding:4px 8px;background:#0d1117;border:1px solid #30363d;border-radius:5px;color:#e6edf3;font-size:11px;outline:none}");
        sb.append("#q:focus{border-color:#58a6ff}");
        sb.append("#nl{flex:1;overflow-y:auto;overflow-x:auto}");
        sb.append(".ni{display:flex;align-items:center;gap:3px;cursor:pointer;padding:1px 6px 1px 0;border-left:2px solid transparent;white-space:nowrap}");
        sb.append(".ni:hover{background:#161b22}.ni.sel{background:#1c2d4a;border-left-color:#58a6ff}");
        // tree prefix: dim guide chars, monospace, pre-whitespace, never shrink
        sb.append(".tp{color:#3d444d;white-space:pre;flex-shrink:0;font-size:10px;line-height:1;user-select:none}");
        sb.append(".tpc{color:#6e7681}"); // branch connector slightly brighter than guides
        sb.append(".ni .d{width:6px;height:6px;border-radius:50%;flex-shrink:0}");
        sb.append(".ni .lbl{font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;color:#cdd9e5;min-width:40px}");
        sb.append(".ni .fg{font-size:9px;color:#6e7681;flex-shrink:0;margin-left:2px}");
        // resize handle
        sb.append("#rsz{width:4px;flex-shrink:0;cursor:col-resize;background:transparent;transition:background .15s;position:relative;z-index:10}");
        sb.append("#rsz:hover,#rsz.rz{background:#388bfd55}");
        sb.append("#rsz::after{content:'';position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:2px;height:32px;border-radius:1px;background:#484f58;opacity:0;transition:opacity .15s}");
        sb.append("#rsz:hover::after,#rsz.rz::after{opacity:1}");
        // canvas
        sb.append("#cv{flex:1;display:flex;align-items:center;justify-content:center;overflow:hidden;background:#010409;position:relative}");
        sb.append("#hint{position:absolute;top:6px;right:8px;font-size:10px;color:#484f58;pointer-events:none;user-select:none}");
        sb.append("#pw{position:relative;transform-origin:center center;flex-shrink:0}");
        sb.append("#ps{position:relative;overflow:hidden;background:#111;box-shadow:0 0 0 1px #30363d,0 12px 40px #000a}");
        sb.append("#bg{position:absolute;inset:0;width:100%;height:100%;object-fit:fill;pointer-events:none;z-index:0;opacity:0;transition:opacity .4s}");
        sb.append("#bg.loaded{opacity:1}");
        // node boxes
        sb.append(".nb{position:absolute;cursor:pointer;transition:box-shadow .08s}");
        // type colours (inset box-shadow = inner border, no layout impact)
        sb.append(".nc{background:rgba(59,130,246,.14);box-shadow:inset 0 0 0 1px rgba(59,130,246,.55)}"); // clickable blue
        sb.append(".ne{background:rgba(16,185,129,.17);box-shadow:inset 0 0 0 1px rgba(16,185,129,.65)}"); // editable green
        sb.append(".ns{background:rgba(245,158,11,.11);box-shadow:inset 0 0 0 1px rgba(245,158,11,.5)}");  // scrollable amber
        sb.append(".nk{background:transparent;box-shadow:inset 0 0 0 1px rgba(100,116,139,.18)}");         // container slate dashed effect
        sb.append(".nl2{background:rgba(148,163,184,.04);box-shadow:inset 0 0 0 1px rgba(148,163,184,.18)}"); // leaf
        sb.append(".nb:hover{background:rgba(255,255,255,.12)!important;box-shadow:0 0 0 2px #fff!important;z-index:9998!important}");
        sb.append(".nb.sel{box-shadow:0 0 0 2px #f0f!important;z-index:9999!important}");
        // tooltip
        sb.append("#tip{display:none;position:fixed;background:#1c2128;border:1px solid #30363d;border-radius:6px;padding:5px 9px;font-size:10px;line-height:1.5;max-width:260px;pointer-events:none;z-index:99999;color:#e6edf3;word-break:break-all}");
        sb.append("#tip b{color:#58a6ff}");
        // detail panel
        sb.append("#dp{width:268px;flex-shrink:0;border-left:1px solid #21262d;display:flex;flex-direction:column;overflow:hidden}");
        sb.append("#dp-h{padding:8px 10px;font-size:10px;font-weight:700;color:#6e7681;text-transform:uppercase;letter-spacing:.06em;border-bottom:1px solid #21262d;flex-shrink:0}");
        sb.append("#dp-b{flex:1;overflow-y:auto;padding:8px 10px}");
        sb.append(".dr{margin-bottom:7px}");
        sb.append(".dk{font-size:9px;color:#6e7681;text-transform:uppercase;letter-spacing:.04em;margin-bottom:1px}");
        sb.append(".dv{font-size:11px;color:#cdd9e5;word-break:break-all}");
        sb.append(".df{display:inline-block;padding:1px 6px;border-radius:4px;font-size:9px;font-weight:700;text-transform:uppercase;margin:1px 2px 1px 0}");
        sb.append(".dfc{background:#1b3a6b;color:#58a6ff}");   // clickable - blue
        sb.append(".dfl{background:#1b3052;color:#79c0ff}");   // longClickable - lighter blue
        sb.append(".dfe{background:#1a3a2e;color:#3fb950}");   // editable - green
        sb.append(".dfs{background:#3a2d0a;color:#d29922}");   // scrollable - amber
        sb.append(".dff{background:#2d1f3d;color:#bc8cff}");   // focused/checked - purple
        sb.append(".dfg{background:#1e2530;color:#8b949e}");   // generic - gray
        sb.append(".dfr{background:#3d1a1a;color:#f85149}");   // warning (disabled) - red
        // section headers inside detail panel
        sb.append(".ds{font-size:9px;font-weight:700;color:#484f58;text-transform:uppercase;letter-spacing:.08em;padding:10px 0 4px;border-top:1px solid #21262d;margin-top:2px}");
        sb.append(".ds:first-child{border-top:none;padding-top:2px}");
        // flag rows (show all boolean properties with true/false value)
        sb.append(".fr{display:flex;align-items:center;gap:0;padding:2px 0;line-height:1}");
        sb.append(".fi{width:14px;font-size:9px;flex-shrink:0;text-align:center}");
        sb.append(".fi-t{color:#3fb950}.fi-f{color:#30363d}.fi-w{color:#f85149}");
        sb.append(".fn{font-size:11px;flex:1;color:#8b949e}");
        sb.append(".fv-t{font-size:11px;color:#3fb950;font-weight:600}");
        sb.append(".fv-f{font-size:11px;color:#30363d}");
        sb.append(".fv-w{font-size:11px;color:#f85149;font-weight:600}");
        sb.append("#dp-empty{color:#484f58;padding:16px;font-size:11px;line-height:1.6}");
        sb.append("</style></head><body>");

        // ── Topbar ────────────────────────────────────────────────────────────────
        sb.append("<div id=\"tb\">");
        sb.append("<h1>Orb Eye</h1>");
        sb.append("<span style=\"color:#484f58;font-size:14px\">\u2022</span>");
        sb.append("<span style=\"color:#e6edf3;font-size:12px;font-weight:600\">Layout</span>");
        sb.append("<span class=\"tbg\">").append(nodes.length()).append(" nodes</span>");
        sb.append("<span class=\"tbg\">").append(sw).append("\u00d7").append(sh).append("</span>");
        if (filterPkg != null && !filterPkg.isEmpty()) {
            sb.append("<span class=\"tbg\">").append(escapeHtml(filterPkg)).append("</span>");
        }
        sb.append("<span style=\"flex:1\"></span>");
        sb.append("<a href=\"/inspect?format=html\" class=\"tbn\">\u2190 Tree</a>");
        sb.append("</div>");

        // ── App shell ─────────────────────────────────────────────────────────────
        sb.append("<div id=\"app\">");

        // ── Pre-compute tree prefixes (DFS continuation algorithm) ────────────────
        int nn = nodes.length();
        int[] depths     = new int[nn];
        String[] paths   = new String[nn];
        boolean[] isLast = new boolean[nn];
        String[] treePfx = new String[nn];

        for (int i = 0; i < nn; i++) {
            depths[i] = nodes.getJSONObject(i).optInt("depth", 0);
            paths[i]  = nodes.getJSONObject(i).optString("path", "");
        }
        // isLast[i]: no sibling of node i appears later in the DFS list
        for (int i = 0; i < nn; i++) {
            String pp = paths[i].contains(".") ? paths[i].substring(0, paths[i].lastIndexOf('.')) : "";
            boolean last = true;
            for (int j = i + 1; j < nn; j++) {
                if (depths[j] < depths[i]) break;           // ascended past parent → done
                if (depths[j] == depths[i]) {
                    String jp = paths[j].contains(".") ? paths[j].substring(0, paths[j].lastIndexOf('.')) : "";
                    if (jp.equals(pp)) { last = false; break; }
                }
            }
            isLast[i] = last;
        }
        // Build tree-char prefix for each node using continuing[] stack
        int maxD = 0;
        for (int d : depths) if (d > maxD) maxD = d;
        boolean[] cont = new boolean[maxD + 2]; // cont[k] = ancestor at depth k still has siblings below
        for (int i = 0; i < nn; i++) {
            int d = depths[i];
            StringBuilder pfx = new StringBuilder();
            for (int k = 0; k < d; k++) {
                pfx.append(cont[k] ? "\u2502   " : "    "); // │   or space
            }
            if (d > 0) {
                pfx.append(isLast[i] ? "\u2514\u2500\u2500 " : "\u251c\u2500\u2500 "); // └── or ├──
            }
            treePfx[i] = pfx.toString();
            cont[d] = !isLast[i];
        }

        // ── Sidebar ───────────────────────────────────────────────────────────────
        sb.append("<div id=\"sb\"><div id=\"sb-top\">");
        sb.append("<input id=\"q\" placeholder=\"Filter nodes\u2026\" /></div>");
        sb.append("<div id=\"nl\">");
        for (int i = 0; i < nn; i++) {
            JSONObject n = nodes.getJSONObject(i);
            String text   = n.optString("text", "");
            String desc   = n.optString("desc", "");
            String nodeId = n.optString("id", "");
            String cls    = n.optString("class", "");
            boolean clickable  = n.optBoolean("clickable", false);
            boolean editable   = n.optBoolean("editable", false);
            boolean scrollable = n.optBoolean("scrollable", false);

            String shortId  = nodeId.contains("/") ? nodeId.substring(nodeId.lastIndexOf('/') + 1) : nodeId;
            String shortCls = cls.contains(".")    ? cls.substring(cls.lastIndexOf('.') + 1)         : cls;
            String lbl = !text.isEmpty() ? text : (!desc.isEmpty() ? desc : (!shortId.isEmpty() ? shortId : shortCls));
            lbl = shortenInspectorText(lbl, 30);

            String dotColor = editable ? "#10b981" : clickable ? "#3b82f6" : scrollable ? "#f59e0b" : "#4b5563";
            String flagStr  = (clickable ? "C" : "") + (editable ? "E" : "") + (scrollable ? "S" : "");

            // Split prefix into guide part (│ / spaces) and connector (├── / └──)
            // Last 4 chars of prefix (if depth>0) are the connector; the rest are guides
            String pfx = treePfx[i];
            String guides = "", connector = "";
            if (!pfx.isEmpty() && pfx.length() >= 4) {
                guides    = pfx.substring(0, pfx.length() - 4);
                connector = pfx.substring(pfx.length() - 4);
            }

            sb.append("<div class=\"ni\" data-i=\"").append(i).append("\">");
            if (!guides.isEmpty())    sb.append("<span class=\"tp\">").append(escapeHtml(guides)).append("</span>");
            if (!connector.isEmpty()) sb.append("<span class=\"tp tpc\">").append(escapeHtml(connector)).append("</span>");
            sb.append("<span class=\"d\" style=\"background:").append(dotColor).append("\"></span>");
            sb.append("<span class=\"lbl\">").append(escapeHtml(lbl)).append("</span>");
            if (!flagStr.isEmpty()) sb.append("<span class=\"fg\">").append(flagStr).append("</span>");
            sb.append("</div>");
        }
        sb.append("</div></div>"); // end #nl, #sb

        // resize handle between sidebar and canvas
        sb.append("<div id=\"rsz\"></div>");

        // ── Canvas ────────────────────────────────────────────────────────────────
        sb.append("<div id=\"cv\"><span id=\"hint\"></span>");
        sb.append("<div id=\"pw\"><div id=\"ps\" style=\"width:").append(sw).append("px;height:").append(sh).append("px\">");
        sb.append("<img id=\"bg\" alt=\"\" />");

        for (int i = 0; i < nn; i++) {
            JSONObject n = nodes.getJSONObject(i);
            int left  = n.optInt("left",   0);
            int top   = n.optInt("top",    0);
            int right = n.optInt("right",  0);
            int bot   = n.optInt("bottom", 0);
            int w = right - left;
            int h = bot   - top;
            if (w <= 0 || h <= 0) continue;

            int depth      = n.optInt("depth", 0);
            boolean click  = n.optBoolean("clickable",  false);
            boolean edit   = n.optBoolean("editable",   false);
            boolean scroll = n.optBoolean("scrollable", false);
            int childCount = n.optInt("childCount", 0);

            String typeCls = edit ? "ne" : click ? "nc" : scroll ? "ns" : childCount > 0 ? "nk" : "nl2";

            sb.append("<div class=\"nb ").append(typeCls)
              .append("\" data-i=\"").append(i)
              .append("\" style=\"left:").append(left).append("px;top:").append(top)
              .append("px;width:").append(w).append("px;height:").append(h)
              .append("px;z-index:").append(depth).append("\"></div>");
        }

        sb.append("</div></div></div>"); // end #ps, #pw, #cv

        // ── Detail panel ─────────────────────────────────────────────────────────
        sb.append("<div id=\"dp\"><div id=\"dp-h\">Inspector</div>");
        sb.append("<div id=\"dp-b\"><div id=\"dp-empty\">Click a node<br>to inspect it</div></div>");
        sb.append("</div>");

        // ── Tooltip ───────────────────────────────────────────────────────────────
        sb.append("<div id=\"tip\"></div>");

        sb.append("</div>"); // end #app

        // ── Script ───────────────────────────────────────────────────────────────
        sb.append("<script>");
        sb.append("var N=").append(nodes.toString()).append(";");
        sb.append("var SW=").append(sw).append(",SH=").append(sh).append(",cur=-1;");

        // Scale phone screen to fit canvas
        sb.append("function rescale(){");
        sb.append("var c=document.getElementById('cv');");
        sb.append("var pw=document.getElementById('pw');");
        sb.append("var aw=c.clientWidth-24,ah=c.clientHeight-24;");
        sb.append("var s=Math.min(aw/SW,ah/SH);if(s<=0)s=0.1;");
        sb.append("pw.style.transform='scale('+s+')';");
        sb.append("pw.style.width=SW+'px';pw.style.height=SH+'px';");
        sb.append("document.getElementById('hint').textContent=Math.round(s*100)+'%';");
        sb.append("}");
        sb.append("window.addEventListener('resize',rescale);rescale();");
        // Sidebar resize handle
        sb.append("(function(){");
        sb.append("var rsz=document.getElementById('rsz');");
        sb.append("var sbar=document.getElementById('sb');");
        sb.append("var dragging=false,startX=0,startW=0;");
        sb.append("rsz.addEventListener('mousedown',function(e){");
        sb.append("dragging=true;startX=e.clientX;startW=sbar.offsetWidth;");
        sb.append("rsz.classList.add('rz');");
        sb.append("document.body.style.userSelect='none';");
        sb.append("document.body.style.cursor='col-resize';");
        sb.append("e.preventDefault();");
        sb.append("});");
        sb.append("window.addEventListener('mousemove',function(e){");
        sb.append("if(!dragging)return;");
        sb.append("var w=Math.max(140,Math.min(520,startW+e.clientX-startX));");
        sb.append("sbar.style.width=w+'px';");
        sb.append("});");
        sb.append("window.addEventListener('mouseup',function(){");
        sb.append("if(!dragging)return;");
        sb.append("dragging=false;rsz.classList.remove('rz');");
        sb.append("document.body.style.userSelect='';");
        sb.append("document.body.style.cursor='';");
        sb.append("rescale();");
        sb.append("});");
        sb.append("})();");

        // Load screenshot as background
        sb.append("fetch('/screenshot').then(function(r){return r.json();}).then(function(d){");
        sb.append("if(d&&d.image){var bg=document.getElementById('bg');bg.src=d.image;bg.onload=function(){bg.classList.add('loaded');};}");
        sb.append("}).catch(function(){});");

        // Select node (from canvas click or sidebar click)
        sb.append("function sel(i){");
        sb.append("if(cur>=0){");
        sb.append("var pb=document.querySelector('.nb.sel');if(pb)pb.classList.remove('sel');");
        sb.append("var pl=document.querySelector('.ni.sel');if(pl)pl.classList.remove('sel');");
        sb.append("}");
        sb.append("cur=i;");
        sb.append("var nb=document.querySelector('.nb[data-i=\"'+i+'\"]');if(nb)nb.classList.add('sel');");
        sb.append("var li=document.querySelector('.ni[data-i=\"'+i+'\"]');");
        sb.append("if(li){li.classList.add('sel');li.scrollIntoView({block:'nearest'});}");
        sb.append("renderDetail(i);");
        sb.append("}");

        // Event delegation: canvas clicks + sidebar clicks
        sb.append("document.getElementById('ps').addEventListener('click',function(e){");
        sb.append("var t=e.target.closest('.nb');if(t)sel(+t.dataset.i);");
        sb.append("});");
        sb.append("document.getElementById('nl').addEventListener('click',function(e){");
        sb.append("var t=e.target.closest('.ni');if(t)sel(+t.dataset.i);");
        sb.append("});");

        // Tooltip via event delegation
        sb.append("var tip=document.getElementById('tip');");
        sb.append("document.getElementById('ps').addEventListener('mouseover',function(e){");
        sb.append("var t=e.target.closest('.nb');if(!t){tip.style.display='none';return;}");
        sb.append("var n=N[+t.dataset.i];if(!n)return;");
        sb.append("var cls=n['class']||'';cls=cls.substring(cls.lastIndexOf('.')+1);");
        sb.append("var lbl=n.text||n.desc||n.id||'';if(lbl.length>50)lbl=lbl.substring(0,50)+'...';");
        sb.append("tip.innerHTML='<b>'+eh(cls)+'</b>'+(lbl?'<br>'+eh(lbl):'')+'<br><span style=\"color:#6e7681\">'+eh(n.bounds||'')+'</span>';");
        sb.append("tip.style.display='block';");
        sb.append("});");
        sb.append("document.getElementById('ps').addEventListener('mouseout',function(e){");
        sb.append("if(!e.relatedTarget||!e.relatedTarget.closest('.nb'))tip.style.display='none';");
        sb.append("});");
        sb.append("window.addEventListener('mousemove',function(e){");
        sb.append("if(tip.style.display!=='none'){tip.style.left=(e.clientX+14)+'px';tip.style.top=(e.clientY-8)+'px';}");
        sb.append("});");

        // Render detail panel - full property display
        sb.append("function row(k,v){");
        sb.append("if(v===undefined||v===null||v==='')return'';");
        sb.append("return'<div class=\"dr\"><div class=\"dk\">'+k+'</div><div class=\"dv\">'+eh(String(v))+'</div></div>';");
        sb.append("}");
        sb.append("function renderDetail(i){");
        sb.append("var n=N[i],b=document.getElementById('dp-b');");
        sb.append("if(!n){b.innerHTML='<div id=\"dp-empty\">No data</div>';return;}");
        sb.append("var h='';");
        // ── Identity
        sb.append("h+='<div class=\"ds\">Identity</div>';");
        sb.append("var fullCls=n['class']||'';");
        sb.append("var shortCls=fullCls.substring(fullCls.lastIndexOf('.')+1);");
        sb.append("h+=row('class',shortCls+(shortCls!==fullCls?' ('+fullCls+')':''));");
        sb.append("h+=row('id',n.id);");
        sb.append("h+=row('pkg',n.pkg);");
        // ── Content
        sb.append("if(n.text||n.desc){");
        sb.append("h+='<div class=\"ds\">Content</div>';");
        sb.append("h+=row('text',n.text);");
        sb.append("h+=row('desc',n.desc);");
        sb.append("}");
        // ── Position
        sb.append("h+='<div class=\"ds\">Position</div>';");
        sb.append("var w=(n.right||0)-(n.left||0),hh=(n.bottom||0)-(n.top||0);");
        sb.append("h+=row('bounds','['+n.left+', '+n.top+'] \u2192 ['+n.right+', '+n.bottom+']');");
        sb.append("h+=row('size',w+' \u00d7 '+hh);");
        sb.append("h+=row('center',n.centerX+', '+n.centerY);");
        // ── Flags: show every boolean with its actual true/false value
        sb.append("h+='<div class=\"ds\">Flags</div>';");
        sb.append("function flag(name,val,warn){");
        sb.append("var ic=warn?(val?'fi-t':'fi-w'):(val?'fi-t':'fi-f');");
        sb.append("var vc=warn?(val?'fv-t':'fv-w'):(val?'fv-t':'fv-f');");
        sb.append("var dot=val?'\u25cf':'\u25cb';");  // ● true, ○ false
        sb.append("return'<div class=\"fr\"><span class=\"fi '+ic+'\">'+dot+'</span>'");
        sb.append("+'<span class=\"fn\">'+name+'</span>'");
        sb.append("+'<span class=\"'+vc+'\">'+val+'</span></div>';");
        sb.append("}");
        sb.append("h+=flag('enabled',      n.enabled,      true);");   // warn when false
        sb.append("h+=flag('clickable',    n.clickable,    false);");
        sb.append("h+=flag('longClickable',n.longClickable,false);");
        sb.append("h+=flag('editable',     n.editable,     false);");
        sb.append("h+=flag('scrollable',   n.scrollable,   false);");
        sb.append("h+=flag('focusable',    n.focusable,    false);");
        sb.append("h+=flag('focused',      n.focused,      false);");
        sb.append("h+=flag('selected',     n.selected,     false);");
        sb.append("h+=flag('checkable',    n.checkable,    false);");
        sb.append("h+=flag('checked',      n.checked,      false);");
        sb.append("h+=flag('visibleToUser',n.visibleToUser,false);");
        // ── Tree
        sb.append("h+='<div class=\"ds\">Tree</div>';");
        sb.append("h+=row('depth',n.depth);");
        sb.append("h+=row('path',n.path);");
        sb.append("h+=row('indexInParent',n.indexInParent);");
        sb.append("h+=row('childCount',n.childCount);");
        sb.append("b.innerHTML=h;");
        sb.append("}");

        // Search / filter sidebar
        sb.append("document.getElementById('q').addEventListener('input',function(){");
        sb.append("var q=this.value.toLowerCase();");
        sb.append("document.querySelectorAll('.ni').forEach(function(el){");
        sb.append("var n=N[+el.dataset.i];");
        sb.append("var m=!q||(n.text&&n.text.toLowerCase().includes(q))");
        sb.append("||(n.desc&&n.desc.toLowerCase().includes(q))");
        sb.append("||(n.id&&n.id.toLowerCase().includes(q))");
        sb.append("||(n['class']&&n['class'].toLowerCase().includes(q));");
        sb.append("el.style.display=m?'':'none';");
        sb.append("});");
        sb.append("});");

        // HTML escape helper
        sb.append("function eh(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;');}");

        sb.append("</script></body></html>");
        return sb.toString();
    }

    private String renderInspectHtmlError(String message) {
        String safe = escapeHtml(message != null ? message : "Unknown error");
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Orb Eye Inspect</title>"
                + "<style>body{font-family:ui-monospace,Menlo,Consolas,monospace;background:#0f1115;color:#e6edf3;padding:24px;} .err{color:#ff8b8b;}</style>"
                + "</head><body><h1>Orb Eye /inspect (HTML)</h1><div class=\"err\">" + safe + "</div></body></html>";
    }

    private String escapeHtml(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            switch (ch) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                case '\'':
                    out.append("&#39;");
                    break;
                default:
                    out.append(ch);
                    break;
            }
        }
        return out.toString();
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

    private float getQueryFloat(String query, String key, float defaultValue) {
        String val = getQueryParam(query, key);
        if (val == null || val.isEmpty()) return defaultValue;
        try {
            return Float.parseFloat(val);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean containsIgnoreCase(String haystack, String needle) {
        if (needle == null || needle.isEmpty()) return true;
        if (haystack == null || haystack.isEmpty()) return false;
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    // ===== Floating Inspector (AutoJs6-style) =====

    private String handleInspectorStart(String query) throws Exception {
        final AtomicReference<String> outRef = new AtomicReference<>(null);
        final AtomicReference<String> errRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                int maxDepth = clampInt(getQueryInt(query, "maxDepth", INSPECTOR_DEFAULT_MAX_DEPTH), 0, 60);
                inspectorMaxDepth = maxDepth;
                startInspectorOnMain(maxDepth);
                outRef.set(buildInspectorStateJsonOnMain().toString());
            } catch (Exception e) {
                errRef.set(e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        boolean done = latch.await(5, TimeUnit.SECONDS);
        if (!done) return errorJson("Inspector start timed out", "TIMEOUT");
        if (errRef.get() != null) return errorJson("Inspector start failed: " + errRef.get(), "INSPECTOR_FAILED");
        return outRef.get() != null ? outRef.get() : errorJson("Inspector start failed", "INSPECTOR_FAILED");
    }

    private String handleInspectorRefresh(String query) throws Exception {
        final AtomicReference<String> outRef = new AtomicReference<>(null);
        final AtomicReference<String> errRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                int maxDepth = clampInt(getQueryInt(query, "maxDepth", INSPECTOR_DEFAULT_MAX_DEPTH), 0, 60);
                inspectorMaxDepth = maxDepth;
                if (!inspectorRunning) {
                    startInspectorOnMain(maxDepth);
                } else {
                    refreshInspectorDataOnMain(maxDepth, true);
                }
                outRef.set(buildInspectorStateJsonOnMain().toString());
            } catch (Exception e) {
                errRef.set(e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        boolean done = latch.await(5, TimeUnit.SECONDS);
        if (!done) return errorJson("Inspector refresh timed out", "TIMEOUT");
        if (errRef.get() != null) return errorJson("Inspector refresh failed: " + errRef.get(), "INSPECTOR_FAILED");
        return outRef.get() != null ? outRef.get() : errorJson("Inspector refresh failed", "INSPECTOR_FAILED");
    }

    private String handleInspectorStop() throws Exception {
        final AtomicReference<String> outRef = new AtomicReference<>(null);
        final AtomicReference<String> errRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                stopInspectorOnMain();
                outRef.set(buildInspectorStateJsonOnMain().toString());
            } catch (Exception e) {
                errRef.set(e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        boolean done = latch.await(5, TimeUnit.SECONDS);
        if (!done) return errorJson("Inspector stop timed out", "TIMEOUT");
        if (errRef.get() != null) return errorJson("Inspector stop failed: " + errRef.get(), "INSPECTOR_FAILED");
        return outRef.get() != null ? outRef.get() : errorJson("Inspector stop failed", "INSPECTOR_FAILED");
    }

    private String handleInspectorState() throws Exception {
        final AtomicReference<String> outRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                outRef.set(buildInspectorStateJsonOnMain().toString());
            } catch (Exception e) {
                try {
                    outRef.set(errorJson("Inspector state failed: " + e.getMessage(), "INSPECTOR_FAILED"));
                } catch (Exception ignored) {
                    outRef.set("{\"ok\":false,\"error\":\"Inspector state failed\",\"code\":\"INSPECTOR_FAILED\"}");
                }
            } finally {
                latch.countDown();
            }
        });
        boolean done = latch.await(5, TimeUnit.SECONDS);
        if (!done) return errorJson("Inspector state timed out", "TIMEOUT");
        return outRef.get() != null ? outRef.get() : errorJson("Inspector state unavailable", "INSPECTOR_FAILED");
    }

    private String handleInspectorControllerShow() throws Exception {
        final AtomicReference<String> outRef = new AtomicReference<>(null);
        final AtomicReference<String> errRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                ensureInspectorControllerOnMain();
                outRef.set(buildInspectorStateJsonOnMain().toString());
            } catch (Exception e) {
                errRef.set(e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        boolean done = latch.await(5, TimeUnit.SECONDS);
        if (!done) return errorJson("Inspector controller show timed out", "TIMEOUT");
        if (errRef.get() != null) return errorJson("Inspector controller show failed: " + errRef.get(), "INSPECTOR_FAILED");
        return outRef.get() != null ? outRef.get() : errorJson("Inspector controller show failed", "INSPECTOR_FAILED");
    }

    private String handleInspectorControllerHide() throws Exception {
        final AtomicReference<String> outRef = new AtomicReference<>(null);
        final AtomicReference<String> errRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                removeInspectorControllerOnMain();
                outRef.set(buildInspectorStateJsonOnMain().toString());
            } catch (Exception e) {
                errRef.set(e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        boolean done = latch.await(5, TimeUnit.SECONDS);
        if (!done) return errorJson("Inspector controller hide timed out", "TIMEOUT");
        if (errRef.get() != null) return errorJson("Inspector controller hide failed: " + errRef.get(), "INSPECTOR_FAILED");
        return outRef.get() != null ? outRef.get() : errorJson("Inspector controller hide failed", "INSPECTOR_FAILED");
    }

    private String handleInspectorOpacity(String query) throws Exception {
        final AtomicReference<String> outRef = new AtomicReference<>(null);
        final AtomicReference<String> errRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                float requested = getQueryFloat(query, "value", Float.NaN);
                if (Float.isNaN(requested)) requested = getQueryFloat(query, "opacity", Float.NaN);
                if (Float.isNaN(requested)) requested = getQueryFloat(query, "alpha", Float.NaN);

                float next = inspectorOpacity;
                if (!Float.isNaN(requested)) {
                    next = requested;
                } else {
                    float step = getQueryFloat(query, "step", 0.05f);
                    String action = getQueryParam(query, "action");
                    if ("up".equalsIgnoreCase(action)) {
                        next = inspectorOpacity + step;
                    } else if ("down".equalsIgnoreCase(action)) {
                        next = inspectorOpacity - step;
                    }
                }
                setInspectorOpacityOnMain(next);
                outRef.set(buildInspectorStateJsonOnMain().toString());
            } catch (Exception e) {
                errRef.set(e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        boolean done = latch.await(5, TimeUnit.SECONDS);
        if (!done) return errorJson("Inspector opacity update timed out", "TIMEOUT");
        if (errRef.get() != null) return errorJson("Inspector opacity update failed: " + errRef.get(), "INSPECTOR_FAILED");
        return outRef.get() != null ? outRef.get() : errorJson("Inspector opacity update failed", "INSPECTOR_FAILED");
    }

    private void setInspectorOpacityOnMain(float requestedOpacity) {
        inspectorOpacity = clampFloat(requestedOpacity, 0.20f, 1.0f);
        if (inspectorControlView != null) {
            inspectorControlView.setAlpha(inspectorOpacity);
        }
        if (inspectorPanelView != null) {
            inspectorPanelView.setAlpha(Math.max(0.30f, inspectorOpacity));
        }
        if (inspectorBreadcrumbPanelView != null) {
            inspectorBreadcrumbPanelView.setAlpha(Math.max(0.35f, inspectorOpacity));
        }
        if (inspectorBoundsView != null) {
            inspectorBoundsView.setOverlayOpacity(inspectorOpacity);
        }
        updateInspectorControlButtonsOnMain();
    }

    private void ensureInspectorControllerOnMain() throws Exception {
        if (inspectorWindowManager == null) {
            inspectorWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        if (inspectorWindowManager == null) {
            throw new IllegalStateException("WindowManager unavailable");
        }
        if (inspectorControlView == null) {
            inspectorControlView = buildInspectorControlView();
        }
        if (inspectorControlView.getParent() == null) {
            if (inspectorControlLayoutParams == null) {
                inspectorControlLayoutParams = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                );
                inspectorControlLayoutParams.gravity = Gravity.TOP | Gravity.START;
                inspectorControlLayoutParams.x = dp(8);
                inspectorControlLayoutParams.y = dp(120);
                inspectorControlLayoutParams.setTitle("orb-eye-inspector-controller");
            }
            inspectorWindowManager.addView(inspectorControlView, inspectorControlLayoutParams);
        }
        setInspectorOpacityOnMain(inspectorOpacity);
        updateInspectorControlButtonsOnMain();
    }

    private void removeInspectorControllerOnMain() {
        if (inspectorWindowManager == null || inspectorControlView == null) return;
        if (inspectorControlView.getParent() != null) {
            try {
                inspectorWindowManager.removeView(inspectorControlView);
            } catch (Exception ignored) {
                // ignore stale window reference
            }
        }
    }

    private LinearLayout buildInspectorControlView() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xDD11161C);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));

        TextView dragHandle = new TextView(this);
        dragHandle.setText("Orb");
        dragHandle.setTextColor(Color.WHITE);
        dragHandle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dragHandle.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.addView(dragHandle);

        final float[] startTouch = new float[2];
        final int[] startPos = new int[2];
        dragHandle.setOnTouchListener((v, event) -> {
            if (inspectorControlLayoutParams == null || inspectorWindowManager == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startTouch[0] = event.getRawX();
                    startTouch[1] = event.getRawY();
                    startPos[0] = inspectorControlLayoutParams.x;
                    startPos[1] = inspectorControlLayoutParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    inspectorControlLayoutParams.x = startPos[0] + (int) (event.getRawX() - startTouch[0]);
                    inspectorControlLayoutParams.y = startPos[1] + (int) (event.getRawY() - startTouch[1]);
                    inspectorWindowManager.updateViewLayout(inspectorControlView, inspectorControlLayoutParams);
                    return true;
                default:
                    return false;
            }
        });

        Button toggleButton = new Button(this);
        toggleButton.setText("Inspect");
        toggleButton.setOnClickListener(v -> {
            try {
                if (inspectorRunning) {
                    stopInspectorOnMain();
                } else {
                    startInspectorOnMain(inspectorMaxDepth);
                }
                updateInspectorControlButtonsOnMain();
            } catch (Exception e) {
                inspectorLastError = e.getMessage() != null ? e.getMessage() : "toggle failed";
                updateInspectorHeaderOnMain();
            }
        });
        inspectorControlToggleButton = toggleButton;
        bar.addView(toggleButton);

        Button refreshButton = new Button(this);
        refreshButton.setText("R");
        refreshButton.setOnClickListener(v -> {
            try {
                if (inspectorRunning) {
                    refreshInspectorDataOnMain(inspectorMaxDepth, true);
                }
            } catch (Exception e) {
                inspectorLastError = e.getMessage() != null ? e.getMessage() : "refresh failed";
                updateInspectorHeaderOnMain();
            }
        });
        inspectorControlRefreshButton = refreshButton;
        bar.addView(refreshButton);

        Button opacityDownButton = new Button(this);
        opacityDownButton.setText("-");
        opacityDownButton.setOnClickListener(v -> setInspectorOpacityOnMain(inspectorOpacity - 0.08f));
        bar.addView(opacityDownButton);

        TextView opacityText = new TextView(this);
        opacityText.setTextColor(Color.WHITE);
        opacityText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        opacityText.setPadding(dp(6), dp(8), dp(6), dp(8));
        inspectorControlOpacityTextView = opacityText;
        bar.addView(opacityText);

        Button opacityUpButton = new Button(this);
        opacityUpButton.setText("+");
        opacityUpButton.setOnClickListener(v -> setInspectorOpacityOnMain(inspectorOpacity + 0.08f));
        bar.addView(opacityUpButton);

        Button closeButton = new Button(this);
        closeButton.setText("X");
        closeButton.setOnClickListener(v -> removeInspectorControllerOnMain());
        bar.addView(closeButton);
        return bar;
    }

    private void updateInspectorControlButtonsOnMain() {
        if (inspectorControlToggleButton != null) {
            inspectorControlToggleButton.setText(inspectorRunning ? "Stop" : "Inspect");
        }
        if (inspectorControlRefreshButton != null) {
            inspectorControlRefreshButton.setEnabled(inspectorRunning);
        }
        if (inspectorControlOpacityTextView != null) {
            int percent = Math.round(inspectorOpacity * 100f);
            inspectorControlOpacityTextView.setText(percent + "%");
        }
    }

    private void startInspectorOnMain(int maxDepth) throws Exception {
        ensureInspectorControllerOnMain();
        if (inspectorWindowManager == null) {
            inspectorWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        if (inspectorWindowManager == null) {
            throw new IllegalStateException("WindowManager unavailable");
        }

        if (inspectorBoundsView == null) {
            inspectorBoundsView = new InspectorBoundsView(this);
        }
        if (inspectorPanelView == null) {
            inspectorPanelView = buildInspectorPanelView();
        }
        if (inspectorBreadcrumbPanelView == null) {
            inspectorBreadcrumbPanelView = buildInspectorBreadcrumbPanelView();
        }

        if (inspectorBoundsView.getParent() == null) {
            WindowManager.LayoutParams overlayParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
            );
            overlayParams.gravity = Gravity.TOP | Gravity.START;
            overlayParams.setTitle("orb-eye-inspector-overlay");
            inspectorWindowManager.addView(inspectorBoundsView, overlayParams);
        }

        if (inspectorPanelView.getParent() == null) {
            if (inspectorPanelLayoutParams == null) {
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                int panelWidth = Math.min(dp(360), Math.max(dp(220), screenWidth - dp(24)));
                int panelHeight = Math.min(dp(620), Math.max(dp(320), screenHeight - dp(120)));
                inspectorPanelLayoutParams = new WindowManager.LayoutParams(
                        panelWidth,
                        panelHeight,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                );
                inspectorPanelLayoutParams.gravity = Gravity.TOP | Gravity.START;
                inspectorPanelLayoutParams.x = Math.max(dp(8), screenWidth - panelWidth - dp(8));
                inspectorPanelLayoutParams.y = dp(120);
                inspectorPanelLayoutParams.setTitle("orb-eye-inspector-panel");
            }
            inspectorWindowManager.addView(inspectorPanelView, inspectorPanelLayoutParams);
        }
        if (inspectorBreadcrumbPanelView.getParent() == null) {
            if (inspectorBreadcrumbLayoutParams == null) {
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                int breadcrumbWidth = Math.max(dp(220), screenWidth - dp(16));
                inspectorBreadcrumbLayoutParams = new WindowManager.LayoutParams(
                        breadcrumbWidth,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                );
                inspectorBreadcrumbLayoutParams.gravity = Gravity.TOP | Gravity.START;
                inspectorBreadcrumbLayoutParams.x = dp(8);
                inspectorBreadcrumbLayoutParams.y = Math.max(dp(8), screenHeight - dp(120));
                inspectorBreadcrumbLayoutParams.setTitle("orb-eye-inspector-breadcrumb");
            }
            inspectorWindowManager.addView(inspectorBreadcrumbPanelView, inspectorBreadcrumbLayoutParams);
        }

        inspectorRunning = true;
        inspectorLastError = "";
        setInspectorOpacityOnMain(inspectorOpacity);
        refreshInspectorDataOnMain(maxDepth, true);
        updateInspectorControlButtonsOnMain();
    }

    private void stopInspectorOnMain() {
        if (inspectorWindowManager != null) {
            if (inspectorPanelView != null && inspectorPanelView.getParent() != null) {
                try {
                    inspectorWindowManager.removeView(inspectorPanelView);
                } catch (Exception ignored) {
                    // ignore stale window reference
                }
            }
            if (inspectorBreadcrumbPanelView != null && inspectorBreadcrumbPanelView.getParent() != null) {
                try {
                    inspectorWindowManager.removeView(inspectorBreadcrumbPanelView);
                } catch (Exception ignored) {
                    // ignore stale window reference
                }
            }
            if (inspectorBoundsView != null && inspectorBoundsView.getParent() != null) {
                try {
                    inspectorWindowManager.removeView(inspectorBoundsView);
                } catch (Exception ignored) {
                    // ignore stale window reference
                }
            }
        }
        inspectorRunning = false;
        inspectorSelectedNode = null;
        inspectorRootNode = null;
        inspectorAllNodes.clear();
        inspectorVisibleNodes.clear();
        updateInspectorParentButtonOnMain();
        updateInspectorBreadcrumbOnMain(null);
        updateInspectorControlButtonsOnMain();
    }

    private void shutdownInspector() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopInspectorOnMain();
            removeInspectorControllerOnMain();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                stopInspectorOnMain();
                removeInspectorControllerOnMain();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private LinearLayout buildInspectorPanelView() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xEE10151A);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setText("Orb Inspector  (drag)");
        title.setPadding(dp(8), dp(8), dp(8), dp(8));
        inspectorHeaderTextView = title;
        final float[] panelStartTouch = new float[2];
        final int[] panelStartPos = new int[2];
        title.setOnTouchListener((v, event) -> {
            if (inspectorPanelLayoutParams == null || inspectorWindowManager == null || inspectorPanelView == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    panelStartTouch[0] = event.getRawX();
                    panelStartTouch[1] = event.getRawY();
                    panelStartPos[0] = inspectorPanelLayoutParams.x;
                    panelStartPos[1] = inspectorPanelLayoutParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    inspectorPanelLayoutParams.x = panelStartPos[0] + (int) (event.getRawX() - panelStartTouch[0]);
                    inspectorPanelLayoutParams.y = panelStartPos[1] + (int) (event.getRawY() - panelStartTouch[1]);
                    inspectorPanelLayoutParams.x = Math.max(0, inspectorPanelLayoutParams.x);
                    inspectorPanelLayoutParams.y = Math.max(0, inspectorPanelLayoutParams.y);
                    inspectorWindowManager.updateViewLayout(inspectorPanelView, inspectorPanelLayoutParams);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        });

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(0, dp(6), 0, dp(6));

        Button refreshButton = new Button(this);
        refreshButton.setText("Refresh");
        refreshButton.setOnClickListener(v -> {
            try {
                refreshInspectorDataOnMain(inspectorMaxDepth, true);
            } catch (Exception e) {
                inspectorLastError = e.getMessage() != null ? e.getMessage() : "refresh failed";
                updateInspectorHeaderOnMain();
            }
        });

        Button parentButton = new Button(this);
        parentButton.setText("↑Parent");
        parentButton.setOnClickListener(v -> {
            if (inspectorSelectedNode != null && inspectorSelectedNode.parent != null) {
                selectInspectorNodeOnMain(inspectorSelectedNode.parent, false);
            }
        });
        inspectorParentButton = parentButton;

        Button closeButton = new Button(this);
        closeButton.setText("Close");
        closeButton.setOnClickListener(v -> stopInspectorOnMain());

        Button infoToggleButton = new Button(this);
        infoToggleButton.setOnClickListener(v -> setInspectorInfoExpandedOnMain(!inspectorInfoExpanded));
        inspectorInfoToggleButton = infoToggleButton;

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        toolbar.addView(parentButton, btnLp);
        toolbar.addView(refreshButton, btnLp);
        toolbar.addView(infoToggleButton, btnLp);
        toolbar.addView(closeButton, btnLp);

        ListView listView = new ListView(this);
        listView.setDividerHeight(1);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        inspectorTreeListView = listView;

        inspectorTreeAdapter = new InspectorTreeAdapter(this, inspectorVisibleNodes);
        listView.setAdapter(inspectorTreeAdapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= inspectorVisibleNodes.size()) return;
            selectInspectorNodeOnMain(inspectorVisibleNodes.get(position), false);
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= inspectorVisibleNodes.size()) return true;
            InspectorNode node = inspectorVisibleNodes.get(position);
            if (node.children.isEmpty()) return true;
            node.expanded = !node.expanded;
            rebuildVisibleInspectorNodes();
            if (inspectorTreeAdapter != null) inspectorTreeAdapter.notifyDataSetChanged();
            selectInspectorNodeOnMain(node, false);
            return true;
        });

        TextView info = new TextView(this);
        info.setTextColor(0xFFE4E8EE);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.setBackgroundColor(0xCC0B0F14);
        info.setPadding(dp(8), dp(8), dp(8), dp(8));
        info.setText("Tap node to select. Long press list node to collapse/expand.");
        inspectorInfoTextView = info;

        panel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        panel.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));
        setInspectorInfoExpandedOnMain(inspectorInfoExpanded);
        updateInspectorParentButtonOnMain();
        return panel;
    }

    private LinearLayout buildInspectorBreadcrumbPanelView() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xDD10151A);
        panel.setPadding(dp(8), dp(6), dp(8), dp(8));
        panel.setClickable(true);
        panel.setLongClickable(true);

        TextView title = new TextView(this);
        title.setText("Path (drag title to move, tap ancestor/child)");
        title.setTextColor(0xFFDDE6F3);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        title.setPadding(dp(4), 0, dp(4), dp(4));
        final float[] startTouch = new float[2];
        final int[] startPos = new int[2];
        title.setOnTouchListener((v, event) -> {
            if (inspectorBreadcrumbLayoutParams == null
                    || inspectorWindowManager == null
                    || inspectorBreadcrumbPanelView == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startTouch[0] = event.getRawX();
                    startTouch[1] = event.getRawY();
                    startPos[0] = inspectorBreadcrumbLayoutParams.x;
                    startPos[1] = inspectorBreadcrumbLayoutParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int screenWidth = getResources().getDisplayMetrics().widthPixels;
                    int screenHeight = getResources().getDisplayMetrics().heightPixels;
                    int panelWidth = inspectorBreadcrumbPanelView.getWidth() > 0
                            ? inspectorBreadcrumbPanelView.getWidth()
                            : inspectorBreadcrumbLayoutParams.width;
                    int panelHeight = inspectorBreadcrumbPanelView.getHeight() > 0
                            ? inspectorBreadcrumbPanelView.getHeight()
                            : dp(72);
                    inspectorBreadcrumbLayoutParams.x = clampInt(
                            startPos[0] + (int) (event.getRawX() - startTouch[0]),
                            0,
                            Math.max(0, screenWidth - panelWidth)
                    );
                    inspectorBreadcrumbLayoutParams.y = clampInt(
                            startPos[1] + (int) (event.getRawY() - startTouch[1]),
                            0,
                            Math.max(0, screenHeight - panelHeight)
                    );
                    inspectorWindowManager.updateViewLayout(inspectorBreadcrumbPanelView, inspectorBreadcrumbLayoutParams);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        });

        HorizontalScrollView scrollView = new HorizontalScrollView(this);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setFillViewport(true);
        inspectorBreadcrumbScrollView = scrollView;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(2), dp(2), dp(2), dp(2));
        inspectorBreadcrumbContainer = row;

        scrollView.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        updateInspectorBreadcrumbOnMain(null);
        return panel;
    }

    private void updateInspectorBreadcrumbOnMain(InspectorNode node) {
        if (inspectorBreadcrumbContainer == null) return;
        inspectorBreadcrumbContainer.removeAllViews();
        if (node == null) {
            TextView empty = new TextView(this);
            empty.setText("No node selected");
            empty.setTextColor(0xFF94A3B8);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            empty.setPadding(dp(6), dp(4), dp(6), dp(4));
            inspectorBreadcrumbContainer.addView(empty);
            return;
        }

        ArrayList<InspectorNode> chain = new ArrayList<>();
        InspectorNode current = node;
        while (current != null) {
            chain.add(0, current);
            current = current.parent;
        }

        for (int i = 0; i < chain.size(); i++) {
            final InspectorNode item = chain.get(i);
            TextView crumb = new TextView(this);
            int levelColor = getInspectorLevelColor(item.depth);
            boolean selected = item == node;
            crumb.setText(getInspectorBreadcrumbLabel(item));
            crumb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            crumb.setTextColor(0xFFF3F7FF);
            crumb.setPadding(dp(8), dp(6), dp(8), dp(6));
            crumb.setBackgroundColor(selected ? withAlpha(levelColor, 0xAA) : withAlpha(levelColor, 0x44));
            crumb.setOnClickListener(v -> selectInspectorNodeOnMain(item, false));
            inspectorBreadcrumbContainer.addView(crumb);

            if (i < chain.size() - 1) {
                TextView sep = new TextView(this);
                sep.setText(" > ");
                sep.setTextColor(0xFF8FA2B8);
                sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                sep.setPadding(dp(4), dp(6), dp(4), dp(6));
                inspectorBreadcrumbContainer.addView(sep);
            }
        }

        if (!node.children.isEmpty()) {
            TextView split = new TextView(this);
            split.setText("   | Sons(" + node.children.size() + ") ");
            split.setTextColor(0xFF9FB3C8);
            split.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            split.setPadding(dp(8), dp(6), dp(8), dp(6));
            inspectorBreadcrumbContainer.addView(split);

            for (int i = 0; i < node.children.size(); i++) {
                final InspectorNode child = node.children.get(i);
                TextView childCrumb = new TextView(this);
                int childColor = getInspectorLevelColor(child.depth);
                childCrumb.setText(getInspectorBreadcrumbLabel(child));
                childCrumb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                childCrumb.setTextColor(0xFFF3F7FF);
                childCrumb.setPadding(dp(8), dp(6), dp(8), dp(6));
                childCrumb.setBackgroundColor(withAlpha(childColor, 0x55));
                childCrumb.setOnClickListener(v -> selectInspectorNodeOnMain(child, false));
                inspectorBreadcrumbContainer.addView(childCrumb);

                if (i < node.children.size() - 1) {
                    TextView sep = new TextView(this);
                    sep.setText(" ");
                    sep.setTextColor(0xFF8FA2B8);
                    sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    sep.setPadding(dp(2), dp(6), dp(2), dp(6));
                    inspectorBreadcrumbContainer.addView(sep);
                }
            }
        }

        if (inspectorBreadcrumbScrollView != null) {
            inspectorBreadcrumbScrollView.post(() -> inspectorBreadcrumbScrollView.fullScroll(View.FOCUS_RIGHT));
        }
    }

    private String getInspectorBreadcrumbLabel(InspectorNode node) {
        if (node == null) return "";
        String className = simplifyInspectorClassName(node.className);
        if (className == null || className.isEmpty()) {
            className = "node";
        }

        String shortId = "";
        if (node.id != null && !node.id.isEmpty()) {
            int slash = node.id.lastIndexOf('/');
            shortId = slash >= 0 && slash + 1 < node.id.length() ? node.id.substring(slash + 1) : node.id;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(node.depth).append(" ");
        if (node.depth == 0) {
            sb.append("ROOT");
        } else {
            sb.append(className);
            if (node.indexInParent >= 0) {
                sb.append("[").append(node.indexInParent).append("]");
            }
        }
        if (!shortId.isEmpty()) {
            sb.append("#").append(shortenInspectorText(shortId, 12));
        }
        return shortenInspectorText(sb.toString(), 28);
    }

    private void setInspectorInfoExpandedOnMain(boolean expanded) {
        inspectorInfoExpanded = expanded;
        if (inspectorInfoTextView != null) {
            inspectorInfoTextView.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (inspectorInfoToggleButton != null) {
            inspectorInfoToggleButton.setText(expanded ? "Info -" : "Info +");
        }
    }

    private void updateInspectorParentButtonOnMain() {
        if (inspectorParentButton == null) return;
        boolean canGoParent = inspectorSelectedNode != null && inspectorSelectedNode.parent != null;
        inspectorParentButton.setEnabled(canGoParent);
    }

    private void refreshInspectorDataOnMain(int maxDepth, boolean keepSelection) throws Exception {
        inspectorLastError = "";
        Map<String, Boolean> expandedState = new HashMap<>();
        for (InspectorNode node : inspectorAllNodes) {
            expandedState.put(node.path, node.expanded);
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            inspectorLastError = "No active window";
            inspectorRootNode = null;
            inspectorAllNodes.clear();
            inspectorVisibleNodes.clear();
            inspectorSelectedNode = null;
            if (inspectorBoundsView != null) {
                inspectorBoundsView.setNodes(Collections.emptyList());
            }
            if (inspectorTreeAdapter != null) inspectorTreeAdapter.notifyDataSetChanged();
            if (inspectorInfoTextView != null) inspectorInfoTextView.setText("No active window.");
            updateInspectorParentButtonOnMain();
            updateInspectorBreadcrumbOnMain(null);
            updateInspectorHeaderOnMain();
            return;
        }

        String selectedPath = keepSelection && inspectorSelectedNode != null
                ? inspectorSelectedNode.path
                : null;

        try {
            InspectorNode rootNode = captureInspectorNode(root, null, 0, -1, "0", maxDepth);
            applyExpansionState(rootNode, expandedState);
            inspectorRootNode = rootNode;
            inspectorAllNodes.clear();
            flattenInspectorNodes(rootNode, inspectorAllNodes);
            rebuildVisibleInspectorNodes();

            if (inspectorBoundsView != null) {
                inspectorBoundsView.setNodes(inspectorAllNodes);
            }

            if (selectedPath != null) {
                inspectorSelectedNode = findInspectorNodeByPath(selectedPath);
            }
            if (inspectorSelectedNode == null && !inspectorAllNodes.isEmpty()) {
                inspectorSelectedNode = inspectorAllNodes.get(0);
            }

            if (inspectorTreeAdapter != null) inspectorTreeAdapter.notifyDataSetChanged();
            selectInspectorNodeOnMain(inspectorSelectedNode, true);
            updateInspectorHeaderOnMain();
        } finally {
            root.recycle();
        }
    }

    private InspectorNode captureInspectorNode(
            AccessibilityNodeInfo node,
            InspectorNode parent,
            int depth,
            int indexInParent,
            String path,
            int maxDepth
    ) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        InspectorNode out = new InspectorNode(
                parent,
                path,
                depth,
                indexInParent,
                node.getClassName() != null ? node.getClassName().toString() : "",
                node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "",
                node.getText() != null ? node.getText().toString() : "",
                node.getContentDescription() != null ? node.getContentDescription().toString() : "",
                node.getPackageName() != null ? node.getPackageName().toString() : "",
                bounds,
                node.isClickable(),
                node.isLongClickable(),
                node.isScrollable(),
                node.isEditable(),
                node.isEnabled(),
                node.isVisibleToUser()
        );

        if (depth >= maxDepth) return out;
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                InspectorNode childNode = captureInspectorNode(child, out, depth + 1, i, path + "." + i, maxDepth);
                out.children.add(childNode);
            } finally {
                child.recycle();
            }
        }
        return out;
    }

    private void flattenInspectorNodes(InspectorNode node, List<InspectorNode> out) {
        if (node == null) return;
        out.add(node);
        for (InspectorNode child : node.children) {
            flattenInspectorNodes(child, out);
        }
    }

    private void applyExpansionState(InspectorNode node, Map<String, Boolean> stateByPath) {
        if (node == null) return;
        if (stateByPath != null) {
            Boolean expanded = stateByPath.get(node.path);
            node.expanded = expanded == null || expanded;
        } else {
            node.expanded = true;
        }
        for (InspectorNode child : node.children) {
            applyExpansionState(child, stateByPath);
        }
    }

    private void rebuildVisibleInspectorNodes() {
        inspectorVisibleNodes.clear();
        appendVisibleInspectorNodes(inspectorRootNode, inspectorVisibleNodes);
    }

    private void appendVisibleInspectorNodes(InspectorNode node, List<InspectorNode> out) {
        if (node == null) return;
        out.add(node);
        if (!node.expanded) return;
        for (InspectorNode child : node.children) {
            appendVisibleInspectorNodes(child, out);
        }
    }

    private void ensureInspectorNodeVisibleOnTree(InspectorNode node) {
        if (node == null) return;
        boolean changed = false;
        InspectorNode current = node.parent;
        while (current != null) {
            if (!current.expanded) {
                current.expanded = true;
                changed = true;
            }
            current = current.parent;
        }
        if (changed) {
            rebuildVisibleInspectorNodes();
        }
    }

    private InspectorNode findInspectorNodeByPath(String path) {
        if (path == null || path.isEmpty()) return null;
        for (InspectorNode node : inspectorAllNodes) {
            if (path.equals(node.path)) return node;
        }
        return null;
    }

    private void selectInspectorNodeOnMain(InspectorNode node, boolean fromOverlay) {
        inspectorSelectedNode = node;
        updateInspectorParentButtonOnMain();
        ensureInspectorNodeVisibleOnTree(node);
        if (inspectorBoundsView != null) {
            inspectorBoundsView.setSelectedNode(node);
        }
        if (inspectorTreeAdapter != null) {
            inspectorTreeAdapter.notifyDataSetChanged();
        }
        if (inspectorTreeListView != null) {
            int idx = node != null ? inspectorVisibleNodes.indexOf(node) : -1;
            if (idx >= 0) {
                inspectorTreeListView.setItemChecked(idx, true);
                if (fromOverlay) {
                    inspectorTreeListView.smoothScrollToPosition(idx);
                }
            } else {
                inspectorTreeListView.clearChoices();
            }
        }
        updateInspectorBreadcrumbOnMain(node);
        updateInspectorInfoOnMain(node);
    }

    private void updateInspectorInfoOnMain(InspectorNode node) {
        if (inspectorInfoTextView == null) return;
        if (node == null) {
            inspectorInfoTextView.setText("No node selected.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("path=").append(node.path).append('\n');
        sb.append("class=").append(node.className).append('\n');
        sb.append("id=").append(node.id).append('\n');
        sb.append("text=").append(node.text).append('\n');
        sb.append("desc=").append(node.desc).append('\n');
        sb.append("pkg=").append(node.pkg).append('\n');
        sb.append("bounds=").append(node.bounds.flattenToString()).append('\n');
        sb.append("depth=").append(node.depth)
                .append(", index=").append(node.indexInParent)
                .append(", childCount=").append(node.children.size())
                .append(", siblingCount=").append(node.getSiblingCount())
                .append(", expanded=").append(node.expanded).append('\n');
        sb.append("flags: ");
        if (node.clickable) sb.append("clickable ");
        if (node.longClickable) sb.append("longClickable ");
        if (node.scrollable) sb.append("scrollable ");
        if (node.editable) sb.append("editable ");
        if (node.enabled) sb.append("enabled ");
        if (node.visibleToUser) sb.append("visibleToUser ");
        inspectorInfoTextView.setText(sb.toString().trim());
    }

    private void updateInspectorHeaderOnMain() {
        if (inspectorHeaderTextView == null) return;
        String pkg = inspectorRootNode != null ? inspectorRootNode.pkg : lastWindowPackage;
        int totalCount = inspectorAllNodes.size();
        int visibleCount = inspectorVisibleNodes.size();
        StringBuilder sb = new StringBuilder();
        sb.append("Orb Inspector");
        if (pkg != null && !pkg.isEmpty()) sb.append("  ").append(pkg);
        sb.append("  nodes=").append(visibleCount).append("/").append(totalCount);
        sb.append("  opacity=").append(Math.round(inspectorOpacity * 100f)).append("%");
        if (inspectorLastError != null && !inspectorLastError.isEmpty()) {
            sb.append("\nerr: ").append(inspectorLastError);
        }
        inspectorHeaderTextView.setText(sb.toString());
    }

    private JSONObject buildInspectorStateJsonOnMain() throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("running", inspectorRunning);
        out.put("count", inspectorAllNodes.size());
        out.put("visibleCount", inspectorVisibleNodes.size());
        out.put("maxDepth", inspectorMaxDepth);
        out.put("opacity", (double) inspectorOpacity);
        out.put("package", inspectorRootNode != null ? inspectorRootNode.pkg : lastWindowPackage);
        out.put("controllerVisible", inspectorControlView != null && inspectorControlView.getParent() != null);
        out.put("infoExpanded", inspectorInfoExpanded);
        if (inspectorSelectedNode != null) {
            JSONObject selected = new JSONObject();
            selected.put("path", inspectorSelectedNode.path);
            selected.put("class", inspectorSelectedNode.className);
            selected.put("id", inspectorSelectedNode.id);
            selected.put("text", inspectorSelectedNode.text);
            selected.put("desc", inspectorSelectedNode.desc);
            selected.put("bounds", inspectorSelectedNode.bounds.flattenToString());
            selected.put("depth", inspectorSelectedNode.depth);
            selected.put("childCount", inspectorSelectedNode.children.size());
            selected.put("siblingCount", inspectorSelectedNode.getSiblingCount());
            selected.put("expanded", inspectorSelectedNode.expanded);
            out.put("selected", selected);
        }
        if (inspectorLastError != null && !inspectorLastError.isEmpty()) {
            out.put("lastError", inspectorLastError);
        }
        return out;
    }

    private int dp(int dip) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dip,
                getResources().getDisplayMetrics()
        );
    }

    private String simplifyInspectorClassName(String className) {
        if (className == null) return "";
        String simplified = className;
        String[] prefixes = new String[]{"android.widget.", "android.view.", "androidx."};
        for (String prefix : prefixes) {
            if (simplified.startsWith(prefix)) {
                simplified = simplified.substring(prefix.length());
                break;
            }
        }
        return simplified;
    }

    private String shortenInspectorText(String text, int maxLen) {
        if (text == null) return "";
        String normalized = text.replace('\n', ' ').trim();
        if (normalized.length() <= maxLen) return normalized;
        return normalized.substring(0, Math.max(0, maxLen - 3)) + "...";
    }

    private int getInspectorLevelColor(int depth) {
        int idx = Math.abs(depth) % INSPECTOR_LEVEL_COLORS.length;
        return INSPECTOR_LEVEL_COLORS[idx];
    }

    private int withAlpha(int color, int alpha) {
        return (clampInt(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private static class InspectorNode {
        final InspectorNode parent;
        final String path;
        final int depth;
        final int indexInParent;
        final String className;
        final String id;
        final String text;
        final String desc;
        final String pkg;
        final Rect bounds;
        final boolean clickable;
        final boolean longClickable;
        final boolean scrollable;
        final boolean editable;
        final boolean enabled;
        final boolean visibleToUser;
        boolean expanded = true;
        final List<InspectorNode> children = new ArrayList<>();

        InspectorNode(
                InspectorNode parent,
                String path,
                int depth,
                int indexInParent,
                String className,
                String id,
                String text,
                String desc,
                String pkg,
                Rect bounds,
                boolean clickable,
                boolean longClickable,
                boolean scrollable,
                boolean editable,
                boolean enabled,
                boolean visibleToUser
        ) {
            this.parent = parent;
            this.path = path;
            this.depth = depth;
            this.indexInParent = indexInParent;
            this.className = className;
            this.id = id;
            this.text = text;
            this.desc = desc;
            this.pkg = pkg;
            this.bounds = new Rect(bounds);
            this.clickable = clickable;
            this.longClickable = longClickable;
            this.scrollable = scrollable;
            this.editable = editable;
            this.enabled = enabled;
            this.visibleToUser = visibleToUser;
        }

        int getSiblingCount() {
            if (parent == null) return 0;
            return Math.max(0, parent.children.size() - 1);
        }
    }

    private class InspectorTreeAdapter extends ArrayAdapter<InspectorNode> {
        InspectorTreeAdapter(Context context, List<InspectorNode> nodes) {
            super(context, android.R.layout.simple_list_item_1, nodes);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = (TextView) super.getView(position, convertView, parent);
            InspectorNode node = getItem(position);
            if (node == null) return tv;

            String marker = node == inspectorSelectedNode ? "> " : "  ";
            StringBuilder row = new StringBuilder();
            row.append(marker);
            row.append(node.depth).append(" ");
            for (int i = 0; i < Math.min(node.depth, 12); i++) row.append("  ");
            if (!node.children.isEmpty()) {
                row.append(node.expanded ? "[-] " : "[+] ");
            } else {
                row.append("    ");
            }
            row.append(simplifyInspectorClassName(node.className));

            String primary = !node.text.isEmpty() ? node.text : (!node.desc.isEmpty() ? node.desc : node.id);
            if (!primary.isEmpty()) {
                row.append("  ").append(shortenInspectorText(primary, 16));
            }
            row.append(" {ch=").append(node.children.size())
                    .append(",sb=").append(node.getSiblingCount()).append("}");

            if (node.clickable) row.append(" [C]");
            if (node.scrollable) row.append(" [S]");
            if (node.editable) row.append(" [E]");

            int levelColor = getInspectorLevelColor(node.depth);
            tv.setText(row.toString());
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTextColor(levelColor);
            tv.setPadding(dp(6), dp(6), dp(6), dp(6));
            tv.setBackgroundColor(node == inspectorSelectedNode ? withAlpha(levelColor, 0x33) : Color.TRANSPARENT);
            return tv;
        }
    }

    private class InspectorBoundsView extends View {
        private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectedOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectedInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectedFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectedCornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<InspectorNode> nodes = Collections.emptyList();
        private InspectorNode selectedNode;
        private float overlayOpacity = 0.88f;
        private float pulsePhase = 0f;
        private boolean pulseRunning = false;
        private final Runnable pulseRunnable = new Runnable() {
            @Override
            public void run() {
                if (!pulseRunning) return;
                pulsePhase += 0.18f;
                if (pulsePhase >= (float) (Math.PI * 2)) {
                    pulsePhase -= (float) (Math.PI * 2);
                }
                if (selectedNode != null) {
                    invalidate();
                }
                postOnAnimation(this);
            }
        };

        InspectorBoundsView(Context context) {
            super(context);
            setWillNotDraw(false);
            nodePaint.setStyle(Paint.Style.STROKE);
            nodePaint.setStrokeWidth(2f);

            selectedOuterPaint.setStyle(Paint.Style.STROKE);
            selectedOuterPaint.setStrokeWidth(5f);

            selectedInnerPaint.setStyle(Paint.Style.STROKE);
            selectedInnerPaint.setStrokeWidth(3f);

            selectedFillPaint.setStyle(Paint.Style.FILL);

            selectedCornerPaint.setStyle(Paint.Style.STROKE);
            selectedCornerPaint.setStrokeWidth(6f);
            selectedCornerPaint.setStrokeCap(Paint.Cap.ROUND);
            setOverlayOpacity(inspectorOpacity);
        }

        void setNodes(List<InspectorNode> newNodes) {
            nodes = newNodes != null ? newNodes : Collections.emptyList();
            invalidate();
        }

        void setSelectedNode(InspectorNode node) {
            selectedNode = node;
            if (selectedNode == null) {
                pulsePhase = 0f;
            }
            invalidate();
        }

        void setOverlayOpacity(float opacity) {
            overlayOpacity = clampFloat(opacity, 0.20f, 1.0f);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            for (InspectorNode node : nodes) {
                if (node.bounds.width() <= 0 || node.bounds.height() <= 0) continue;
                int color = getInspectorLevelColor(node.depth);
                nodePaint.setColor(withAlpha(color, Math.round(0xC0 * overlayOpacity)));
                canvas.drawRect(node.bounds, nodePaint);
            }
            if (selectedNode != null && selectedNode.bounds.width() > 0 && selectedNode.bounds.height() > 0) {
                int selectedColor = getInspectorLevelColor(selectedNode.depth);
                Rect selectedBounds = selectedNode.bounds;
                float pulse = 0.5f + 0.5f * (float) Math.sin(pulsePhase);
                int fillAlpha = Math.round((0x2A + (0x2A * pulse)) * overlayOpacity);
                int outerAlpha = Math.round((0xA0 + (0x5F * pulse)) * overlayOpacity);
                int innerAlpha = Math.round((0xA0 + (0x5F * pulse)) * overlayOpacity);
                int cornerAlpha = Math.round((0x90 + (0x6F * pulse)) * overlayOpacity);
                selectedOuterPaint.setStrokeWidth(4f + (2f * pulse));
                selectedInnerPaint.setStrokeWidth(2f + (2f * pulse));
                selectedCornerPaint.setStrokeWidth(5f + (2f * pulse));

                selectedFillPaint.setColor(withAlpha(selectedColor, fillAlpha));
                canvas.drawRect(selectedBounds, selectedFillPaint);

                selectedOuterPaint.setColor(withAlpha(0xFFFFFFFF, outerAlpha));
                canvas.drawRect(selectedBounds, selectedOuterPaint);

                Rect inner = new Rect(selectedBounds);
                inner.inset(dp(2), dp(2));
                if (inner.width() > 0 && inner.height() > 0) {
                    selectedInnerPaint.setColor(withAlpha(selectedColor, innerAlpha));
                    canvas.drawRect(inner, selectedInnerPaint);
                }

                drawSelectionCorners(canvas, selectedBounds, cornerAlpha, pulse);
            }
        }

        private void drawSelectionCorners(Canvas canvas, Rect bounds, int alpha, float pulse) {
            int base = clampInt(Math.min(bounds.width(), bounds.height()) / 4, dp(10), dp(24));
            int corner = clampInt(base + Math.round(dp(4) * pulse), dp(10), dp(32));
            selectedCornerPaint.setColor(withAlpha(0xFFFFFFFF, alpha));

            // top-left
            canvas.drawLine(bounds.left, bounds.top, bounds.left + corner, bounds.top, selectedCornerPaint);
            canvas.drawLine(bounds.left, bounds.top, bounds.left, bounds.top + corner, selectedCornerPaint);
            // top-right
            canvas.drawLine(bounds.right, bounds.top, bounds.right - corner, bounds.top, selectedCornerPaint);
            canvas.drawLine(bounds.right, bounds.top, bounds.right, bounds.top + corner, selectedCornerPaint);
            // bottom-left
            canvas.drawLine(bounds.left, bounds.bottom, bounds.left + corner, bounds.bottom, selectedCornerPaint);
            canvas.drawLine(bounds.left, bounds.bottom, bounds.left, bounds.bottom - corner, selectedCornerPaint);
            // bottom-right
            canvas.drawLine(bounds.right, bounds.bottom, bounds.right - corner, bounds.bottom, selectedCornerPaint);
            canvas.drawLine(bounds.right, bounds.bottom, bounds.right, bounds.bottom - corner, selectedCornerPaint);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!pulseRunning) {
                pulseRunning = true;
                postOnAnimation(pulseRunnable);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            pulseRunning = false;
            removeCallbacks(pulseRunnable);
            super.onDetachedFromWindow();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN
                    && action != MotionEvent.ACTION_MOVE
                    && action != MotionEvent.ACTION_UP) {
                return false;
            }
            InspectorNode hit = findSmallestNodeAt((int) event.getRawX(), (int) event.getRawY());
            if (hit == null) {
                return true;
            }
            selectedNode = hit;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
                selectInspectorNodeOnMain(hit, true);
            } else {
                invalidate();
            }
            return true;
        }

        private InspectorNode findSmallestNodeAt(int x, int y) {
            InspectorNode best = null;
            long bestArea = Long.MAX_VALUE;
            for (InspectorNode node : nodes) {
                if (!node.bounds.contains(x, y)) continue;
                long area = Math.max(1, (long) node.bounds.width() * (long) node.bounds.height());
                if (area <= bestArea) {
                    bestArea = area;
                    best = node;
                }
            }
            return best;
        }
    }

    // ===== Screen Elements (v2.1 enhanced) =====

    /**
     * GET /summary — high-level RPA-oriented screen snapshot.
     * Query params:
     *   package=xxx  — optional package filter (for split-screen scenarios)
     *   top=6        — max number of top_clickables (1..20)
     *   inputs=6     — max number of input_fields (1..20)
     */
    private String getScreenSummary(String query) throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorJson("No active window", "NOT_FOUND");

        try {
            String filterPkg = getQueryParam(query, "package");
            int topLimit = clampInt(getQueryInt(query, "top", 6), 1, 20);
            int inputLimit = clampInt(getQueryInt(query, "inputs", 6), 1, 20);

            ArrayList<ScreenSummaryNode> nodes = new ArrayList<>();
            collectScreenSummaryNodes(root, nodes, filterPkg, false, 0);

            int interactiveCount = 0;
            int textCount = 0;
            int scrollContainers = 0;
            String packageName = "";
            for (ScreenSummaryNode node : nodes) {
                if (packageName.isEmpty() && node.pkg != null && !node.pkg.isEmpty()) {
                    packageName = node.pkg;
                }
                if (node.visible && node.enabled && (node.clickable || node.longClickable || node.editable)) {
                    interactiveCount++;
                }
                if (node.visible && ((!node.text.isEmpty()) || (!node.desc.isEmpty()))) {
                    textCount++;
                }
                if (node.visible && node.scrollable) {
                    scrollContainers++;
                }
            }
            if (packageName.isEmpty()) {
                packageName = lastWindowPackage != null ? lastWindowPackage : "";
            }

            String activity = (lastWindowClass != null && !lastWindowClass.isEmpty())
                    ? lastWindowClass
                    : (!nodes.isEmpty() ? nodes.get(0).className : "");

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("package", packageName);
            result.put("activity", activity);
            result.put("title", detectScreenTitle(nodes));
            result.put("interactive_count", interactiveCount);
            result.put("text_count", textCount);
            result.put("scroll_containers", scrollContainers);
            result.put("top_clickables", buildSummaryTopClickables(nodes, topLimit));
            result.put("input_fields", buildSummaryInputFields(nodes, inputLimit));
            return result.toString();
        } finally {
            root.recycle();
        }
    }

    private void collectScreenSummaryNodes(
            AccessibilityNodeInfo node,
            List<ScreenSummaryNode> out,
            String filterPkg,
            boolean insideScrollable,
            int depth
    ) {
        if (node == null) return;

        String pkg = node.getPackageName() != null ? node.getPackageName().toString() : "";
        boolean nowInsideScrollable = insideScrollable || node.isScrollable();
        boolean packageMatch = filterPkg == null || filterPkg.isEmpty() || filterPkg.equals(pkg);

        if (packageMatch) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            CharSequence hintCs = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                hintCs = node.getHintText();
            }
            out.add(new ScreenSummaryNode(
                    pkg,
                    node.getClassName() != null ? node.getClassName().toString() : "",
                    node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "",
                    node.getText() != null ? node.getText().toString() : "",
                    node.getContentDescription() != null ? node.getContentDescription().toString() : "",
                    hintCs != null ? hintCs.toString() : "",
                    node.isClickable(),
                    node.isLongClickable(),
                    node.isEditable(),
                    node.isScrollable(),
                    node.isEnabled(),
                    node.isVisibleToUser(),
                    nowInsideScrollable,
                    node.getChildCount(),
                    depth,
                    bounds
            ));
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectScreenSummaryNodes(child, out, filterPkg, nowInsideScrollable, depth + 1);
            } finally {
                child.recycle();
            }
        }
    }

    private String detectScreenTitle(List<ScreenSummaryNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return "";
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;

        String bestTitle = "";
        int bestScore = Integer.MIN_VALUE;
        for (ScreenSummaryNode node : nodes) {
            if (!node.visible) continue;
            String text = node.text != null ? node.text.trim() : "";
            if (text.isEmpty() || text.length() > 40) continue;

            int score = 0;
            int cy = node.bounds.centerY();
            if (cy <= screenHeight * 0.20f) score += 48;
            else if (cy <= screenHeight * 0.35f) score += 34;
            else if (cy <= screenHeight * 0.50f) score += 10;

            if (containsIgnoreCase(node.className, "TextView")) score += 10;
            if (!node.clickable && !node.editable) score += 8;
            if (node.depth <= 6) score += 6;
            if (node.bounds.width() >= screenWidth * 0.25f) score += 10;

            int len = text.length();
            if (len >= 2 && len <= 14) score += 20;
            else if (len <= 24) score += 10;
            else score -= 8;

            if (score > bestScore) {
                bestScore = score;
                bestTitle = text;
            }
        }

        if (!bestTitle.isEmpty()) {
            return shortenInspectorText(bestTitle, 36);
        }

        for (ScreenSummaryNode node : nodes) {
            String text = node.text != null ? node.text.trim() : "";
            if (!text.isEmpty()) return shortenInspectorText(text, 36);
        }
        return "";
    }

    private JSONArray buildSummaryTopClickables(List<ScreenSummaryNode> nodes, int limit) throws Exception {
        ArrayList<ScreenSummaryNode> candidates = new ArrayList<>();
        for (ScreenSummaryNode node : nodes) {
            if (!node.visible || !node.enabled) continue;
            if (!(node.clickable || node.longClickable)) continue;
            String label = getSummaryNodeLabel(node);
            if (label.isEmpty() && (node.id == null || node.id.isEmpty())) continue;
            candidates.add(node);
        }

        final int screenHeight = getResources().getDisplayMetrics().heightPixels;
        Collections.sort(candidates, (a, b) -> Integer.compare(
                scoreSummaryClickableNode(b, screenHeight),
                scoreSummaryClickableNode(a, screenHeight)
        ));

        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (ScreenSummaryNode node : candidates) {
            if (out.length() >= limit) break;
            String dedupeKey = buildSummaryDedupeKey(node);
            if (seen.contains(dedupeKey)) continue;
            seen.add(dedupeKey);

            JSONObject item = new JSONObject();
            item.put("text", getSummaryNodeLabel(node));
            item.put("id", node.id != null ? node.id : "");
            item.put("bounds", rectToArray(node.bounds));
            item.put("centerX", node.bounds.centerX());
            item.put("centerY", node.bounds.centerY());
            out.put(item);
        }
        return out;
    }

    private JSONArray buildSummaryInputFields(List<ScreenSummaryNode> nodes, int limit) throws Exception {
        ArrayList<ScreenSummaryNode> candidates = new ArrayList<>();
        for (ScreenSummaryNode node : nodes) {
            if (!node.visible || !node.enabled) continue;
            boolean isInput = node.editable
                    || containsIgnoreCase(node.className, "EditText")
                    || containsIgnoreCase(node.className, "TextInput");
            if (!isInput) continue;
            candidates.add(node);
        }

        Collections.sort(candidates, (a, b) -> Integer.compare(
                scoreSummaryInputNode(b),
                scoreSummaryInputNode(a)
        ));

        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (ScreenSummaryNode node : candidates) {
            if (out.length() >= limit) break;
            String dedupeKey = buildSummaryDedupeKey(node);
            if (seen.contains(dedupeKey)) continue;
            seen.add(dedupeKey);

            JSONObject item = new JSONObject();
            item.put("hint", getSummaryInputHint(node));
            item.put("id", node.id != null ? node.id : "");
            item.put("bounds", rectToArray(node.bounds));
            item.put("centerX", node.bounds.centerX());
            item.put("centerY", node.bounds.centerY());
            out.put(item);
        }
        return out;
    }

    private int scoreSummaryClickableNode(ScreenSummaryNode node, int screenHeight) {
        if (node == null) return Integer.MIN_VALUE;
        int score = 0;
        if (node.visible) score += 40;
        if (node.enabled) score += 25;
        if (node.clickable) score += 20;
        if (node.longClickable) score += 6;
        if (!node.text.isEmpty()) score += 40;
        else if (!node.desc.isEmpty()) score += 28;
        else if (!node.id.isEmpty()) score += 15;
        if (node.childCount == 0) score += 15;
        if (node.insideScrollable) score += 4;
        if (node.bounds.centerY() > screenHeight * 0.60f) score += 8;
        long area = Math.max(1L, (long) node.bounds.width() * (long) node.bounds.height());
        score -= (int) Math.min(30L, area / 60000L);
        return score;
    }

    private int scoreSummaryInputNode(ScreenSummaryNode node) {
        if (node == null) return Integer.MIN_VALUE;
        int score = 0;
        if (node.visible) score += 30;
        if (node.enabled) score += 20;
        if (node.editable) score += 25;
        if (!node.hint.isEmpty()) score += 30;
        else if (!node.desc.isEmpty()) score += 15;
        else if (!node.text.isEmpty()) score += 10;
        if (!node.id.isEmpty()) score += 8;
        if (node.childCount == 0) score += 6;
        return score;
    }

    private String getSummaryNodeLabel(ScreenSummaryNode node) {
        if (node == null) return "";
        if (node.text != null && !node.text.trim().isEmpty()) {
            return shortenInspectorText(node.text.trim(), 24);
        }
        if (node.desc != null && !node.desc.trim().isEmpty()) {
            return shortenInspectorText(node.desc.trim(), 24);
        }
        return getSummaryShortId(node.id);
    }

    private String getSummaryInputHint(ScreenSummaryNode node) {
        if (node == null) return "";
        if (node.hint != null && !node.hint.trim().isEmpty()) {
            return shortenInspectorText(node.hint.trim(), 24);
        }
        if (node.desc != null && !node.desc.trim().isEmpty()) {
            return shortenInspectorText(node.desc.trim(), 24);
        }
        if (node.text != null && !node.text.trim().isEmpty()) {
            return shortenInspectorText(node.text.trim(), 24);
        }
        return "";
    }

    private String getSummaryShortId(String id) {
        if (id == null || id.isEmpty()) return "";
        int slash = id.lastIndexOf('/');
        String shortId = (slash >= 0 && slash + 1 < id.length()) ? id.substring(slash + 1) : id;
        return shortenInspectorText(shortId, 24);
    }

    private String buildSummaryDedupeKey(ScreenSummaryNode node) {
        if (node == null) return "";
        return (node.id != null ? node.id : "")
                + "|"
                + (node.text != null ? node.text : "")
                + "|"
                + node.bounds.flattenToString();
    }

    private JSONArray rectToArray(Rect bounds) throws Exception {
        JSONArray arr = new JSONArray();
        if (bounds == null) return arr;
        arr.put(bounds.left);
        arr.put(bounds.top);
        arr.put(bounds.right);
        arr.put(bounds.bottom);
        return arr;
    }

    private static class ScreenSummaryNode {
        final String pkg;
        final String className;
        final String id;
        final String text;
        final String desc;
        final String hint;
        final boolean clickable;
        final boolean longClickable;
        final boolean editable;
        final boolean scrollable;
        final boolean enabled;
        final boolean visible;
        final boolean insideScrollable;
        final int childCount;
        final int depth;
        final Rect bounds;

        ScreenSummaryNode(
                String pkg,
                String className,
                String id,
                String text,
                String desc,
                String hint,
                boolean clickable,
                boolean longClickable,
                boolean editable,
                boolean scrollable,
                boolean enabled,
                boolean visible,
                boolean insideScrollable,
                int childCount,
                int depth,
                Rect bounds
        ) {
            this.pkg = pkg != null ? pkg : "";
            this.className = className != null ? className : "";
            this.id = id != null ? id : "";
            this.text = text != null ? text : "";
            this.desc = desc != null ? desc : "";
            this.hint = hint != null ? hint : "";
            this.clickable = clickable;
            this.longClickable = longClickable;
            this.editable = editable;
            this.scrollable = scrollable;
            this.enabled = enabled;
            this.visible = visible;
            this.insideScrollable = insideScrollable;
            this.childCount = childCount;
            this.depth = depth;
            this.bounds = bounds != null ? new Rect(bounds) : new Rect();
        }
    }

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
     * GET/POST /enrich — merge accessibility nodes with OCR text by coordinates.
     * Query/body params:
     *   package=xxx, maxDepth=25, clickableOnly/textOnly/enabledOnly/visibleOnly/contains
     *   engine=auto|mlkit|paddle
     *   includeOcrLines=true   — include per-node raw OCR matches
     *   includeUnmatched=true  — include OCR lines that could not be mapped
     */
    private String handleEnrich(String query, JSONObject body) throws Exception {
        long startedAt = System.currentTimeMillis();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorJson("No active window", "NOT_FOUND");

        Bitmap source = null;
        try {
            String filterPkg = getBodyString(body, "package", getQueryParam(query, "package"));
            int maxDepth = clampInt(getBodyInt(body, "maxDepth", getQueryInt(query, "maxDepth", 25)), 0, 60);
            boolean clickableOnly = getBodyBoolean(body, "clickableOnly",
                    getQueryBoolean(query, "clickableOnly", false) || getQueryBoolean(query, "clickable", false));
            boolean textOnly = getBodyBoolean(body, "textOnly", getQueryBoolean(query, "textOnly", false));
            boolean enabledOnly = getBodyBoolean(body, "enabledOnly", getQueryBoolean(query, "enabledOnly", false));
            boolean visibleOnly = getBodyBoolean(body, "visibleOnly", getQueryBoolean(query, "visibleOnly", false));
            String contains = getBodyString(body, "contains", getQueryParam(query, "contains"));
            boolean includeOcrLines = getBodyBoolean(body, "includeOcrLines", getQueryBoolean(query, "includeOcrLines", true));
            boolean includeUnmatched = getBodyBoolean(body, "includeUnmatched", getQueryBoolean(query, "includeUnmatched", true));

            String rawEngine = getBodyString(body, "engine", getQueryParam(query, "engine"));
            String requestedEngine = normalizeOcrEngine(rawEngine != null && !rawEngine.isEmpty() ? rawEngine : OCR_ENGINE_AUTO);

            JSONArray nodes = new JSONArray();
            collectInspectNodes(root, nodes,
                    filterPkg, maxDepth,
                    clickableOnly, textOnly, enabledOnly, visibleOnly, contains,
                    0, -1, "0");

            source = captureScreenshotBitmap();
            if (source == null) {
                return errorJson("OCR input image unavailable", "ENRICH_FAILED");
            }

            OcrRunResult ocrResult = runOcrWithEngine(source, requestedEngine);
            List<OcrLineData> lines = ocrResult.lines != null ? ocrResult.lines : new ArrayList<>();

            ArrayList<EnrichNodeRef> nodeRefs = new ArrayList<>();
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                node.put("ocr_text", "");
                node.put("ocr_match_count", 0);
                if (includeOcrLines) {
                    node.put("ocr_lines", new JSONArray());
                }

                int left = node.optInt("left", Integer.MIN_VALUE);
                int top = node.optInt("top", Integer.MIN_VALUE);
                int right = node.optInt("right", Integer.MIN_VALUE);
                int bottom = node.optInt("bottom", Integer.MIN_VALUE);
                if (left == Integer.MIN_VALUE || top == Integer.MIN_VALUE
                        || right == Integer.MIN_VALUE || bottom == Integer.MIN_VALUE
                        || right <= left || bottom <= top) {
                    continue;
                }
                nodeRefs.add(new EnrichNodeRef(
                        i,
                        new Rect(left, top, right, bottom),
                        node.optInt("depth", 0)
                ));
            }

            JSONArray unmatched = new JSONArray();
            int matchedOcrCount = 0;
            for (int i = 0; i < lines.size(); i++) {
                OcrLineData line = lines.get(i);
                if (line == null || line.bounds == null) continue;

                JSONObject lineJson = lineToJson(line, 0, 0);
                lineJson.put("index", i);

                Rect lineRect = new Rect(
                        lineJson.optInt("left", line.bounds.left),
                        lineJson.optInt("top", line.bounds.top),
                        lineJson.optInt("right", line.bounds.right),
                        lineJson.optInt("bottom", line.bounds.bottom)
                );
                int centerX = lineJson.optInt("centerX", lineRect.centerX());
                int centerY = lineJson.optInt("centerY", lineRect.centerY());

                int nodeIndex = findBestEnrichNodeIndex(nodeRefs, lineRect, centerX, centerY);
                if (nodeIndex >= 0) {
                    JSONObject node = nodes.getJSONObject(nodeIndex);
                    int matchedCount = node.optInt("ocr_match_count", 0) + 1;
                    node.put("ocr_match_count", matchedCount);
                    node.put("ocr_text", appendOcrText(node.optString("ocr_text", ""), lineJson.optString("text", "")));

                    lineJson.put("nodePath", node.optString("path", ""));
                    lineJson.put("nodeDepth", node.optInt("depth", 0));

                    if (includeOcrLines) {
                        JSONArray nodeLines = node.optJSONArray("ocr_lines");
                        if (nodeLines == null) {
                            nodeLines = new JSONArray();
                            node.put("ocr_lines", nodeLines);
                        }
                        nodeLines.put(lineJson);
                    }
                    matchedOcrCount++;
                } else if (includeUnmatched) {
                    unmatched.put(lineJson);
                }
            }

            int enrichedCount = 0;
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                if (node.optInt("ocr_match_count", 0) > 0) {
                    enrichedCount++;
                }
                String text = node.optString("text", "");
                String effectiveText = (text != null && !text.isEmpty())
                        ? text
                        : node.optString("ocr_text", "");
                node.put("text_effective", effectiveText);
            }

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("engine", ocrResult.engine);
            result.put("requestedEngine", requestedEngine);
            result.put("imageWidth", source.getWidth());
            result.put("imageHeight", source.getHeight());
            result.put("nodeCount", nodes.length());
            result.put("ocrLineCount", lines.size());
            result.put("matchedOcrCount", matchedOcrCount);
            result.put("enrichedCount", enrichedCount);
            if (includeUnmatched) {
                result.put("unmatchedOcrCount", unmatched.length());
                result.put("unmatched_ocr_lines", unmatched);
            }
            result.put("nodes", nodes);
            result.put("elapsedMs", System.currentTimeMillis() - startedAt);
            return result.toString();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Enrich failed";
            if (msg.contains("API 30+")) return errorJson(msg, "NOT_SUPPORTED");
            if (msg.contains("timed out")) return errorJson(msg, "TIMEOUT");
            return errorJson(msg, "ENRICH_FAILED");
        } finally {
            root.recycle();
            if (source != null && !source.isRecycled()) source.recycle();
        }
    }

    private int findBestEnrichNodeIndex(List<EnrichNodeRef> nodeRefs, Rect ocrBounds, int centerX, int centerY) {
        if (nodeRefs == null || nodeRefs.isEmpty() || ocrBounds == null) return -1;

        int bestIndex = -1;
        long bestArea = Long.MAX_VALUE;
        int bestDepth = Integer.MIN_VALUE;
        for (EnrichNodeRef ref : nodeRefs) {
            if (ref == null || ref.bounds == null || !ref.bounds.contains(centerX, centerY)) continue;
            if (ref.area < bestArea || (ref.area == bestArea && ref.depth > bestDepth)) {
                bestIndex = ref.nodeIndex;
                bestArea = ref.area;
                bestDepth = ref.depth;
            }
        }
        if (bestIndex >= 0) return bestIndex;

        long lineArea = Math.max(1L, (long) ocrBounds.width() * (long) ocrBounds.height());
        double bestScore = 0d;
        bestArea = Long.MAX_VALUE;
        bestDepth = Integer.MIN_VALUE;
        for (EnrichNodeRef ref : nodeRefs) {
            if (ref == null || ref.bounds == null) continue;
            long overlap = intersectionArea(ref.bounds, ocrBounds);
            if (overlap <= 0L) continue;
            double score = overlap / (double) lineArea;
            if (score > bestScore + 1e-9
                    || (Math.abs(score - bestScore) <= 1e-9
                    && (ref.area < bestArea || (ref.area == bestArea && ref.depth > bestDepth)))) {
                bestIndex = ref.nodeIndex;
                bestScore = score;
                bestArea = ref.area;
                bestDepth = ref.depth;
            }
        }
        return bestIndex;
    }

    private long intersectionArea(Rect a, Rect b) {
        if (a == null || b == null) return 0L;
        int left = Math.max(a.left, b.left);
        int top = Math.max(a.top, b.top);
        int right = Math.min(a.right, b.right);
        int bottom = Math.min(a.bottom, b.bottom);
        if (right <= left || bottom <= top) return 0L;
        return (long) (right - left) * (long) (bottom - top);
    }

    private String appendOcrText(String existing, String token) {
        String normalizedExisting = existing != null ? existing.trim() : "";
        String normalizedToken = token != null ? token.trim() : "";
        if (normalizedToken.isEmpty()) return normalizedExisting;
        if (normalizedExisting.isEmpty()) return normalizedToken;
        if (normalizedExisting.endsWith(normalizedToken)) return normalizedExisting;
        return normalizedExisting + " " + normalizedToken;
    }

    private String getBodyString(JSONObject body, String key, String defaultValue) {
        if (body == null || key == null || key.isEmpty() || !body.has(key)) return defaultValue;
        Object raw = body.opt(key);
        if (raw == null || raw == JSONObject.NULL) return "";
        return String.valueOf(raw);
    }

    private boolean getBodyBoolean(JSONObject body, String key, boolean defaultValue) {
        if (body == null || key == null || key.isEmpty() || !body.has(key)) return defaultValue;
        Object raw = body.opt(key);
        if (raw == null || raw == JSONObject.NULL) return defaultValue;
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) return ((Number) raw).intValue() != 0;
        String str = String.valueOf(raw).trim();
        if (str.isEmpty()) return defaultValue;
        return "1".equals(str)
                || "true".equalsIgnoreCase(str)
                || "yes".equalsIgnoreCase(str)
                || "y".equalsIgnoreCase(str);
    }

    private int getBodyInt(JSONObject body, String key, int defaultValue) {
        if (body == null || key == null || key.isEmpty() || !body.has(key)) return defaultValue;
        Object raw = body.opt(key);
        if (raw == null || raw == JSONObject.NULL) return defaultValue;
        if (raw instanceof Number) return ((Number) raw).intValue();
        String str = String.valueOf(raw).trim();
        if (str.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(str);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static class EnrichNodeRef {
        final int nodeIndex;
        final Rect bounds;
        final long area;
        final int depth;

        EnrichNodeRef(int nodeIndex, Rect bounds, int depth) {
            this.nodeIndex = nodeIndex;
            this.bounds = bounds != null ? new Rect(bounds) : new Rect();
            this.area = Math.max(1L, (long) this.bounds.width() * (long) this.bounds.height());
            this.depth = depth;
        }
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
     *   "fuzzy": true                — enable fuzzy fallback with scored candidates
     *   "candidates": 5              — max candidate hints when not found (0..20)
     *
     * Returns the matched element with centerX/centerY ready for /tap.
     */
    private String handleFind(JSONObject body) throws Exception {
        String text = body.optString("text", "");
        String desc = body.optString("desc", "");
        String id = body.optString("id", "");
        boolean onlyClickable = body.optBoolean("clickable", false);
        boolean fuzzy = body.optBoolean("fuzzy", false);
        int index = body.optInt("index", 0);
        int candidateLimit = clampInt(body.optInt("candidates", 5), 0, 20);

        if (text.isEmpty() && desc.isEmpty() && id.isEmpty()) {
            return errorJson("Provide 'text', 'desc', or 'id'", "INVALID_ARGS");
        }

        String queryType;
        String query;
        if (!text.isEmpty()) {
            queryType = "text";
            query = text;
        } else if (!id.isEmpty()) {
            queryType = "id";
            query = id;
        } else {
            queryType = "desc";
            query = desc;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return buildFindFailure(
                    queryType, query, fuzzy, onlyClickable, index,
                    FIND_REASON_NO_ACTIVE_WINDOW, new JSONArray(),
                    0, 0, false
            );
        }

        try {
            List<AccessibilityNodeInfo> exactCandidates = new ArrayList<>();
            if ("text".equals(queryType)) {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(query);
                if (found != null) exactCandidates.addAll(found);
            } else if ("id".equals(queryType)) {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(query);
                if (found != null) exactCandidates.addAll(found);
            } else {
                collectByDesc(root, query, exactCandidates, false);
            }

            int exactVisibleCount = 0;
            for (AccessibilityNodeInfo n : exactCandidates) {
                if (n != null && n.isVisibleToUser()) exactVisibleCount++;
            }

            List<AccessibilityNodeInfo> filtered = new ArrayList<>();
            if (onlyClickable) {
                Set<String> seen = new HashSet<>();
                for (AccessibilityNodeInfo n : exactCandidates) {
                    if (n == null) continue;
                    AccessibilityNodeInfo picked = null;
                    if (n.isClickable()) {
                        picked = n;
                    } else {
                        AccessibilityNodeInfo parent = findClickableParent(n);
                        if (parent != null) picked = parent;
                    }
                    if (picked == null) continue;
                    String key = buildFindNodeKey(picked);
                    if (seen.add(key)) filtered.add(picked);
                }
            } else {
                filtered.addAll(exactCandidates);
            }

            if (!filtered.isEmpty()) {
                if (index >= filtered.size()) {
                    ArrayList<ScreenSummaryNode> inspectNodes = new ArrayList<>();
                    collectFindDiagnosticNodes(root, inspectNodes);
                    JSONArray hints = buildFindCandidates(inspectNodes, queryType, query, candidateLimit);
                    return buildFindFailure(
                            queryType, query, fuzzy, onlyClickable, index,
                            FIND_REASON_INDEX_OUT_OF_RANGE, hints,
                            filtered.size(), exactVisibleCount, hasScrollableNode(inspectNodes)
                    );
                }

                AccessibilityNodeInfo target = filtered.get(index);
                Rect bounds = new Rect();
                target.getBoundsInScreen(bounds);

                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("found", true);
                result.put("reason", "exact_match");
                result.put("score", 1.0);
                result.put("text", target.getText() != null ? target.getText().toString() : "");
                result.put("desc", target.getContentDescription() != null ? target.getContentDescription().toString() : "");
                result.put("id", target.getViewIdResourceName() != null ? target.getViewIdResourceName() : "");
                result.put("bounds", bounds.flattenToString());
                result.put("boundsArray", rectToArray(bounds));
                result.put("centerX", bounds.centerX());
                result.put("centerY", bounds.centerY());
                result.put("clickable", target.isClickable());
                result.put("editable", target.isEditable());
                result.put("scrollable", target.isScrollable());
                result.put("matchCount", filtered.size());
                result.put("index", index);
                return result.toString();
            }

            ArrayList<ScreenSummaryNode> inspectNodes = new ArrayList<>();
            collectFindDiagnosticNodes(root, inspectNodes);
            boolean hasScrollable = hasScrollableNode(inspectNodes);

            ArrayList<FindRankedNode> ranked = rankFindNodes(inspectNodes, queryType, query);

            if (fuzzy) {
                ArrayList<FindRankedNode> fuzzyMatches = pickFuzzyMatches(ranked, onlyClickable);
                if (!fuzzyMatches.isEmpty()) {
                    if (index >= fuzzyMatches.size()) {
                        JSONArray hints = buildFindCandidates(inspectNodes, queryType, query, candidateLimit);
                        return buildFindFailure(
                                queryType, query, true, onlyClickable, index,
                                FIND_REASON_INDEX_OUT_OF_RANGE, hints,
                                fuzzyMatches.size(), exactVisibleCount, hasScrollable
                        );
                    }
                    FindRankedNode picked = fuzzyMatches.get(index);
                    ScreenSummaryNode target = picked.node;
                    JSONObject result = new JSONObject();
                    result.put("ok", true);
                    result.put("found", true);
                    result.put("reason", "fuzzy_match");
                    result.put("score", roundFindScore(picked.score));
                    result.put("text", target.text != null ? target.text : "");
                    result.put("desc", target.desc != null ? target.desc : "");
                    result.put("id", target.id != null ? target.id : "");
                    result.put("bounds", target.bounds.flattenToString());
                    result.put("boundsArray", rectToArray(target.bounds));
                    result.put("centerX", target.bounds.centerX());
                    result.put("centerY", target.bounds.centerY());
                    result.put("clickable", target.clickable || target.longClickable);
                    result.put("editable", target.editable);
                    result.put("scrollable", target.scrollable);
                    result.put("matchCount", fuzzyMatches.size());
                    result.put("index", index);
                    return result.toString();
                }
            }

            JSONArray hints = buildFindCandidates(inspectNodes, queryType, query, candidateLimit);
            String reason = resolveFindFailureReason(
                    queryType,
                    query,
                    exactCandidates.size(),
                    exactVisibleCount,
                    onlyClickable,
                    ranked,
                    hasScrollable,
                    root
            );
            return buildFindFailure(
                    queryType, query, fuzzy, onlyClickable, index,
                    reason, hints,
                    0, exactVisibleCount, hasScrollable
            );
        } finally {
            root.recycle();
        }
    }

    private static class FindRankedNode {
        final ScreenSummaryNode node;
        final double score;

        FindRankedNode(ScreenSummaryNode node, double score) {
            this.node = node;
            this.score = score;
        }
    }

    private String buildFindFailure(
            String queryType,
            String query,
            boolean fuzzy,
            boolean clickableOnly,
            int index,
            String reason,
            JSONArray candidates,
            int matchCount,
            int visibleExactCount,
            boolean hasScrollable
    ) throws Exception {
        JSONObject result = new JSONObject();
        result.put("ok", false);
        result.put("found", false);
        result.put("reason", reason);
        result.put("queryType", queryType);
        result.put("query", query);
        result.put("fuzzy", fuzzy);
        result.put("clickableOnly", clickableOnly);
        result.put("index", index);
        result.put("matchCount", matchCount);
        result.put("visibleExactMatchCount", visibleExactCount);
        result.put("hasScrollable", hasScrollable);
        result.put("candidates", candidates);
        result.put("candidateCount", candidates != null ? candidates.length() : 0);
        result.put("error", "No matching element found (" + reason + ")");
        result.put("code", "NOT_FOUND");
        return result.toString();
    }

    private String resolveFindFailureReason(
            String queryType,
            String query,
            int exactMatchCount,
            int exactVisibleCount,
            boolean clickableOnly,
            List<FindRankedNode> ranked,
            boolean hasScrollable,
            AccessibilityNodeInfo activeRoot
    ) {
        boolean hasStrongCandidate = false;
        boolean hasStrongClickableCandidate = false;
        boolean hasStrongVisibleCandidate = false;
        boolean hasStrongInScrollableCandidate = false;
        boolean hasAnyInScrollableCandidate = false;
        for (FindRankedNode item : ranked) {
            if (item == null || item.node == null) continue;
            if (item.score >= 0.35d && (item.node.insideScrollable || item.node.scrollable)) {
                hasAnyInScrollableCandidate = true;
            }
            if (item.score < 0.62d) continue;
            hasStrongCandidate = true;
            if (item.node.visible) hasStrongVisibleCandidate = true;
            if (item.node.insideScrollable || item.node.scrollable) hasStrongInScrollableCandidate = true;
            if (item.node.clickable || item.node.longClickable) {
                hasStrongClickableCandidate = true;
            }
        }
        if (clickableOnly && (exactMatchCount > 0 || hasStrongCandidate) && !hasStrongClickableCandidate) {
            return FIND_REASON_NOT_CLICKABLE;
        }
        if (exactMatchCount > 0 && exactVisibleCount == 0) {
            if (hasScrollable || hasStrongInScrollableCandidate) return FIND_REASON_NEED_SCROLL;
            return FIND_REASON_OFF_SCREEN;
        }
        if (query != null && !query.isEmpty() && hasQueryInOtherWindows(queryType, query, activeRoot)) {
            return FIND_REASON_ANOTHER_WINDOW;
        }
        if (hasStrongInScrollableCandidate || (hasScrollable && hasAnyInScrollableCandidate)) {
            return FIND_REASON_NEED_SCROLL;
        }
        if (hasStrongCandidate && !hasStrongVisibleCandidate) {
            return FIND_REASON_OFF_SCREEN;
        }
        return mismatchReasonForQueryType(queryType);
    }

    private String mismatchReasonForQueryType(String queryType) {
        if ("id".equals(queryType)) return FIND_REASON_ID_MISMATCH;
        if ("desc".equals(queryType)) return FIND_REASON_DESC_MISMATCH;
        return FIND_REASON_TEXT_MISMATCH;
    }

    private boolean hasQueryInOtherWindows(String queryType, String query, AccessibilityNodeInfo activeRoot) {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null || windows.isEmpty()) return false;

        int checked = 0;
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) continue;
            if (window.isActive()) continue;
            if (checked >= 6) break;

            AccessibilityNodeInfo root = null;
            try {
                root = window.getRoot();
                if (root == null) continue;
                if (activeRoot != null && System.identityHashCode(root) == System.identityHashCode(activeRoot)) {
                    continue;
                }
                checked++;
                if (hasQueryMatchInRoot(root, queryType, query)) {
                    return true;
                }
            } catch (Exception ignored) {
                // Ignore broken window roots and continue scanning.
            } finally {
                if (root != null) {
                    try {
                        root.recycle();
                    } catch (Exception ignored) {
                        // ignore recycle failures
                    }
                }
            }
        }
        return false;
    }

    private boolean hasQueryMatchInRoot(AccessibilityNodeInfo root, String queryType, String query) {
        if (root == null || query == null || query.isEmpty()) return false;
        if ("text".equals(queryType)) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(query);
            return found != null && !found.isEmpty();
        }
        if ("id".equals(queryType)) {
            try {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(query);
                return found != null && !found.isEmpty();
            } catch (Exception ignored) {
                return false;
            }
        }
        List<AccessibilityNodeInfo> byDesc = new ArrayList<>();
        collectByDesc(root, query, byDesc, false);
        return !byDesc.isEmpty();
    }

    private boolean hasScrollableNode(List<ScreenSummaryNode> nodes) {
        if (nodes == null) return false;
        for (ScreenSummaryNode node : nodes) {
            if (node != null && (node.scrollable || node.insideScrollable)) return true;
        }
        return false;
    }

    private void collectFindDiagnosticNodes(AccessibilityNodeInfo root, List<ScreenSummaryNode> out) {
        if (root == null || out == null) return;
        long deadlineMs = System.currentTimeMillis() + FIND_MAX_DIAGNOSTIC_MS;
        collectFindDiagnosticNodesRecursive(root, out, false, 0, deadlineMs);
    }

    private void collectFindDiagnosticNodesRecursive(
            AccessibilityNodeInfo node,
            List<ScreenSummaryNode> out,
            boolean insideScrollable,
            int depth,
            long deadlineMs
    ) {
        if (node == null || out == null) return;
        if (out.size() >= FIND_MAX_DIAGNOSTIC_NODES) return;
        if (depth > FIND_MAX_DIAGNOSTIC_DEPTH) return;
        if (System.currentTimeMillis() > deadlineMs) return;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        String id = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
        String text = node.getText() != null ? node.getText().toString() : "";
        String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
        CharSequence hintCs = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            hintCs = node.getHintText();
        }
        String hint = hintCs != null ? hintCs.toString() : "";
        boolean clickable = node.isClickable();
        boolean longClickable = node.isLongClickable();
        boolean editable = node.isEditable();
        boolean scrollable = node.isScrollable();
        boolean visible = node.isVisibleToUser();
        boolean enabled = node.isEnabled();
        boolean nowInsideScrollable = insideScrollable || scrollable;

        boolean shouldRecord = clickable
                || longClickable
                || editable
                || scrollable
                || (!text.isEmpty())
                || (!desc.isEmpty())
                || (!id.isEmpty())
                || (!hint.isEmpty());

        if (shouldRecord) {
            out.add(new ScreenSummaryNode(
                    node.getPackageName() != null ? node.getPackageName().toString() : "",
                    className,
                    id,
                    text,
                    desc,
                    hint,
                    clickable,
                    longClickable,
                    editable,
                    scrollable,
                    enabled,
                    visible,
                    nowInsideScrollable,
                    node.getChildCount(),
                    depth,
                    bounds
            ));
        }

        if (depth >= FIND_MAX_DIAGNOSTIC_DEPTH || out.size() >= FIND_MAX_DIAGNOSTIC_NODES) return;

        for (int i = 0; i < node.getChildCount(); i++) {
            if (out.size() >= FIND_MAX_DIAGNOSTIC_NODES) return;
            if (System.currentTimeMillis() > deadlineMs) return;
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectFindDiagnosticNodesRecursive(child, out, nowInsideScrollable, depth + 1, deadlineMs);
            } finally {
                child.recycle();
            }
        }
    }

    private String buildFindNodeKey(AccessibilityNodeInfo node) {
        if (node == null) return "";
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return (node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "")
                + "|"
                + bounds.flattenToString()
                + "|"
                + (node.getClassName() != null ? node.getClassName().toString() : "");
    }

    private JSONArray buildFindCandidates(List<ScreenSummaryNode> nodes, String queryType, String query, int limit) throws Exception {
        JSONArray out = new JSONArray();
        if (limit <= 0 || nodes == null || nodes.isEmpty()) return out;

        ArrayList<FindRankedNode> ranked = rankFindNodes(nodes, queryType, query);
        Set<String> seen = new HashSet<>();
        for (FindRankedNode item : ranked) {
            if (out.length() >= limit) break;
            if (item == null || item.node == null) continue;
            if (item.score <= 0.20d) continue;
            String key = buildSummaryDedupeKey(item.node);
            if (!seen.add(key)) continue;

            ScreenSummaryNode node = item.node;
            JSONObject c = new JSONObject();
            c.put("text", getSummaryNodeLabel(node));
            c.put("desc", node.desc != null ? shortenInspectorText(node.desc, 40) : "");
            c.put("id", node.id != null ? node.id : "");
            c.put("class", node.className != null ? node.className : "");
            c.put("score", roundFindScore(item.score));
            c.put("bounds", rectToArray(node.bounds));
            c.put("centerX", node.bounds.centerX());
            c.put("centerY", node.bounds.centerY());
            c.put("clickable", node.clickable || node.longClickable);
            c.put("editable", node.editable);
            c.put("visible", node.visible);
            c.put("insideScrollable", node.insideScrollable);
            out.put(c);
        }
        return out;
    }

    private ArrayList<FindRankedNode> pickFuzzyMatches(List<FindRankedNode> ranked, boolean clickableOnly) {
        ArrayList<FindRankedNode> out = new ArrayList<>();
        if (ranked == null || ranked.isEmpty()) return out;
        Set<String> seen = new HashSet<>();
        for (FindRankedNode item : ranked) {
            if (item == null || item.node == null) continue;
            if (item.score < 0.62d) continue;
            if (!item.node.visible || !item.node.enabled) continue;
            if (clickableOnly && !(item.node.clickable || item.node.longClickable)) continue;
            String key = buildSummaryDedupeKey(item.node);
            if (!seen.add(key)) continue;
            out.add(item);
        }
        return out;
    }

    private ArrayList<FindRankedNode> rankFindNodes(List<ScreenSummaryNode> nodes, String queryType, String query) {
        ArrayList<FindRankedNode> out = new ArrayList<>();
        if (nodes == null || query == null || query.isEmpty()) return out;
        for (ScreenSummaryNode node : nodes) {
            double score = scoreFindNode(node, queryType, query);
            if (score > 0.01d) {
                out.add(new FindRankedNode(node, score));
            }
        }
        Collections.sort(out, (a, b) -> {
            int scoreCmp = Double.compare(b.score, a.score);
            if (scoreCmp != 0) return scoreCmp;
            int visibleCmp = Boolean.compare(b.node.visible, a.node.visible);
            if (visibleCmp != 0) return visibleCmp;
            int clickCmp = Boolean.compare((b.node.clickable || b.node.longClickable), (a.node.clickable || a.node.longClickable));
            if (clickCmp != 0) return clickCmp;
            return Integer.compare(a.node.depth, b.node.depth);
        });
        return out;
    }

    private double scoreFindNode(ScreenSummaryNode node, String queryType, String query) {
        if (node == null || query == null || query.isEmpty()) return 0d;
        String text = node.text != null ? node.text : "";
        String desc = node.desc != null ? node.desc : "";
        String id = node.id != null ? node.id : "";
        String shortId = getSummaryShortId(id);
        String className = node.className != null ? node.className : "";

        double textScore = scoreFindField(query, text);
        double descScore = scoreFindField(query, desc);
        double idScore = scoreFindField(query, id);
        double shortIdScore = scoreFindField(query, shortId);
        double classScore = scoreFindField(query, className);

        double lexicalScore;
        if ("id".equals(queryType)) {
            lexicalScore = Math.max(idScore, Math.max(shortIdScore * 0.96d, Math.max(textScore * 0.72d, descScore * 0.60d)));
        } else if ("desc".equals(queryType)) {
            lexicalScore = Math.max(descScore, Math.max(textScore * 0.88d, Math.max(shortIdScore * 0.72d, classScore * 0.56d)));
        } else {
            lexicalScore = Math.max(textScore, Math.max(descScore * 0.86d, Math.max(shortIdScore * 0.74d, classScore * 0.54d)));
        }
        if (lexicalScore <= 0d) return 0d;

        double score = lexicalScore;
        if (node.visible) score += 0.04d;
        else score -= 0.08d;
        if (node.enabled) score += 0.02d;
        if (node.clickable || node.longClickable) score += 0.02d;
        if (node.bounds.width() <= 0 || node.bounds.height() <= 0) score -= 0.20d;
        if (node.insideScrollable) score += 0.01d;
        return clampDouble(score, 0d, 1d);
    }

    private double scoreFindField(String query, String fieldValue) {
        if (query == null || query.isEmpty() || fieldValue == null || fieldValue.isEmpty()) return 0d;
        if (fieldValue.equals(query)) return 1d;
        if (fieldValue.equalsIgnoreCase(query)) return 0.99d;

        String qNorm = normalizeFindToken(query);
        String fNorm = normalizeFindToken(fieldValue);
        if (qNorm.isEmpty() || fNorm.isEmpty()) return 0d;
        if (qNorm.equals(fNorm)) return 1d;
        if (fNorm.contains(qNorm) || qNorm.contains(fNorm)) {
            double ratio = (double) Math.min(qNorm.length(), fNorm.length()) / (double) Math.max(qNorm.length(), fNorm.length());
            return clampDouble(0.72d + 0.25d * ratio, 0d, 0.97d);
        }
        if (!shareFindChar(qNorm, fNorm)) return 0d;
        return normalizedSimilarityTokens(qNorm, fNorm);
    }

    private String normalizeFindToken(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String s = raw.toLowerCase(Locale.ROOT).trim();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private double normalizedSimilarity(String left, String right) {
        String a = normalizeFindToken(left);
        String b = normalizeFindToken(right);
        return normalizedSimilarityTokens(a, b);
    }

    private double normalizedSimilarityTokens(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0d;
        if (a.equals(b)) return 1d;
        if (a.contains(b) || b.contains(a)) {
            double ratio = (double) Math.min(a.length(), b.length()) / (double) Math.max(a.length(), b.length());
            return clampDouble(0.68d + ratio * 0.28d, 0d, 0.96d);
        }
        if (!shareFindChar(a, b)) return 0d;

        // Bound edit-distance cost on pathological long labels.
        if (a.length() > 40) a = a.substring(0, 40);
        if (b.length() > 40) b = b.substring(0, 40);

        int dist = levenshteinDistance(a, b);
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 0d;
        return clampDouble(1d - ((double) dist / (double) maxLen), 0d, 1d);
    }

    private boolean shareFindChar(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        String shortS = a.length() <= b.length() ? a : b;
        String longS = a.length() <= b.length() ? b : a;
        for (int i = 0; i < shortS.length(); i++) {
            if (longS.indexOf(shortS.charAt(i)) >= 0) return true;
        }
        return false;
    }

    private int levenshteinDistance(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;

        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;

        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost
                );
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double roundFindScore(double score) {
        return Math.round(clampDouble(score, 0d, 1d) * 100.0d) / 100.0d;
    }

    private void collectByDesc(AccessibilityNodeInfo node, String descQuery, List<AccessibilityNodeInfo> out, boolean ignoreCase) {
        if (node == null) return;
        CharSequence cd = node.getContentDescription();
        if (cd != null) {
            String current = cd.toString();
            boolean matched = ignoreCase ? containsIgnoreCase(current, descQuery) : current.contains(descQuery);
            if (matched) out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectByDesc(child, descQuery, out, ignoreCase);
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
