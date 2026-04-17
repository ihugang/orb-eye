package com.cb.monitor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.WrappedException;

import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Embeds Mozilla Rhino inside orb-eye so that callers can POST a JavaScript
 * snippet to {@code /exec} and have it run locally on the device with direct
 * access to the Accessibility Service — zero HTTP round-trips per action.
 *
 * <h3>JS API exposed as global functions:</h3>
 * <pre>
 * // Reading
 * getScreen()           → [{text, desc, centerX, centerY, clickable, ...}, ...]
 * getTree([query])      → nested UI tree JSON object
 * getInspect([query])   → {ok, count, nodes:[...]} flattened hierarchy for analysis
 * getInfo()             → {package, activity}
 * findText(text)        → {centerX, centerY, ...} or null
 * hasText(text)         → boolean
 * getText()             → all visible text as one string
 *
 * // Interaction
 * tap(x, y)             → boolean
 * shellTap(x, y)        → boolean
 * click(text)           → boolean
 * clickDesc(desc)       → boolean
 * clickId(id)           → boolean
 * openPackage(pkg)      → boolean   (launch app by package)
 * openActivity(pkg,cls) → boolean   (launch explicit activity component)
 * input(text)           → void
 * setText(text)         → void
 * paste()               → boolean
 * scroll(dir, count)    → void      (dir: "up"/"down"/"left"/"right")
 * swipe(x1,y1,x2,y2,ms)→ boolean
 * back()                → void
 * home()                → void
 *
 * // Sensing
 * screenshot()          → base64 string
 * ocr()                 → [{text, centerX, centerY}, ...]
 * ocrFind(text)         → {text, centerX, centerY} or null
 * getClipboard()        → string
 * setClipboard(text)    → void
 *
 * // Flow
 * sleep(ms)             → void
 * waitForChange(ms)     → boolean
 * waitForText(text, ms) → boolean
 * waitUntil(fn, ms, interval) → boolean
 * exit([result])        → void    (stop script immediately as success)
 * log(msg)              → void  (captured in result.logs)
 * </pre>
 */
public class OrbScriptEngine {
    static final String VERSION = "2.8";
    private static final String TAG = "OrbEye.JS";
    private final OrbAccessibilityService svc;
    // Track active script threads by execution id so /stopjs can target one script.
    private final ConcurrentHashMap<Long, Thread> workersByExecutionId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicBoolean> cancelFlagsByExecutionId = new ConcurrentHashMap<>();
    // Each worker keeps its own log buffer / stream sink to avoid cross-script leakage.
    private final ThreadLocal<List<String>> threadLogBuffer = new ThreadLocal<>();
    private final ThreadLocal<OutputStream> threadStreamSink = new ThreadLocal<>();
    private final ThreadLocal<Long> currentExecutionId = new ThreadLocal<>();
    private final AtomicLong nextExecutionId = new AtomicLong(1L);

    private static final InterruptibleContextFactory CONTEXT_FACTORY = new InterruptibleContextFactory();

    public OrbScriptEngine(OrbAccessibilityService svc) {
        this.svc = svc;
    }

    private static class ScriptInterruptedException extends RuntimeException {
        ScriptInterruptedException(String message) {
            super(message);
        }
    }

    private static class ScriptExitException extends RuntimeException {
        final String result;

        ScriptExitException(String result) {
            super("script exited by exit()");
            this.result = result;
        }
    }

    private static class InterruptibleContextFactory extends ContextFactory {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            // Rhino on Android: must use interpreted mode (no bytecode gen).
            cx.setOptimizationLevel(-1);
            // Check interrupt status regularly so force-stop can break tight JS loops.
            cx.setInstructionObserverThreshold(10_000);
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            if (Thread.currentThread().isInterrupted()) {
                throw new ScriptInterruptedException("script execution interrupted");
            }
        }
    }

    public int stopAllRunningScripts() {
        int stopped = 0;
        for (Long executionId : workersByExecutionId.keySet()) {
            AtomicBoolean cancelFlag = cancelFlagsByExecutionId.get(executionId);
            if (cancelFlag != null) {
                cancelFlag.set(true);
            }
            Thread worker = workersByExecutionId.get(executionId);
            if (worker == null) {
                continue;
            }
            if (worker.isAlive()) {
                worker.interrupt();
                stopped++;
            }
        }
        return stopped;
    }

    public boolean stopExecution(long executionId) {
        Thread worker = workersByExecutionId.get(executionId);
        AtomicBoolean cancelFlag = cancelFlagsByExecutionId.get(executionId);
        if (cancelFlag != null) {
            cancelFlag.set(true);
        }
        if (worker == null) {
            return false;
        }
        if (!worker.isAlive()) {
            workersByExecutionId.remove(executionId, worker);
            return false;
        }
        worker.interrupt();
        return true;
    }

    private long claimExecutionId(Thread worker, Long requestedExecutionId) {
        if (requestedExecutionId != null) {
            long executionId = requestedExecutionId;
            if (executionId <= 0) {
                throw new IllegalArgumentException("executionId must be positive");
            }
            Thread prev = workersByExecutionId.putIfAbsent(executionId, worker);
            if (prev != null) {
                throw new IllegalStateException("executionId already running: " + executionId);
            }
            cancelFlagsByExecutionId.put(executionId, new AtomicBoolean(false));
            return executionId;
        }

        while (true) {
            long executionId = nextExecutionId.getAndIncrement();
            if (executionId <= 0) {
                nextExecutionId.compareAndSet(executionId + 1, 1L);
                continue;
            }
            if (workersByExecutionId.putIfAbsent(executionId, worker) == null) {
                cancelFlagsByExecutionId.put(executionId, new AtomicBoolean(false));
                return executionId;
            }
        }
    }

    /**
     * Execute a JS script with a timeout. Returns JSON result.
     */
    public String execute(String script, int timeoutMs) {
        return execute(script, timeoutMs, null);
    }

    public String execute(String script, int timeoutMs, Long requestedExecutionId) {
        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        long start = System.currentTimeMillis();
        final Object[] resultHolder = {null};
        final Throwable[] errorHolder = {null};
        final ScriptExitException[] exitHolder = {null};
        final long[] executionIdHolder = {0L};

        Thread worker = new Thread(() -> {
            threadLogBuffer.set(logs);
            try {
                currentExecutionId.set(executionIdHolder[0]);
                resultHolder[0] = CONTEXT_FACTORY.call(cx -> {
                    Scriptable scope = cx.initStandardObjects();
                    injectAPI(cx, scope);
                    Object result = cx.evaluateString(scope, script, "exec", 1, null);
                    return Context.jsToJava(result, Object.class);
                });
            } catch (Throwable t) {
                ScriptExitException exit = findScriptExit(t);
                if (exit != null) {
                    exitHolder[0] = exit;
                } else {
                    errorHolder[0] = t;
                }
            } finally {
                currentExecutionId.remove();
                threadLogBuffer.remove();
                workersByExecutionId.remove(executionIdHolder[0], Thread.currentThread());
                cancelFlagsByExecutionId.remove(executionIdHolder[0]);
            }
        });
        try {
            executionIdHolder[0] = claimExecutionId(worker, requestedExecutionId);
        } catch (RuntimeException e) {
            return buildError(normalizeErrorMessage(e), logs, -1L, System.currentTimeMillis() - start);
        }
        worker.setDaemon(true);
        worker.start();

        try {
            worker.join(timeoutMs > 0 ? timeoutMs : 60000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (worker.isAlive()) {
            worker.interrupt();
            return buildError("script execution timed out after " + timeoutMs + "ms",
                    logs, executionIdHolder[0], System.currentTimeMillis() - start);
        }

        if (exitHolder[0] != null) {
            Object exitResult = exitHolder[0].result;
            if (exitResult == null || String.valueOf(exitResult).isEmpty()) {
                exitResult = "exit";
            }
            return buildSuccess(exitResult, logs, executionIdHolder[0], System.currentTimeMillis() - start);
        }

        if (errorHolder[0] != null) {
            return buildError(normalizeErrorMessage(errorHolder[0]),
                    logs, executionIdHolder[0], System.currentTimeMillis() - start);
        }

        return buildSuccess(resultHolder[0], logs, executionIdHolder[0], System.currentTimeMillis() - start);
    }

    /**
     * Execute a JS script in streaming mode. Each log() call emits an NDJSON line
     * to {@code out} immediately, so the caller sees output in real time.
     * The final line is the result JSON.
     *
     * Wire format (one JSON object per line):
     * <pre>
     * {"type":"log","msg":"..."}
     * {"type":"log","msg":"..."}
     * {"type":"result","ok":true,"result":"...","duration_ms":123}
     * </pre>
     */
    public void executeStreaming(String script, int timeoutMs, OutputStream out) {
        executeStreaming(script, timeoutMs, null, out);
    }

    public void executeStreaming(String script, int timeoutMs, Long requestedExecutionId, OutputStream out) {
        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        long start = System.currentTimeMillis();
        final Object[] resultHolder = {null};
        final Throwable[] errorHolder = {null};
        final ScriptExitException[] exitHolder = {null};
        final long[] executionIdHolder = {0L};

        Thread worker = new Thread(() -> {
            threadLogBuffer.set(logs);
            threadStreamSink.set(out);
            try {
                currentExecutionId.set(executionIdHolder[0]);
                resultHolder[0] = CONTEXT_FACTORY.call(cx -> {
                    Scriptable scope = cx.initStandardObjects();
                    injectAPI(cx, scope);
                    Object result = cx.evaluateString(scope, script, "exec", 1, null);
                    return Context.jsToJava(result, Object.class);
                });
            } catch (Throwable t) {
                ScriptExitException exit = findScriptExit(t);
                if (exit != null) {
                    exitHolder[0] = exit;
                } else {
                    errorHolder[0] = t;
                }
            } finally {
                currentExecutionId.remove();
                threadStreamSink.remove();
                threadLogBuffer.remove();
                workersByExecutionId.remove(executionIdHolder[0], Thread.currentThread());
                cancelFlagsByExecutionId.remove(executionIdHolder[0]);
            }
        });
        try {
            executionIdHolder[0] = claimExecutionId(worker, requestedExecutionId);
        } catch (RuntimeException e) {
            try {
                writeStreamLine(out, buildStreamResult(false,
                        normalizeErrorMessage(e), null, -1L, System.currentTimeMillis() - start));
            } catch (Exception ignored) {
            }
            return;
        }
        worker.setDaemon(true);
        worker.start();

        try {
            worker.join(timeoutMs > 0 ? timeoutMs : 60000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long duration = System.currentTimeMillis() - start;

        try {
            if (worker.isAlive()) {
                worker.interrupt();
                writeStreamLine(out, buildStreamResult(false,
                        "script execution timed out after " + timeoutMs + "ms", null, executionIdHolder[0], duration));
            } else if (exitHolder[0] != null) {
                String exitResult = exitHolder[0].result;
                if (exitResult == null || exitResult.isEmpty()) {
                    exitResult = "exit";
                }
                writeStreamLine(out, buildStreamResult(true, null,
                        exitResult, executionIdHolder[0], duration));
            } else if (errorHolder[0] != null) {
                writeStreamLine(out, buildStreamResult(false,
                        normalizeErrorMessage(errorHolder[0]), null, executionIdHolder[0], duration));
            } else {
                writeStreamLine(out, buildStreamResult(true, null,
                        resultHolder[0] != null ? resultHolder[0].toString() : null, executionIdHolder[0], duration));
            }
            out.flush();
        } catch (Exception e) {
            Log.e(TAG, "stream write final result error: " + e.getMessage());
        }
    }

    private String buildStreamResult(boolean ok, String error, String result, long executionId, long durationMs) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "result");
            json.put("ok", ok);
            if (error != null) json.put("error", error);
            if (result != null) json.put("result", result);
            if (executionId > 0) json.put("execution_id", executionId);
            json.put("duration_ms", durationMs);
            return json.toString();
        } catch (Exception e) {
            return "{\"type\":\"result\",\"ok\":false,\"error\":\"json build failed\"}";
        }
    }

    private void writeStreamLine(OutputStream out, String line) {
        try {
            out.write((line + "\n").getBytes("UTF-8"));
            out.flush();
        } catch (Exception e) {
            Log.e(TAG, "stream write error: " + e.getMessage());
        }
    }

    // ─── Result builders ────────────────────────────────────────────────────

    private String buildSuccess(Object result, List<String> logs, long executionId, long durationMs) {
        try {
            JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("result", result != null ? result.toString() : null);
            json.put("logs", new JSONArray(logs));
            if (executionId > 0) json.put("execution_id", executionId);
            json.put("duration_ms", durationMs);
            return json.toString();
        } catch (Exception e) {
            return "{\"ok\":true,\"duration_ms\":" + durationMs + "}";
        }
    }

    private String buildError(String message, List<String> logs, long executionId, long durationMs) {
        try {
            JSONObject json = new JSONObject();
            json.put("ok", false);
            json.put("error", message);
            json.put("logs", new JSONArray(logs));
            if (executionId > 0) json.put("execution_id", executionId);
            json.put("duration_ms", durationMs);
            return json.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + message + "\"}";
        }
    }

    private String normalizeErrorMessage(Throwable throwable) {
        if (throwable == null) return "unknown script error";
        if (throwable instanceof InterruptedException || throwable instanceof ScriptInterruptedException) {
            return "script execution interrupted";
        }
        String msg = throwable.getMessage();
        return (msg == null || msg.isEmpty()) ? throwable.getClass().getSimpleName() : msg;
    }

    private ScriptExitException findScriptExit(Throwable throwable) {
        Throwable cur = throwable;
        int guard = 0;
        while (cur != null && guard < 16) {
            if (cur instanceof ScriptExitException) {
                return (ScriptExitException) cur;
            }
            if (cur instanceof WrappedException) {
                Throwable wrapped = ((WrappedException) cur).getWrappedException();
                if (wrapped != null && wrapped != cur) {
                    cur = wrapped;
                    guard++;
                    continue;
                }
            }
            cur = cur.getCause();
            guard++;
        }
        return null;
    }

    private void throwIfStopRequested() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ScriptInterruptedException("script execution interrupted");
        }
        Long executionId = currentExecutionId.get();
        if (executionId == null) {
            return;
        }
        AtomicBoolean cancelFlag = cancelFlagsByExecutionId.get(executionId);
        if (cancelFlag != null && cancelFlag.get()) {
            throw new ScriptInterruptedException("script execution interrupted");
        }
    }

    private void throwIfInterrupted(Exception e) {
        if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            throw new ScriptInterruptedException("script execution interrupted");
        }
    }

    private String sanitizeUiText(CharSequence value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.isEmpty()) {
            return "";
        }
        String compact = text.trim();
        if (compact.startsWith("data:image/")) {
            return "[embedded-image-data]";
        }
        if (compact.contains("base64,") && compact.length() > 120) {
            return "[embedded-binary-text]";
        }
        if ((compact.startsWith("PHN2Zy") || compact.startsWith("iVBOR")) && compact.length() > 120) {
            return "[embedded-image-data]";
        }
        return text;
    }

    // ─── API Injection ──────────────────────────────────────────────────────

    private void injectAPI(Context cx, Scriptable scope) {
        // Bind all API methods as top-level JS functions via a preamble approach:
        // put a Java bridge object, then promote its methods to globals.
        OrbBridge bridge = new OrbBridge();
        Object wrapped = Context.javaToJS(bridge, scope);
        ScriptableObject.putProperty(scope, "_orb", wrapped);

        // Evaluate preamble to create global functions
        cx.evaluateString(scope, PREAMBLE, "preamble", 1, null);
    }

    private static final String PREAMBLE =
        "var ORB_EYE_VERSION = '" + VERSION + "';\n" +
        // Reading
        "function getScreen() { return JSON.parse(_orb.getScreen()); }\n" +
        "function getTree(query) { return JSON.parse(_orb.getTree(query || '')); }\n" +
        "function getInspect(opts) {\n" +
        "  if (opts === undefined || opts === null) return JSON.parse(_orb.getInspect(''));\n" +
        "  if (typeof opts === 'string') return JSON.parse(_orb.getInspect(opts));\n" +
        "  var q = [];\n" +
        "  for (var k in opts) {\n" +
        "    if (!Object.prototype.hasOwnProperty.call(opts, k)) continue;\n" +
        "    var v = opts[k];\n" +
        "    if (v === undefined || v === null) continue;\n" +
        "    q.push(encodeURIComponent(String(k)) + '=' + encodeURIComponent(String(v)));\n" +
        "  }\n" +
        "  return JSON.parse(_orb.getInspect(q.join('&')));\n" +
        "}\n" +
        "function getInfo() { return JSON.parse(_orb.getInfo()); }\n" +
        "function findText(t) { var r = _orb.findText(t); return r ? JSON.parse(r) : null; }\n" +
        "function hasText(t) { return _orb.hasText(t); }\n" +
        "function getText() { return _orb.getText(); }\n" +
        // Interaction
        "function tap(x, y) { return _orb.tap(x, y); }\n" +
        "function shellTap(x, y) { return _orb.shellTap(x, y); }\n" +
        "function click(t) { return _orb.click(t); }\n" +
        "function clickDesc(d) { return _orb.clickDesc(d); }\n" +
        "function clickId(id) { return _orb.clickId(id); }\n" +
        "function openPackage(pkg) { return _orb.openPackage(String(pkg || '')); }\n" +
        "function openActivity(pkg, cls) { return _orb.openActivity(String(pkg || ''), String(cls || '')); }\n" +
        "function openUrl(url) { return _orb.openUrl(String(url || '')); }\n" +
        "function shell(cmd) { return _orb.shell(String(cmd || '')); }\n" +
        "function input(t) { _orb.input(t); }\n" +
        "function setText(t) { _orb.setText(t); }\n" +
        "function paste() { return _orb.paste(); }\n" +
        "function scroll(dir, count) { _orb.scroll(dir || 'down', count || 1); }\n" +
        "function swipe(x1,y1,x2,y2,ms) { return _orb.swipe(x1,y1,x2,y2,ms||300); }\n" +
        "function back() { _orb.back(); }\n" +
        "function home() { _orb.home(); }\n" +
        // Sensing
        "function screenshot() { return _orb.screenshot(); }\n" +
        "function ocr() { return JSON.parse(_orb.ocr()); }\n" +
        "function ocrFind(t) { var r = _orb.ocrFind(t); return r ? JSON.parse(r) : null; }\n" +
        "function getClipboard() { return _orb.getClipboard(); }\n" +
        "function setClipboard(t) { _orb.setClipboard(t); }\n" +
        // Flow
        "function sleep(ms) { _orb.sleep(ms); }\n" +
        "function waitForChange(ms) { return _orb.waitForChange(ms || 5000); }\n" +
        "function waitForText(t, ms) { return _orb.waitForText(t, ms || 10000); }\n" +
        "function exit(v) { if (v === undefined || v === null) _orb.exit(); else _orb.exit(String(v)); }\n" +
        "function log() { _orb.log(Array.prototype.slice.call(arguments).join(' ')); }\n" +
        // waitUntil needs special handling since it takes a JS function
        "function waitUntil(fn, ms, interval) {\n" +
        "  ms = ms || 10000; interval = interval || 500;\n" +
        "  var deadline = Date.now() + ms;\n" +
        "  while (Date.now() < deadline) {\n" +
        "    if (fn()) return true;\n" +
        "    _orb.sleep(interval);\n" +
        "  }\n" +
        "  return false;\n" +
        "}\n" +
        // tapText: find by text then tap its center
        "function tapText(t) {\n" +
        "  var el = findText(t);\n" +
        "  if (!el) throw 'tapText: \"' + t + '\" not found';\n" +
        "  return tap(el.centerX, el.centerY);\n" +
        "}\n" +
        // tapOcr: find by OCR then tap
        "function tapOcr(t) {\n" +
        "  var el = ocrFind(t);\n" +
        "  if (!el) throw 'tapOcr: \"' + t + '\" not found';\n" +
        "  return tap(el.centerX, el.centerY);\n" +
        "}\n";

    // ─── Bridge Object ──────────────────────────────────────────────────────
    // Public methods are callable from JS via Rhino's Java-to-JS reflection.

    public class OrbBridge {

        // ── Reading ─────────────────────────────────────────────────────

        public String getScreen() {
            try {
                return svc.getScreenElementsForScript();
            } catch (Exception e) {
                Log.e(TAG, "getScreen error: " + e.getMessage());
                return "[]";
            }
        }

        public String getTree() {
            return getTree("");
        }

        public String getTree(String query) {
            try {
                return svc.getUiTree(query != null ? query : "");
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"getTree failed\",\"code\":\"INTERNAL_ERROR\"}";
            }
        }

        public String getInspect() {
            return getInspect("");
        }

        public String getInspect(String query) {
            try {
                return svc.getUiInspect(query != null ? query : "");
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"getInspect failed\",\"code\":\"INTERNAL_ERROR\"}";
            }
        }

        public String getInfo() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("package", svc.lastWindowPackage);
                obj.put("activity", svc.lastWindowClass);
                return obj.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        public String findText(String text) {
            try {
                return svc.findTextForScript(text);
            } catch (Exception e) {
                return null;
            }
        }

        public boolean hasText(String text) {
            try {
                return svc.hasTextForScript(text);
            } catch (Exception e) {
                return false;
            }
        }

        public String getText() {
            try {
                return svc.getVisibleTextForScript();
            } catch (Exception e) {
                return "";
            }
        }

        public void exit() {
            throw new ScriptExitException("exit");
        }

        public void exit(String result) {
            throw new ScriptExitException(result);
        }

        // ── Interaction ─────────────────────────────────────────────────

        public boolean openPackage(String packageName) {
            try {
                String pkg = packageName != null ? packageName.trim() : "";
                if (pkg.isEmpty()) return false;
                Intent intent = svc.getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent == null) return false;
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                svc.startActivity(intent);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "openPackage error: " + e.getMessage());
                return false;
            }
        }

        public boolean openActivity(String packageName, String activityName) {
            try {
                String pkg = packageName != null ? packageName.trim() : "";
                String cls = activityName != null ? activityName.trim() : "";
                if (pkg.isEmpty() || cls.isEmpty()) return false;
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(pkg, cls));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                svc.startActivity(intent);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "openActivity error: " + e.getMessage());
                return false;
            }
        }

        /**
         * Open a URL via ACTION_VIEW intent, equivalent to:
         *   adb shell am start --user 0 -a android.intent.action.VIEW -d <url> [package]
         * If the URL contains "taobao.com" or "tmall.com", it targets com.taobao.taobao directly.
         */
        public boolean openUrl(String url) {
            try {
                String u = url != null ? url.trim() : "";
                if (u.isEmpty()) return false;
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // Route to Taobao app directly for Taobao/Tmall URLs and schemes
                if (u.contains("taobao.com") || u.contains("tmall.com")
                        || u.startsWith("taobao://") || u.startsWith("tmall://")) {
                    intent.setPackage("com.taobao.taobao");
                }
                svc.startActivity(intent);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "openUrl error: " + e.getMessage());
                return false;
            }
        }

        /**
         * Execute a shell command via Runtime.exec(), equivalent to adb shell.
         * Returns the command's stdout, or empty string on error.
         */
        public String shell(String cmd) {
            Process proc = null;
            try {
                throwIfStopRequested();
                String c = cmd != null ? cmd.trim() : "";
                if (c.isEmpty()) return "";
                proc = Runtime.getRuntime().exec(new String[]{"sh", "-c", c});
                java.io.InputStream is = proc.getInputStream();
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] tmp = new byte[1024];
                int n;
                while ((n = is.read(tmp)) != -1) {
                    throwIfStopRequested();
                    buf.write(tmp, 0, n);
                }
                proc.waitFor();
                throwIfStopRequested();
                return buf.toString("UTF-8").trim();
            } catch (Exception e) {
                throwIfInterrupted(e);
                Log.e(TAG, "shell error: " + e.getMessage());
                return "";
            } finally {
                if (Thread.currentThread().isInterrupted() && proc != null) {
                    try {
                        proc.destroyForcibly();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        public boolean tap(int x, int y) {
            try {
                throwIfStopRequested();
                Path path = new Path();
                path.moveTo(x, y);
                GestureDescription.Builder builder = new GestureDescription.Builder();
                builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
                final CountDownLatch latch = new CountDownLatch(1);
                final boolean[] result = {false};
                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(() -> {
                    result[0] = svc.dispatchGesture(builder.build(),
                            new AccessibilityService.GestureResultCallback() {
                                @Override public void onCompleted(GestureDescription desc) { latch.countDown(); }
                                @Override public void onCancelled(GestureDescription desc) { latch.countDown(); }
                            }, null);
                    if (!result[0]) latch.countDown();
                });
                latch.await(3, TimeUnit.SECONDS);
                throwIfStopRequested();
                return result[0];
            } catch (Exception e) {
                throwIfInterrupted(e);
                Log.e(TAG, "tap error: " + e.getMessage());
                return false;
            }
        }

        /**
         * Execute "input tap x y" through the device shell.
         * This matches the explicit shell-tap route used by some automation tools.
         */
        public boolean shellTap(int x, int y) {
            Process proc = null;
            try {
                throwIfStopRequested();
                String cmd = "input tap " + x + " " + y;
                proc = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                int exitCode = proc.waitFor();
                throwIfStopRequested();
                if (exitCode != 0) {
                    java.io.InputStream es = proc.getErrorStream();
                    java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
                    byte[] tmp = new byte[1024];
                    int n;
                    while ((n = es.read(tmp)) != -1) err.write(tmp, 0, n);
                    Log.e(TAG, "shellTap failed: exit=" + exitCode + " err=" + err.toString("UTF-8").trim());
                    return false;
                }
                return true;
            } catch (Exception e) {
                throwIfInterrupted(e);
                Log.e(TAG, "shellTap error: " + e.getMessage());
                return false;
            } finally {
                if (Thread.currentThread().isInterrupted() && proc != null) {
                    try {
                        proc.destroyForcibly();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        public boolean click(String text) {
            try {
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                if (root == null) return false;
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
                if (nodes == null || nodes.isEmpty()) { root.recycle(); return false; }
                AccessibilityNodeInfo target = nodes.get(0);
                // Walk up to find clickable ancestor if needed
                AccessibilityNodeInfo clickable = target;
                if (!clickable.isClickable()) {
                    AccessibilityNodeInfo parent = clickable.getParent();
                    int depth = 0;
                    while (parent != null && depth < 5) {
                        if (parent.isClickable()) { clickable = parent; break; }
                        parent = parent.getParent();
                        depth++;
                    }
                }
                boolean ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                root.recycle();
                return ok;
            } catch (Exception e) {
                return false;
            }
        }

        public boolean clickDesc(String desc) {
            try {
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                if (root == null) return false;
                AccessibilityNodeInfo target = findNodeByDescBFS(root, desc);
                if (target == null) { root.recycle(); return false; }
                boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                root.recycle();
                return ok;
            } catch (Exception e) {
                return false;
            }
        }

        public boolean clickId(String id) {
            try {
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                if (root == null) return false;
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes == null || nodes.isEmpty()) { root.recycle(); return false; }
                boolean ok = nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                root.recycle();
                return ok;
            } catch (Exception e) {
                return false;
            }
        }

        public void input(String text) {
            try {
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                if (root == null) return;
                AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused == null) { root.recycle(); return; }
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                root.recycle();
            } catch (Exception e) {
                Log.e(TAG, "input error: " + e.getMessage());
            }
        }

        public void setText(String text) {
            input(text); // same underlying mechanism
        }

        public boolean paste() {
            try {
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                if (root == null) return false;
                AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused == null) {
                    root.recycle();
                    return false;
                }
                boolean ok = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                root.recycle();
                return ok;
            } catch (Exception e) {
                Log.e(TAG, "paste error: " + e.getMessage());
                return false;
            }
        }

        public void scroll(String direction, int count) {
            try {
                throwIfStopRequested();
                int action;
                switch (direction.toLowerCase()) {
                    case "up": action = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; break;
                    case "left": action = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; break;
                    default: action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; break;
                }
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                if (root == null) return;
                AccessibilityNodeInfo scrollable = findScrollable(root);
                if (scrollable != null) {
                    for (int i = 0; i < Math.max(1, count); i++) {
                        throwIfStopRequested();
                        scrollable.performAction(action);
                        if (i < count - 1) Thread.sleep(300);
                    }
                }
                root.recycle();
            } catch (Exception e) {
                throwIfInterrupted(e);
                Log.e(TAG, "scroll error: " + e.getMessage());
            }
        }

        public boolean swipe(int x1, int y1, int x2, int y2, int durationMs) {
            try {
                throwIfStopRequested();
                Path path = new Path();
                path.moveTo(x1, y1);
                path.lineTo(x2, y2);
                GestureDescription.Builder builder = new GestureDescription.Builder();
                builder.addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(durationMs, 50)));
                final CountDownLatch latch = new CountDownLatch(1);
                final boolean[] result = {false};
                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(() -> {
                    result[0] = svc.dispatchGesture(builder.build(),
                            new AccessibilityService.GestureResultCallback() {
                                @Override public void onCompleted(GestureDescription desc) { latch.countDown(); }
                                @Override public void onCancelled(GestureDescription desc) { latch.countDown(); }
                            }, null);
                    if (!result[0]) latch.countDown();
                });
                latch.await(5, TimeUnit.SECONDS);
                throwIfStopRequested();
                return result[0];
            } catch (Exception e) {
                throwIfInterrupted(e);
                return false;
            }
        }

        public void back() {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        }

        public void home() {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }

        // ── Sensing ─────────────────────────────────────────────────────

        public String screenshot() {
            // Delegate to the service's existing screenshot handler which returns base64
            try {
                throwIfStopRequested();
                String json = svc.handleScreenshotForScript();
                throwIfStopRequested();
                JSONObject obj = new JSONObject(json);
                if (obj.optBoolean("ok")) {
                    return obj.optString("image", "");
                }
                return "";
            } catch (Exception e) {
                throwIfInterrupted(e);
                return "";
            }
        }

        public String ocr() {
            try {
                throwIfStopRequested();
                String json = svc.handleOcrForScript();
                throwIfStopRequested();
                return json; // Returns JSON array of lines
            } catch (Exception e) {
                throwIfInterrupted(e);
                return "[]";
            }
        }

        public String ocrFind(String text) {
            try {
                throwIfStopRequested();
                String json = svc.handleOcrFindForScript(text);
                throwIfStopRequested();
                return json; // Returns JSON object or null
            } catch (Exception e) {
                throwIfInterrupted(e);
                return null;
            }
        }

        public String getClipboard() {
            try {
                return svc.getClipboardText();
            } catch (Exception e) {
                return "";
            }
        }

        public void setClipboard(String text) {
            try {
                svc.setClipboardText(text);
            } catch (Exception e) {
                Log.e(TAG, "setClipboard error: " + e.getMessage());
            }
        }

        // ── Flow ────────────────────────────────────────────────────────

        public void sleep(int ms) {
            try {
                throwIfStopRequested();
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                throwIfInterrupted(e);
            }
        }

        public boolean waitForChange(int timeoutMs) {
            try {
                throwIfStopRequested();
                svc.uiChangeLatch = new CountDownLatch(1);
                boolean changed = svc.uiChangeLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
                throwIfStopRequested();
                return changed;
            } catch (InterruptedException e) {
                throwIfInterrupted(e);
                return false;
            }
        }

        public boolean waitForText(String text, int timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                throwIfStopRequested();
                if (hasText(text)) return true;
                try { Thread.sleep(500); } catch (InterruptedException e) { throwIfInterrupted(e); }
            }
            return false;
        }

        public void log(String msg) {
            Log.i(TAG, "[script] " + msg);
            List<String> logs = threadLogBuffer.get();
            if (logs != null) {
                synchronized (logs) {
                    logs.add(msg);
                }
            }
            // Stream mode: emit NDJSON line immediately
            OutputStream sink = threadStreamSink.get();
            if (sink != null) {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("type", "log");
                    obj.put("msg", msg);
                    writeStreamLine(sink, obj.toString());
                } catch (Exception e) {
                    Log.e(TAG, "stream log error: " + e.getMessage());
                }
            }
        }

        // ── Helpers ─────────────────────────────────────────────────────

        private AccessibilityNodeInfo findNodeByDescBFS(AccessibilityNodeInfo root, String desc) {
            Queue<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            queue.add(root);
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo node = queue.poll();
                CharSequence cd = node.getContentDescription();
                if (cd != null && cd.toString().contains(desc)) return node;
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.add(child);
                }
            }
            return null;
        }

        private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
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
    }
}
