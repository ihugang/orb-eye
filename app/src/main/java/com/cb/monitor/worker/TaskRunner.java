package com.cb.monitor.worker;

import android.os.Build;

import com.cb.monitor.BuildConfig;
import com.cb.monitor.OrbAccessibilityService;
import com.cb.monitor.OrbScriptEngine;
import com.cb.monitor.model.TaskEnvelope;
import com.cb.monitor.model.TaskResultPayload;

import org.json.JSONObject;

public final class TaskRunner {
    public TaskResultPayload run(OrbAccessibilityService service, TaskEnvelope task, String scriptBody) throws Exception {
        if (service == null) {
            throw new IllegalStateException("Accessibility service is not connected");
        }
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        if (scriptBody == null || scriptBody.trim().isEmpty()) {
            throw new IllegalArgumentException("script body is empty");
        }

        long start = System.currentTimeMillis();
        // Reuse the service-level engine so execution ids, worker registry,
        // and /exec/stop-all control share state with HTTP-submitted scripts.
        OrbScriptEngine engine = service.getOrCreateScriptEngine();
        String wrappedScript = buildWrappedScript(task, scriptBody);
        String displayName = task.scriptFilename != null && !task.scriptFilename.isEmpty()
                ? task.scriptFilename : task.taskNo;
        long token = service.beginJsExecution(displayName);
        String rawResult;
        try {
            rawResult = engine.execute(wrappedScript, task.timeoutMs);
        } finally {
            service.endJsExecution(token);
        }
        long durationMs = System.currentTimeMillis() - start;

        JSONObject parsed = new JSONObject(rawResult);
        boolean ok = parsed.optBoolean("ok", false);
        String result = parsed.optString("result", "");
        String error = parsed.optString("error", "");
        return new TaskResultPayload(ok ? "SUCCESS" : "FAILED", result, error, durationMs, parsed);
    }

    private String buildWrappedScript(TaskEnvelope task, String scriptBody) {
        try {
            JSONObject device = new JSONObject();
            device.put("model", Build.MODEL);
            device.put("manufacturer", Build.MANUFACTURER);
            device.put("sdk", Build.VERSION.SDK_INT);
            device.put("appVersion", BuildConfig.VERSION_NAME);

            // `$`-prefixed to mark "injected by runtime" and avoid colliding with
            // PREAMBLE API functions (e.g. global `input(text)` for typing).
            StringBuilder script = new StringBuilder();
            script.append("var $task = ").append(toJson(taskJson(task))).append(";\n");
            script.append("var $input = ").append(toJson(task.inputData)).append(";\n");
            script.append("var $device = ").append(toJson(device)).append(";\n");
            script.append("var $runtime = { app: 'crossbuy-monitor', jsEngine: 'rhino' };\n");
            script.append(scriptBody).append("\n");
            return script.toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to prepare wrapped script", e);
        }
    }

    private JSONObject taskJson(TaskEnvelope task) {
        try {
            JSONObject json = new JSONObject();
            json.put("taskId", task.taskId);
            json.put("taskNo", task.taskNo);
            json.put("platform", task.platform);
            json.put("dispatchKey", task.dispatchKey);
            json.put("timeoutMs", task.timeoutMs);
            return json;
        } catch (Exception e) {
            throw new IllegalStateException("failed to build task json", e);
        }
    }

    private String toJson(JSONObject json) {
        return json != null ? json.toString() : "{}";
    }
}
