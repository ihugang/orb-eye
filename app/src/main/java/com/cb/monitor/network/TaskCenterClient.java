package com.cb.monitor.network;

import android.os.Build;
import android.util.Log;

import com.cb.monitor.model.TaskEnvelope;
import com.cb.monitor.model.TaskResultPayload;
import com.cb.monitor.storage.WorkerPrefs;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class TaskCenterClient {
    private static final String TAG = "CrossBuy.TaskCenter";

    public static final class DeviceState {
        public final String status;
        public final String approvalStatus;
        public final boolean envReady;

        DeviceState(String status, String approvalStatus, boolean envReady) {
            this.status = status != null ? status : "";
            this.approvalStatus = approvalStatus != null ? approvalStatus : "";
            this.envReady = envReady;
        }
    }

    private final WorkerPrefs prefs;
    private String registeredDeviceId = "";
    private String registeredBaseUrl = "";

    public TaskCenterClient(WorkerPrefs prefs) {
        this.prefs = prefs;
    }

    public void heartbeat(boolean envReady, String reason) throws Exception {
        ensureRegistered();
        String url = prefs.getCenterBaseUrl() + "/api/v2/mobile/devices/" + encode(prefs.getDeviceId()) + "/heartbeat";
        JSONObject body = new JSONObject();
        body.put("status", "ONLINE");
        body.put("envReady", envReady);
        if (reason != null && !reason.isEmpty()) {
            body.put("envReason", reason);
            body.put("reason", reason);
        }
        postJson(url, body);
    }

    public TaskEnvelope pullNextTask() throws Exception {
        ensureRegistered();
        StringBuilder url = new StringBuilder();
        url.append(prefs.getCenterBaseUrl())
                .append("/api/v2/mobile/devices/")
                .append(encode(prefs.getDeviceId()))
                .append("/tasks/next");

        HttpResult result = request("GET", url.toString(), null, "application/json");
        if (result.code == 204 || result.body.trim().isEmpty()) {
            return null;
        }
        if (result.code >= 400) {
            throw toApiException("pullNextTask failed", result);
        }
        return TaskEnvelope.fromJson(new JSONObject(result.body));
    }

    public DeviceState fetchDeviceState() throws Exception {
        ensureRegistered();
        String url = prefs.getCenterBaseUrl() + "/api/v2/mobile/devices/" + encode(prefs.getDeviceId()) + "/status";
        HttpResult result = request("GET", url, null, "application/json");
        if (result.code >= 400) {
            throw toApiException("fetchDeviceState failed", result);
        }
        JSONObject json = new JSONObject(result.body);
        return new DeviceState(
                json.optString("status", ""),
                json.optString("approvalStatus", ""),
                json.optBoolean("envReady", false)
        );
    }

    public String downloadScript(TaskEnvelope task) throws Exception {
        if (task == null) {
            return "";
        }
        if (task.hasInlineScript()) {
            return task.scriptContent;
        }

        StringBuilder url = new StringBuilder();
        url.append(prefs.getCenterBaseUrl()).append("/api/v1/center/exec/scripts/download");
        boolean hasQuery = false;
        if (!task.scriptPath.isEmpty()) {
            url.append("?path=").append(encode(task.scriptPath));
            hasQuery = true;
        }
        if (!task.scriptFilename.isEmpty()) {
            url.append(hasQuery ? "&" : "?").append("filename=").append(encode(task.scriptFilename));
        }
        HttpResult result = request("GET", url.toString(), null, "application/octet-stream");
        if (result.code >= 400) {
            throw new IllegalStateException("downloadScript failed: HTTP " + result.code + " " + result.body);
        }
        return result.body;
    }

    public void markExecuting(TaskEnvelope task) throws Exception {
        if (task == null || task.taskId <= 0) {
            return;
        }
        String url = prefs.getCenterBaseUrl() + "/api/v1/center/exec/tasks/" + task.taskId + "/executing";
        JSONObject body = new JSONObject();
        body.put("deviceId", prefs.getDeviceId());
        postJson(url, body);
    }

    public void reportResult(TaskEnvelope task, TaskResultPayload payload) throws Exception {
        if (task == null || task.taskId <= 0 || payload == null) {
            return;
        }
        String url = prefs.getCenterBaseUrl() + "/api/v1/center/tasks/" + task.taskId + "/result";
        JSONObject body = payload.toJson();
        body.put("deviceId", prefs.getDeviceId());
        postJson(url, body);
    }

    private void ensureRegistered() throws Exception {
        String deviceId = prefs.getDeviceId();
        String baseUrl = prefs.getCenterBaseUrl();
        if (deviceId.equals(registeredDeviceId) && baseUrl.equals(registeredBaseUrl)) {
            return;
        }

        String url = baseUrl + "/api/v2/mobile/devices/register";
        JSONObject body = new JSONObject();
        body.put("deviceId", deviceId);
        body.put("deviceName", buildDeviceName());
        body.put("deviceModel", Build.MODEL != null ? Build.MODEL : "");
        body.put("deviceOsVersion", Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "");
        body.put("deviceSdkVersion", String.valueOf(Build.VERSION.SDK_INT));
        postJson(url, body);

        registeredDeviceId = deviceId;
        registeredBaseUrl = baseUrl;
    }

    private String buildDeviceName() {
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.trim() : "";
        String model = Build.MODEL != null ? Build.MODEL.trim() : "";
        String combined = (manufacturer + " " + model).trim();
        return combined.isEmpty() ? prefs.getDeviceId() : combined;
    }

    private void postJson(String url, JSONObject body) throws Exception {
        HttpResult result = request("POST", url, body.toString().getBytes(StandardCharsets.UTF_8), "application/json");
        if (result.code >= 400) {
            throw toApiException("POST failed", result);
        }
    }

    private ApiException toApiException(String prefix, HttpResult result) {
        String errorCode = null;
        String message = result.body;
        try {
            JSONObject json = new JSONObject(result.body);
            errorCode = json.optString("errorCode", null);
            String serverMessage = json.optString("message", "");
            if (!serverMessage.trim().isEmpty()) {
                message = serverMessage;
            }
        } catch (Exception ignored) {
            // Keep raw body when the server does not return JSON.
        }
        StringBuilder fullMessage = new StringBuilder(prefix)
                .append(": HTTP ")
                .append(result.code);
        if (errorCode != null && !errorCode.trim().isEmpty()) {
            fullMessage.append(" ").append(errorCode);
        }
        if (message != null && !message.trim().isEmpty()) {
            fullMessage.append(" ").append(message);
        }
        return new ApiException(fullMessage.toString(), result.code, errorCode, message);
    }

    private HttpResult request(String method, String rawUrl, byte[] body, String contentType) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(rawUrl).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseBody = readFully(stream);
            return new HttpResult(code, responseBody);
        } catch (Exception e) {
            Log.e(TAG, "request failed " + rawUrl + ": " + e.getMessage());
            throw e;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readFully(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        try (InputStream input = new BufferedInputStream(stream);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8.name());
    }

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body != null ? body : "";
        }
    }

    public static final class ApiException extends Exception {
        public final int httpCode;
        public final String errorCode;
        public final String serverMessage;

        ApiException(String message, int httpCode, String errorCode, String serverMessage) {
            super(message);
            this.httpCode = httpCode;
            this.errorCode = errorCode != null ? errorCode : "";
            this.serverMessage = serverMessage != null ? serverMessage : "";
        }

        public boolean hasErrorCode(String expected) {
            return expected != null && expected.equalsIgnoreCase(errorCode);
        }
    }
}
