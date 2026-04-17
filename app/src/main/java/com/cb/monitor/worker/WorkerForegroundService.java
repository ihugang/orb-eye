package com.cb.monitor.worker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import com.cb.monitor.OrbAccessibilityService;
import com.cb.monitor.R;
import com.cb.monitor.model.TaskEnvelope;
import com.cb.monitor.model.TaskResultPayload;
import com.cb.monitor.network.TaskCenterClient;
import com.cb.monitor.storage.RunningTaskStore;
import com.cb.monitor.storage.WorkerPrefs;
import com.cb.monitor.storage.WorkerStatsStore;

import org.json.JSONObject;

public class WorkerForegroundService extends Service {
    private static final String TAG = "CrossBuy.Worker";
    private static final String CHANNEL_ID = "crossbuy-worker";
    private static final int NOTIFICATION_ID = 73331;

    private HandlerThread workerThread;
    private Handler handler;
    private WorkerPrefs workerPrefs;
    private RunningTaskStore runningTaskStore;
    private WorkerStatsStore workerStatsStore;
    private TaskCenterClient taskCenterClient;
    private TaskRunner taskRunner;
    private long lastHeartbeatAt = 0L;
    private volatile boolean isExecuting = false;

    public static Intent createStartIntent(Context context) {
        return new Intent(context, WorkerForegroundService.class);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        workerPrefs = new WorkerPrefs(this);
        runningTaskStore = new RunningTaskStore(this);
        workerStatsStore = new WorkerStatsStore(this);
        taskCenterClient = new TaskCenterClient(workerPrefs);
        taskRunner = new TaskRunner();

        workerThread = new HandlerThread("crossbuy-worker");
        workerThread.start();
        handler = new Handler(workerThread.getLooper());

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.worker_notification_starting)));
        workerStatsStore.markWorkerIdle(getString(R.string.worker_service_started));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        scheduleLoop(0);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void scheduleLoop(long delayMs) {
        if (handler == null) {
            return;
        }
        handler.removeCallbacks(loopRunnable);
        handler.postDelayed(loopRunnable, Math.max(0L, delayMs));
    }

    private final Runnable loopRunnable = () -> {
        try {
            tick();
        } catch (Exception e) {
            Log.e(TAG, "worker loop failed: " + e.getMessage(), e);
            updateNotification(getString(R.string.worker_loop_error, e.getMessage()));
        } finally {
            scheduleLoop(workerPrefs.getPollIntervalMs());
        }
    };

    private void tick() throws Exception {
        maybeHeartbeat();
        if (isExecuting) {
            return;
        }

        OrbAccessibilityService runtime = OrbAccessibilityService.getActiveInstance();
        if (runtime == null) {
            workerStatsStore.markHeartbeatFailure(getString(R.string.worker_accessibility_not_ready));
            workerStatsStore.markWaitingAccessibility(getString(R.string.worker_enable_accessibility));
            updateNotification(getString(R.string.worker_waiting_accessibility_notification));
            return;
        }

        TaskEnvelope task;
        try {
            task = taskCenterClient.pullNextTask();
        } catch (TaskCenterClient.ApiException e) {
            if (e.hasErrorCode("DEVICE_PENDING_APPROVAL")) {
                workerStatsStore.markPendingApproval(getString(R.string.worker_pending_approval_message));
                updateNotification(getString(R.string.worker_pending_approval_notification));
                return;
            }
            workerStatsStore.markServerError(resolvePullErrorMessage(e));
            updateNotification(getString(R.string.worker_server_error));
            return;
        } catch (Exception e) {
            workerStatsStore.markServerError(e.getMessage());
            updateNotification(getString(R.string.worker_server_error));
            return;
        }

        if (task == null) {
            workerStatsStore.markServerOnline(getString(R.string.worker_connected_waiting));
            workerStatsStore.markWorkerIdle(getString(R.string.worker_connected_waiting));
            updateNotification(getString(R.string.worker_idle_no_task));
            return;
        }

        isExecuting = true;
        runningTaskStore.save(taskToJson(task));
        workerStatsStore.markTaskStarted(task.taskNo);
        updateNotification(getString(R.string.worker_running_task, task.taskNo));

        try {
            taskCenterClient.markExecuting(task);
        } catch (Exception ignored) {
            Log.w(TAG, "markExecuting not available yet: " + ignored.getMessage());
        }

        try {
            String script = taskCenterClient.downloadScript(task);
            TaskResultPayload result = taskRunner.run(runtime, task, script);
            taskCenterClient.reportResult(task, result);
            workerStatsStore.markTaskCompleted(task.taskNo);
            updateNotification(getString(R.string.worker_task_complete, task.taskNo));
        } catch (Exception e) {
            TaskResultPayload failed = new TaskResultPayload(
                    "FAILED",
                    "",
                    e.getMessage() != null ? e.getMessage() : getString(R.string.worker_task_failed_fallback),
                    0L,
                    new JSONObject()
            );
            try {
                taskCenterClient.reportResult(task, failed);
            } catch (Exception reportErr) {
                Log.e(TAG, "reportResult failed: " + reportErr.getMessage(), reportErr);
            }
            workerStatsStore.markTaskFailed(task.taskNo, e.getMessage());
            updateNotification(getString(R.string.worker_task_failed, task.taskNo));
            throw e;
        } finally {
            runningTaskStore.clear();
            isExecuting = false;
        }
    }

    private void maybeHeartbeat() {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatAt < workerPrefs.getHeartbeatIntervalMs()) {
            return;
        }
        try {
            boolean envReady = OrbAccessibilityService.getActiveInstance() != null;
            String reason = envReady ? "" : "ACCESSIBILITY_NOT_READY";
            taskCenterClient.heartbeat(envReady, reason);
            lastHeartbeatAt = now;
            workerStatsStore.markHeartbeatSuccess(envReady ? getString(R.string.worker_server_reachable) : reason);
        } catch (Exception e) {
            Log.w(TAG, "heartbeat failed: " + e.getMessage());
            workerStatsStore.markHeartbeatFailure(e.getMessage());
            workerStatsStore.markWorkerError(e.getMessage());
            updateNotification(getString(R.string.worker_heartbeat_failed));
        }
    }

    private String resolvePullErrorMessage(TaskCenterClient.ApiException error) {
        if (error.serverMessage != null && !error.serverMessage.trim().isEmpty()) {
            return error.serverMessage;
        }
        if (error.httpCode >= 500) {
            return getString(R.string.worker_task_center_unavailable);
        }
        if (error.httpCode >= 400) {
            return getString(R.string.worker_request_rejected);
        }
        return error.getMessage();
    }

    private JSONObject taskToJson(TaskEnvelope task) {
        try {
            JSONObject json = new JSONObject();
            json.put("taskId", task.taskId);
            json.put("taskNo", task.taskNo);
            json.put("platform", task.platform);
            json.put("scriptPath", task.scriptPath);
            json.put("scriptFilename", task.scriptFilename);
            json.put("scriptContent", task.scriptContent);
            json.put("timeoutMs", task.timeoutMs);
            json.put("dispatchKey", task.dispatchKey);
            json.put("inputData", task.inputData);
            return json;
        } catch (Exception e) {
            throw new IllegalStateException(getString(R.string.worker_failed_persist_task), e);
        }
    }

    private Notification buildNotification(String contentText) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle(getString(R.string.worker_notification_title))
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true);
        return builder.build();
    }

    private void updateNotification(String contentText) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(contentText));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.worker_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.worker_channel_desc));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
