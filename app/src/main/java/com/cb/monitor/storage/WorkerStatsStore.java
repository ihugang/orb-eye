package com.cb.monitor.storage;

import android.content.Context;
import android.content.SharedPreferences;

import com.cb.monitor.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class WorkerStatsStore {
    private final Context context;

    public static final class RecentTaskEntry {
        public final String taskNo;
        public final String status;
        public final String detail;
        public final long timestamp;

        public RecentTaskEntry(String taskNo, String status, String detail, long timestamp) {
            this.taskNo = safe(taskNo, "-");
            this.status = safe(status, "UNKNOWN");
            this.detail = safe(detail, "-");
            this.timestamp = timestamp;
        }
    }

    public static final class RecentIssueEntry {
        public final String category;
        public final String title;
        public final String detail;
        public final long timestamp;

        public RecentIssueEntry(String category, String title, String detail, long timestamp) {
            this.category = safe(category, "GENERAL");
            this.title = safe(title, "Issue");
            this.detail = safe(detail, "-");
            this.timestamp = timestamp;
        }
    }

    public static final String STATUS_UNKNOWN = "unknown";
    public static final String STATUS_ONLINE = "online";
    public static final String STATUS_OFFLINE = "offline";
    public static final String STATUS_PENDING_APPROVAL = "pending_approval";
    public static final String STATUS_ERROR = "error";
    public static final String RUNTIME_UNKNOWN = "unknown";
    public static final String RUNTIME_IDLE = "idle";
    public static final String RUNTIME_RUNNING = "running";
    public static final String RUNTIME_WAITING_ACCESSIBILITY = "waiting_accessibility";
    public static final String RUNTIME_PENDING_APPROVAL = "pending_approval";
    public static final String RUNTIME_ERROR = "error";

    private static final String PREF_NAME = "crossbuy_worker_stats";
    private static final String KEY_TOTAL_COMPLETED = "total_completed";
    private static final String KEY_TODAY_COMPLETED = "today_completed";
    private static final String KEY_TODAY_DATE = "today_date";
    private static final String KEY_SERVER_STATUS = "server_status";
    private static final String KEY_SERVER_MESSAGE = "server_message";
    private static final String KEY_LAST_TASK_NO = "last_task_no";
    private static final String KEY_LAST_UPDATED_AT = "last_updated_at";
    private static final String KEY_RECENT_TASKS = "recent_tasks";
    private static final String KEY_RECENT_ISSUES = "recent_issues";
    private static final String KEY_RUNTIME_STATUS = "runtime_status";
    private static final String KEY_RUNTIME_MESSAGE = "runtime_message";
    private static final int MAX_RECENT_TASKS = 6;
    private static final int MAX_RECENT_ISSUES = 4;

    private final SharedPreferences prefs;

    public WorkerStatsStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public int getTotalCompleted() {
        return prefs.getInt(KEY_TOTAL_COMPLETED, 0);
    }

    public int getTodayCompleted() {
        ensureTodayBucket();
        return prefs.getInt(KEY_TODAY_COMPLETED, 0);
    }

    public String getServerStatus() {
        return prefs.getString(KEY_SERVER_STATUS, STATUS_UNKNOWN);
    }

    public String getServerMessage() {
        return prefs.getString(KEY_SERVER_MESSAGE, context.getString(R.string.stats_no_heartbeat_yet));
    }

    public String getLastTaskNo() {
        return prefs.getString(KEY_LAST_TASK_NO, "-");
    }

    public long getLastUpdatedAt() {
        return prefs.getLong(KEY_LAST_UPDATED_AT, 0L);
    }

    public String getRuntimeStatus() {
        return prefs.getString(KEY_RUNTIME_STATUS, RUNTIME_UNKNOWN);
    }

    public String getRuntimeMessage() {
        return prefs.getString(KEY_RUNTIME_MESSAGE, context.getString(R.string.stats_worker_not_started));
    }

    public List<RecentTaskEntry> getRecentTasks() {
        List<RecentTaskEntry> tasks = new ArrayList<>();
        String raw = prefs.getString(KEY_RECENT_TASKS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String taskNo = safe(item.optString("taskNo", ""), "-");
                String status = safe(item.optString("status", ""), "UNKNOWN");
                String detail = safe(item.optString("detail", ""), "-");
                long timestamp = item.optLong("timestamp", 0L);
                tasks.add(new RecentTaskEntry(taskNo, status, detail, timestamp));
            }
        } catch (Exception ignored) {
            // Return an empty list if persisted data is malformed.
        }
        return tasks;
    }

    public List<RecentIssueEntry> getRecentIssues() {
        List<RecentIssueEntry> issues = new ArrayList<>();
        String raw = prefs.getString(KEY_RECENT_ISSUES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                issues.add(new RecentIssueEntry(
                        item.optString("category", "GENERAL"),
                        item.optString("title", "Issue"),
                        item.optString("detail", "-"),
                        item.optLong("timestamp", 0L)
                ));
            }
        } catch (Exception ignored) {
            // Return empty list when persisted data is malformed.
        }
        return issues;
    }

    public void markHeartbeatSuccess(String message) {
        setServerState(STATUS_ONLINE, safe(message, context.getString(R.string.stats_connected)));
    }

    public void markHeartbeatFailure(String message) {
        setServerState(STATUS_OFFLINE, safe(message, context.getString(R.string.stats_heartbeat_failed)));
    }

    public void markPendingApproval(String message) {
        setServerState(STATUS_PENDING_APPROVAL, safe(message, context.getString(R.string.stats_pending_approval)));
        setRuntimeState(RUNTIME_PENDING_APPROVAL, safe(message, context.getString(R.string.stats_pending_approval)));
        appendRecentIssue("APPROVAL", context.getString(R.string.stats_pending_approval), safe(message, context.getString(R.string.stats_pending_approval)));
    }

    public void markServerError(String message) {
        setServerState(STATUS_ERROR, safe(message, context.getString(R.string.stats_server_error)));
        setRuntimeState(RUNTIME_ERROR, safe(message, context.getString(R.string.stats_server_error)));
        appendRecentIssue("SERVER", context.getString(R.string.stats_server_error), safe(message, context.getString(R.string.stats_server_error)));
    }

    public void markWorkerError(String message) {
        setRuntimeState(RUNTIME_ERROR, safe(message, context.getString(R.string.stats_worker_error)));
        appendRecentIssue("WORKER", context.getString(R.string.stats_worker_error), safe(message, context.getString(R.string.stats_worker_error)));
    }

    public void markServerOnline(String message) {
        setServerState(STATUS_ONLINE, safe(message, context.getString(R.string.stats_connected)));
    }

    public void markWorkerIdle(String message) {
        setRuntimeState(RUNTIME_IDLE, safe(message, context.getString(R.string.stats_waiting_tasks)));
    }

    public void markWorkerRunning(String taskNo) {
        setRuntimeState(RUNTIME_RUNNING, context.getString(R.string.stats_executing_task, safe(taskNo, "-")));
    }

    public void markWaitingAccessibility(String message) {
        setRuntimeState(RUNTIME_WAITING_ACCESSIBILITY, safe(message, context.getString(R.string.stats_accessibility_not_ready)));
        appendRecentIssue("ACCESSIBILITY", context.getString(R.string.stats_accessibility_required), safe(message, context.getString(R.string.stats_accessibility_not_ready)));
    }

    public void markTaskStarted(String taskNo) {
        prefs.edit()
                .putString(KEY_LAST_TASK_NO, safe(taskNo, "-"))
                .putLong(KEY_LAST_UPDATED_AT, System.currentTimeMillis())
                .apply();
        markWorkerRunning(taskNo);
        appendRecentTask(taskNo, "RUNNING", context.getString(R.string.stats_task_waiting_result));
    }

    public void markTaskCompleted(String taskNo) {
        ensureTodayBucket();
        prefs.edit()
                .putInt(KEY_TOTAL_COMPLETED, getTotalCompleted() + 1)
                .putInt(KEY_TODAY_COMPLETED, getTodayCompleted() + 1)
                .putString(KEY_LAST_TASK_NO, safe(taskNo, "-"))
                .putLong(KEY_LAST_UPDATED_AT, System.currentTimeMillis())
                .apply();
        markWorkerIdle(context.getString(R.string.stats_last_task_completed));
        appendRecentTask(taskNo, "SUCCESS", context.getString(R.string.stats_task_completed));
    }

    public void markTaskFailed(String taskNo, String message) {
        prefs.edit()
                .putString(KEY_LAST_TASK_NO, safe(taskNo, "-"))
                .putString(KEY_SERVER_MESSAGE, safe(message, context.getString(R.string.stats_task_failed)))
                .putLong(KEY_LAST_UPDATED_AT, System.currentTimeMillis())
                .apply();
        markWorkerError(safe(message, context.getString(R.string.stats_task_failed)));
        appendRecentTask(taskNo, "FAILED", safe(message, context.getString(R.string.stats_task_failed)));
    }

    private void setServerState(String status, String message) {
        prefs.edit()
                .putString(KEY_SERVER_STATUS, safe(status, STATUS_UNKNOWN))
                .putString(KEY_SERVER_MESSAGE, safe(message, context.getString(R.string.stats_no_heartbeat_yet)))
                .putLong(KEY_LAST_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    private void setRuntimeState(String status, String message) {
        prefs.edit()
                .putString(KEY_RUNTIME_STATUS, safe(status, RUNTIME_UNKNOWN))
                .putString(KEY_RUNTIME_MESSAGE, safe(message, context.getString(R.string.stats_worker_not_started)))
                .putLong(KEY_LAST_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    private void appendRecentTask(String taskNo, String status, String detail) {
        JSONArray next = new JSONArray();
        try {
            JSONObject current = new JSONObject();
            current.put("taskNo", safe(taskNo, "-"));
            current.put("status", safe(status, "UNKNOWN"));
            current.put("detail", safe(detail, "-"));
            current.put("timestamp", System.currentTimeMillis());
            next.put(current);

            JSONArray existing = new JSONArray(prefs.getString(KEY_RECENT_TASKS, "[]"));
            for (int i = 0; i < existing.length() && next.length() < MAX_RECENT_TASKS; i++) {
                JSONObject item = existing.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                boolean sameTask = safe(taskNo, "-").equals(item.optString("taskNo", ""));
                boolean sameStatus = safe(status, "UNKNOWN").equals(item.optString("status", ""));
                if (sameTask && sameStatus) {
                    continue;
                }
                next.put(item);
            }
            prefs.edit().putString(KEY_RECENT_TASKS, next.toString()).apply();
        } catch (Exception ignored) {
            // Skip recent-task persistence when JSON encoding fails.
        }
    }

    private void appendRecentIssue(String category, String title, String detail) {
        JSONArray next = new JSONArray();
        try {
            JSONObject current = new JSONObject();
            current.put("category", safe(category, "GENERAL"));
            current.put("title", safe(title, "Issue"));
            current.put("detail", safe(detail, "-"));
            current.put("timestamp", System.currentTimeMillis());
            next.put(current);

            JSONArray existing = new JSONArray(prefs.getString(KEY_RECENT_ISSUES, "[]"));
            for (int i = 0; i < existing.length() && next.length() < MAX_RECENT_ISSUES; i++) {
                JSONObject item = existing.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                boolean sameTitle = safe(title, "Issue").equals(item.optString("title", ""));
                boolean sameDetail = safe(detail, "-").equals(item.optString("detail", ""));
                if (sameTitle && sameDetail) {
                    continue;
                }
                next.put(item);
            }
            prefs.edit().putString(KEY_RECENT_ISSUES, next.toString()).apply();
        } catch (Exception ignored) {
            // Skip issue persistence when JSON encoding fails.
        }
    }

    private void ensureTodayBucket() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String saved = prefs.getString(KEY_TODAY_DATE, "");
        if (!today.equals(saved)) {
            prefs.edit()
                    .putString(KEY_TODAY_DATE, today)
                    .putInt(KEY_TODAY_COMPLETED, 0)
                    .apply();
        }
    }

    private static String safe(String value, String fallback) {
        return value != null && !value.trim().isEmpty() ? value : fallback;
    }

    public static String formatTimestamp(long timestamp) {
        if (timestamp <= 0L) {
            return "Unknown time";
        }
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(timestamp));
    }
}
