package com.orb.eye;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Embeds Mozilla Rhino inside orb-eye so that callers can POST a JavaScript
 * snippet to {@code /exec} and have it run locally on the device with direct
 * access to the Accessibility Service — zero HTTP round-trips per action.
 *
 * <h3>JS API exposed as global functions:</h3>
 * <pre>
 * // Reading
 * getScreen()           → [{text, desc, centerX, centerY, clickable, ...}, ...]
 * getInfo()             → {package, activity}
 * findText(text)        → {centerX, centerY, ...} or null
 * hasText(text)         → boolean
 * getText()             → all visible text as one string
 *
 * // Interaction
 * tap(x, y)             → boolean
 * click(text)           → boolean
 * clickDesc(desc)       → boolean
 * clickId(id)           → boolean
 * input(text)           → void
 * setText(text)         → void
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
 * log(msg)              → void  (captured in result.logs)
 * </pre>
 */
public class OrbScriptEngine {
    private static final String TAG = "OrbEye.JS";
    private final OrbAccessibilityService svc;
    private final List<String> logBuffer = new ArrayList<>();

    public OrbScriptEngine(OrbAccessibilityService svc) {
        this.svc = svc;
    }

    /**
     * Execute a JS script with a timeout. Returns JSON result.
     */
    public String execute(String script, int timeoutMs) {
        logBuffer.clear();
        long start = System.currentTimeMillis();
        final Object[] resultHolder = {null};
        final Exception[] errorHolder = {null};

        Thread worker = new Thread(() -> {
            Context cx = Context.enter();
            try {
                // Rhino on Android: must use interpreted mode (no bytecode gen)
                cx.setOptimizationLevel(-1);
                Scriptable scope = cx.initStandardObjects();

                injectAPI(cx, scope);

                Object result = cx.evaluateString(scope, script, "exec", 1, null);
                resultHolder[0] = Context.jsToJava(result, Object.class);
            } catch (Exception e) {
                errorHolder[0] = e;
            } finally {
                Context.exit();
            }
        });
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
                    System.currentTimeMillis() - start);
        }

        if (errorHolder[0] != null) {
            return buildError(errorHolder[0].getMessage(),
                    System.currentTimeMillis() - start);
        }

        return buildSuccess(resultHolder[0], System.currentTimeMillis() - start);
    }

    // ─── Result builders ────────────────────────────────────────────────────

    private String buildSuccess(Object result, long durationMs) {
        try {
            JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("result", result != null ? result.toString() : null);
            json.put("logs", new JSONArray(logBuffer));
            json.put("duration_ms", durationMs);
            return json.toString();
        } catch (Exception e) {
            return "{\"ok\":true,\"duration_ms\":" + durationMs + "}";
        }
    }

    private String buildError(String message, long durationMs) {
        try {
            JSONObject json = new JSONObject();
            json.put("ok", false);
            json.put("error", message);
            json.put("logs", new JSONArray(logBuffer));
            json.put("duration_ms", durationMs);
            return json.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + message + "\"}";
        }
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
        // Reading
        "function getScreen() { return JSON.parse(_orb.getScreen()); }\n" +
        "function getInfo() { return JSON.parse(_orb.getInfo()); }\n" +
        "function findText(t) { var r = _orb.findText(t); return r ? JSON.parse(r) : null; }\n" +
        "function hasText(t) { return _orb.hasText(t); }\n" +
        "function getText() { return _orb.getText(); }\n" +
        // Interaction
        "function tap(x, y) { return _orb.tap(x, y); }\n" +
        "function click(t) { return _orb.click(t); }\n" +
        "function clickDesc(d) { return _orb.clickDesc(d); }\n" +
        "function clickId(id) { return _orb.clickId(id); }\n" +
        "function input(t) { _orb.input(t); }\n" +
        "function setText(t) { _orb.setText(t); }\n" +
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
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                if (root == null) return "[]";
                JSONArray arr = new JSONArray();
                Queue<AccessibilityNodeInfo> queue = new ArrayDeque<>();
                queue.add(root);
                while (!queue.isEmpty()) {
                    AccessibilityNodeInfo node = queue.poll();
                    String text = node.getText() != null ? node.getText().toString() : "";
                    String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
                    if (!text.isEmpty() || !desc.isEmpty() || node.isClickable()) {
                        Rect bounds = new Rect();
                        node.getBoundsInScreen(bounds);
                        JSONObject obj = new JSONObject();
                        obj.put("text", text);
                        obj.put("desc", desc);
                        obj.put("centerX", bounds.centerX());
                        obj.put("centerY", bounds.centerY());
                        obj.put("clickable", node.isClickable());
                        obj.put("className", node.getClassName() != null ? node.getClassName().toString() : "");
                        arr.put(obj);
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) queue.add(child);
                    }
                }
                root.recycle();
                return arr.toString();
            } catch (Exception e) {
                Log.e(TAG, "getScreen error: " + e.getMessage());
                return "[]";
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
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                if (root == null) return null;
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
                if (nodes == null || nodes.isEmpty()) {
                    root.recycle();
                    return null;
                }
                AccessibilityNodeInfo node = nodes.get(0);
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                JSONObject obj = new JSONObject();
                obj.put("text", node.getText() != null ? node.getText().toString() : "");
                obj.put("desc", node.getContentDescription() != null ? node.getContentDescription().toString() : "");
                obj.put("centerX", bounds.centerX());
                obj.put("centerY", bounds.centerY());
                obj.put("clickable", node.isClickable());
                root.recycle();
                return obj.toString();
            } catch (Exception e) {
                return null;
            }
        }

        public boolean hasText(String text) {
            try {
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                if (root == null) return false;
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
                boolean found = nodes != null && !nodes.isEmpty();
                root.recycle();
                return found;
            } catch (Exception e) {
                return false;
            }
        }

        public String getText() {
            try {
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                if (root == null) return "";
                StringBuilder sb = new StringBuilder();
                Queue<AccessibilityNodeInfo> queue = new ArrayDeque<>();
                queue.add(root);
                while (!queue.isEmpty()) {
                    AccessibilityNodeInfo node = queue.poll();
                    if (node.getText() != null) {
                        sb.append(node.getText().toString()).append(' ');
                    }
                    if (node.getContentDescription() != null) {
                        sb.append(node.getContentDescription().toString()).append(' ');
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) queue.add(child);
                    }
                }
                root.recycle();
                return sb.toString();
            } catch (Exception e) {
                return "";
            }
        }

        // ── Interaction ─────────────────────────────────────────────────

        public boolean tap(int x, int y) {
            try {
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
                return result[0];
            } catch (Exception e) {
                Log.e(TAG, "tap error: " + e.getMessage());
                return false;
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

        public void scroll(String direction, int count) {
            try {
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
                        scrollable.performAction(action);
                        if (i < count - 1) Thread.sleep(300);
                    }
                }
                root.recycle();
            } catch (Exception e) {
                Log.e(TAG, "scroll error: " + e.getMessage());
            }
        }

        public boolean swipe(int x1, int y1, int x2, int y2, int durationMs) {
            try {
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
                return result[0];
            } catch (Exception e) {
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
                String json = svc.handleScreenshotForScript();
                JSONObject obj = new JSONObject(json);
                if (obj.optBoolean("ok")) {
                    return obj.optString("image", "");
                }
                return "";
            } catch (Exception e) {
                return "";
            }
        }

        public String ocr() {
            try {
                String json = svc.handleOcrForScript();
                return json; // Returns JSON array of lines
            } catch (Exception e) {
                return "[]";
            }
        }

        public String ocrFind(String text) {
            try {
                String json = svc.handleOcrFindForScript(text);
                return json; // Returns JSON object or null
            } catch (Exception e) {
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
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public boolean waitForChange(int timeoutMs) {
            try {
                svc.uiChangeLatch = new CountDownLatch(1);
                return svc.uiChangeLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }

        public boolean waitForText(String text, int timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (hasText(text)) return true;
                try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
            }
            return false;
        }

        public void log(String msg) {
            Log.i(TAG, "[script] " + msg);
            synchronized (logBuffer) {
                logBuffer.add(msg);
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
