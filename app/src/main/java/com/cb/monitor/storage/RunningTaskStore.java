package com.cb.monitor.storage;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

public final class RunningTaskStore {
    private static final String PREF_NAME = "crossbuy_running_task";
    private static final String KEY_TASK_JSON = "task_json";

    private final SharedPreferences prefs;

    public RunningTaskStore(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void save(JSONObject taskJson) {
        prefs.edit().putString(KEY_TASK_JSON, taskJson != null ? taskJson.toString() : "").apply();
    }

    public JSONObject load() {
        String raw = prefs.getString(KEY_TASK_JSON, "");
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    public void clear() {
        prefs.edit().remove(KEY_TASK_JSON).apply();
    }
}
