package com.cb.monitor;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.cb.monitor.model.TaskEnvelope;
import com.cb.monitor.model.TaskResultPayload;
import com.cb.monitor.storage.ScriptStore;
import com.cb.monitor.storage.WorkerPrefs;
import com.cb.monitor.worker.TaskRunner;
import com.cb.monitor.worker.WorkerForegroundService;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SettingsActivity extends Activity {
    private static final int REQUEST_PICK_SCRIPT = 2001;

    private WorkerPrefs workerPrefs;
    private ScriptStore scriptStore;
    private TextView statusView;
    private LinearLayout scriptListContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        workerPrefs = new WorkerPrefs(this);
        scriptStore = new ScriptStore(this);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);
        scrollView.addView(layout);

        TextView title = new TextView(this);
        title.setText(R.string.settings_title);
        title.setTextSize(28);
        layout.addView(title);

        TextView desc = new TextView(this);
        desc.setText(R.string.settings_desc);
        desc.setTextSize(16);
        desc.setPadding(0, 16, 0, 24);
        layout.addView(desc);

        statusView = new TextView(this);
        statusView.setText(R.string.settings_ready);
        statusView.setTextSize(14);
        statusView.setGravity(Gravity.START);
        layout.addView(statusView);

        TextView taskCenterTitle = new TextView(this);
        taskCenterTitle.setText(R.string.settings_task_center_title);
        taskCenterTitle.setTextSize(16);
        taskCenterTitle.setPadding(0, 24, 0, 8);
        layout.addView(taskCenterTitle);

        EditText taskCenterInput = new EditText(this);
        taskCenterInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        taskCenterInput.setHint(R.string.settings_task_center_hint);
        taskCenterInput.setText(workerPrefs.getCenterBaseUrl());
        layout.addView(taskCenterInput);

        TextView deviceIdTitle = new TextView(this);
        deviceIdTitle.setText(R.string.settings_device_id_title);
        deviceIdTitle.setTextSize(16);
        deviceIdTitle.setPadding(0, 20, 0, 8);
        layout.addView(deviceIdTitle);

        EditText deviceIdInput = new EditText(this);
        deviceIdInput.setInputType(InputType.TYPE_CLASS_TEXT);
        deviceIdInput.setHint(R.string.settings_device_id_hint);
        deviceIdInput.setText(workerPrefs.getDeviceId());
        layout.addView(deviceIdInput);

        addButton(layout, getString(R.string.settings_save_config), v -> {
            String raw = taskCenterInput.getText() != null ? taskCenterInput.getText().toString().trim() : "";
            String normalized = normalizeTaskCenterUrl(raw);
            if (normalized.isEmpty()) {
                statusView.setText(R.string.settings_invalid_task_center_url);
                Toast.makeText(this, R.string.settings_invalid_task_center_url, Toast.LENGTH_SHORT).show();
                return;
            }
            String rawDeviceId = deviceIdInput.getText() != null ? deviceIdInput.getText().toString().trim() : "";
            if (rawDeviceId.isEmpty()) {
                statusView.setText(R.string.settings_invalid_device_id);
                Toast.makeText(this, R.string.settings_invalid_device_id, Toast.LENGTH_SHORT).show();
                return;
            }
            workerPrefs.setCenterBaseUrl(normalized);
            workerPrefs.setDeviceId(rawDeviceId);
            taskCenterInput.setText(normalized);
            deviceIdInput.setText(rawDeviceId);
            statusView.setText(R.string.settings_config_saved);
            Toast.makeText(this, R.string.settings_config_saved, Toast.LENGTH_SHORT).show();
        });

        TextView scriptLibraryTitle = new TextView(this);
        scriptLibraryTitle.setText(R.string.settings_script_library_title);
        scriptLibraryTitle.setTextSize(16);
        scriptLibraryTitle.setPadding(0, 28, 0, 8);
        layout.addView(scriptLibraryTitle);

        TextView scriptLibraryDesc = new TextView(this);
        scriptLibraryDesc.setText(R.string.settings_script_library_desc);
        scriptLibraryDesc.setTextSize(14);
        scriptLibraryDesc.setPadding(0, 0, 0, 12);
        layout.addView(scriptLibraryDesc);

        addButton(layout, getString(R.string.settings_import_script), v -> openScriptPicker());
        addButton(layout, getString(R.string.settings_stop_script), v -> {
            callLocalApi("/exec/stopAll", statusView);
            Toast.makeText(this, R.string.settings_stop_script_done, Toast.LENGTH_SHORT).show();
        });

        scriptListContainer = new LinearLayout(this);
        scriptListContainer.setOrientation(LinearLayout.VERTICAL);
        layout.addView(scriptListContainer);

        addButton(layout, getString(R.string.settings_open_accessibility), v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            statusView.setText(R.string.settings_opened_accessibility);
        });
        addButton(layout, getString(R.string.settings_start_worker), v -> {
            startForegroundService(WorkerForegroundService.createStartIntent(this));
            statusView.setText(R.string.settings_worker_started);
        });
        addButton(layout, getString(R.string.settings_stop_worker), v -> {
            stopService(WorkerForegroundService.createStartIntent(this));
            statusView.setText(R.string.settings_worker_stopped);
        });
        addButton(layout, getString(R.string.settings_start_inspector), v -> callLocalApi("/inspector/start?maxDepth=40", statusView));
        addButton(layout, getString(R.string.settings_show_controller), v -> callLocalApi("/inspector/controller/show", statusView));
        addButton(layout, getString(R.string.settings_refresh_inspector), v -> callLocalApi("/inspector/refresh?maxDepth=40", statusView));
        addButton(layout, getString(R.string.settings_stop_inspector), v -> callLocalApi("/inspector/stop", statusView));
        addButton(layout, getString(R.string.settings_stop_all_js), v -> callLocalApi("/exec/stop-all", statusView));
        addButton(layout, getString(R.string.settings_hide_controller), v -> callLocalApi("/inspector/controller/hide", statusView));

        setContentView(scrollView);
        refreshScriptList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScriptList();
    }

    private void addButton(LinearLayout layout, String text, android.view.View.OnClickListener onClickListener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(onClickListener);
        layout.addView(button);
    }

    private void callLocalApi(String path, TextView statusView) {
        statusView.setText(getString(R.string.settings_calling_api, path));
        new Thread(() -> {
            String result;
            try {
                URL url = new URL("http://127.0.0.1:7333" + path);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(3000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        code >= 400 ? conn.getErrorStream() : conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                result = sb.toString();
            } catch (Exception e) {
                result = "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
            }

            String finalResult = result;
            runOnUiThread(() -> {
                statusView.setText(finalResult);
                Toast.makeText(this, R.string.settings_operation_done, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_SCRIPT || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        String fileName = resolveDisplayName(uri);
        new Thread(() -> {
            try {
                String savedName = scriptStore.importScript(getContentResolver(), uri, fileName);
                runOnUiThread(() -> {
                    statusView.setText(getString(R.string.settings_script_saved, savedName));
                    Toast.makeText(this, getString(R.string.settings_script_saved, savedName), Toast.LENGTH_SHORT).show();
                    refreshScriptList();
                });
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : getString(R.string.settings_select_js_file);
                runOnUiThread(() -> {
                    statusView.setText(message);
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void openScriptPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/javascript");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/javascript", "application/javascript", "text/plain"});
        startActivityForResult(intent, REQUEST_PICK_SCRIPT);
    }

    private void refreshScriptList() {
        if (scriptListContainer == null) {
            return;
        }
        scriptListContainer.removeAllViews();
        List<ScriptStore.ScriptEntry> scripts = scriptStore.listScripts();
        if (scripts.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText(R.string.settings_no_scripts);
            emptyView.setTextSize(14);
            emptyView.setPadding(0, 8, 0, 8);
            scriptListContainer.addView(emptyView);
            return;
        }
        for (ScriptStore.ScriptEntry entry : scripts) {
            scriptListContainer.addView(createScriptRow(entry));
        }
    }

    private LinearLayout createScriptRow(ScriptStore.ScriptEntry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        TextView nameView = new TextView(this);
        nameView.setText(entry.fileName);
        nameView.setTextSize(16);
        row.addView(nameView);

        TextView metaView = new TextView(this);
        metaView.setText(getString(
                R.string.settings_script_row_meta,
                formatTimestamp(entry.lastModified),
                formatFileSize(entry.sizeBytes)
        ));
        metaView.setTextSize(12);
        metaView.setPadding(0, dp(4), 0, dp(8));
        row.addView(metaView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(actions);

        actions.addView(createInlineButton(getString(R.string.settings_run_script), v -> runScript(entry.fileName)));
        actions.addView(createInlineButton(getString(R.string.settings_delete_script), v -> deleteScript(entry.fileName)));
        return row;
    }

    private Button createInlineButton(String text, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.rightMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private void deleteScript(String fileName) {
        boolean deleted = scriptStore.deleteScript(fileName);
        if (deleted) {
            statusView.setText(getString(R.string.settings_script_deleted, fileName));
            Toast.makeText(this, getString(R.string.settings_script_deleted, fileName), Toast.LENGTH_SHORT).show();
            refreshScriptList();
            return;
        }
        statusView.setText(getString(R.string.settings_script_delete_failed, fileName));
        Toast.makeText(this, getString(R.string.settings_script_delete_failed, fileName), Toast.LENGTH_SHORT).show();
    }

    private void runScript(String fileName) {
        OrbAccessibilityService service = OrbAccessibilityService.getActiveInstance();
        if (service == null) {
            statusView.setText(R.string.settings_accessibility_required);
            Toast.makeText(this, R.string.settings_accessibility_required, Toast.LENGTH_SHORT).show();
            return;
        }
        statusView.setText(getString(R.string.settings_script_running, fileName));
        new Thread(() -> {
            try {
                String scriptBody = scriptStore.readScript(fileName);
                TaskEnvelope task = new TaskEnvelope(
                        0L,
                        getString(R.string.manual_test_task_no),
                        "android",
                        "",
                        fileName,
                        scriptBody,
                        60000,
                        new JSONObject(),
                        "manual"
                );
                TaskResultPayload result = new TaskRunner().run(service, task, scriptBody);
                String summary = "SUCCESS".equalsIgnoreCase(result.status)
                        ? result.result
                        : result.errorMessage;
                String safeSummary = summary != null && !summary.trim().isEmpty() ? summary : result.status;
                runOnUiThread(() -> {
                    statusView.setText(getString(R.string.settings_script_result, safeSummary));
                    Toast.makeText(this, getString(R.string.settings_script_result, safeSummary), Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : "script failed";
                runOnUiThread(() -> {
                    statusView.setText(getString(R.string.settings_script_failed, message));
                    Toast.makeText(this, getString(R.string.settings_script_failed, message), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private String normalizeTaskCenterUrl(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!trimmed.contains("://")) {
            trimmed = "http://" + trimmed;
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String resolveDisplayName(Uri uri) {
        String path = uri != null ? uri.getLastPathSegment() : null;
        if (path == null || path.trim().isEmpty()) {
            return getString(R.string.manual_test_task_no) + ".js";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0L) {
            return getString(R.string.settings_unknown_time);
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(timestamp));
    }

    private String formatFileSize(long sizeBytes) {
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        }
        if (sizeBytes < 1024 * 1024) {
            return (sizeBytes / 1024) + " KB";
        }
        return String.format(Locale.getDefault(), "%.1f MB", sizeBytes / 1024f / 1024f);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        ));
    }
}
