package com.cb.monitor.model;

import org.json.JSONObject;

public final class TaskResultPayload {
    public final String status;
    public final String result;
    public final String errorMessage;
    public final long durationMs;
    public final JSONObject raw;

    public TaskResultPayload(String status, String result, String errorMessage, long durationMs, JSONObject raw) {
        this.status = status != null ? status : "FAILED";
        this.result = result != null ? result : "";
        this.errorMessage = errorMessage != null ? errorMessage : "";
        this.durationMs = durationMs;
        this.raw = raw != null ? raw : new JSONObject();
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("status", status);
            if (!result.isEmpty()) {
                json.put("result", result);
            }
            if (!errorMessage.isEmpty()) {
                json.put("errorMessage", errorMessage);
            }
            json.put("durationMs", durationMs);
            json.put("raw", raw);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build task result json", e);
        }
        return json;
    }
}
