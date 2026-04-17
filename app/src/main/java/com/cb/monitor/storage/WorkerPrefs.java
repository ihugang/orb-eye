package com.cb.monitor.storage;

import android.content.Context;
import android.content.SharedPreferences;

public final class WorkerPrefs {
    private static final String DEFAULT_CENTER_BASE_URL = "http://115.120.248.248:16800";
    private static final String PREF_NAME = "crossbuy_worker";
    private static final String KEY_CENTER_BASE_URL = "center_base_url";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_NODE_SECRET = "node_secret";
    private static final String KEY_POLL_INTERVAL_MS = "poll_interval_ms";
    private static final String KEY_HEARTBEAT_INTERVAL_MS = "heartbeat_interval_ms";

    private final Context context;
    private final SharedPreferences prefs;

    public WorkerPrefs(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public String getCenterBaseUrl() {
        return prefs.getString(KEY_CENTER_BASE_URL, DEFAULT_CENTER_BASE_URL);
    }

    public void setCenterBaseUrl(String value) {
        prefs.edit().putString(KEY_CENTER_BASE_URL, value).apply();
    }

    public String getDeviceId() {
        String saved = prefs.getString(KEY_DEVICE_ID, "");
        if (saved != null && !saved.trim().isEmpty()) {
            return saved.trim();
        }
        String detected = DeviceIdentity.resolve(context);
        prefs.edit().putString(KEY_DEVICE_ID, detected).apply();
        return detected;
    }

    public void setDeviceId(String value) {
        prefs.edit().putString(KEY_DEVICE_ID, value != null ? value.trim() : "").apply();
    }

    public String getNodeSecret() {
        return prefs.getString(KEY_NODE_SECRET, "");
    }

    public void setNodeSecret(String value) {
        prefs.edit().putString(KEY_NODE_SECRET, value).apply();
    }

    public int getPollIntervalMs() {
        return prefs.getInt(KEY_POLL_INTERVAL_MS, 3000);
    }

    public int getHeartbeatIntervalMs() {
        return prefs.getInt(KEY_HEARTBEAT_INTERVAL_MS, 10000);
    }
}
