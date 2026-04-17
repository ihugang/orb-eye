package com.cb.monitor.model;

import org.json.JSONObject;

public final class TaskEnvelope {
    public final long taskId;
    public final String taskNo;
    public final String platform;
    public final String scriptPath;
    public final String scriptFilename;
    public final String scriptContent;
    public final int timeoutMs;
    public final JSONObject inputData;
    public final String dispatchKey;

    public TaskEnvelope(
            long taskId,
            String taskNo,
            String platform,
            String scriptPath,
            String scriptFilename,
            String scriptContent,
            int timeoutMs,
            JSONObject inputData,
            String dispatchKey
    ) {
        this.taskId = taskId;
        this.taskNo = taskNo != null ? taskNo : "";
        this.platform = platform != null ? platform : "";
        this.scriptPath = scriptPath != null ? scriptPath : "";
        this.scriptFilename = scriptFilename != null ? scriptFilename : "";
        this.scriptContent = scriptContent != null ? scriptContent : "";
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 60000;
        this.inputData = inputData != null ? inputData : new JSONObject();
        this.dispatchKey = dispatchKey != null ? dispatchKey : "";
    }

    public static TaskEnvelope fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }

        JSONObject input = json.optJSONObject("inputData");
        if (input == null) {
            input = json.optJSONObject("input_data");
        }
        if (input == null) {
            input = new JSONObject();
        }

        return new TaskEnvelope(
                json.optLong("taskId", json.optLong("id", 0L)),
                json.optString("taskNo", json.optString("task_no", "")),
                json.optString("platform", ""),
                json.optString("scriptPath", json.optString("script_path", "")),
                json.optString("scriptFilename", json.optString("script_filename", "")),
                json.optString("scriptContent", json.optString("script_content", "")),
                json.optInt("timeoutMs", json.optInt("timeout_ms", 60000)),
                input,
                json.optString("dispatchKey", json.optString("dispatch_key", ""))
        );
    }

    public boolean hasInlineScript() {
        return !scriptContent.isEmpty();
    }
}
