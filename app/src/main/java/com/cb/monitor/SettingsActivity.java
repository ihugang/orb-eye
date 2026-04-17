package com.cb.monitor;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.cb.monitor.storage.WorkerPrefs;
import com.cb.monitor.worker.WorkerForegroundService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class SettingsActivity extends Activity {
    private WorkerPrefs workerPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        workerPrefs = new WorkerPrefs(this);

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

        TextView status = new TextView(this);
        status.setText(R.string.settings_ready);
        status.setTextSize(14);
        status.setGravity(Gravity.START);
        layout.addView(status);

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

        addButton(layout, getString(R.string.settings_save_config), v -> {
            String raw = taskCenterInput.getText() != null ? taskCenterInput.getText().toString().trim() : "";
            String normalized = normalizeTaskCenterUrl(raw);
            if (normalized.isEmpty()) {
                status.setText(R.string.settings_invalid_task_center_url);
                Toast.makeText(this, R.string.settings_invalid_task_center_url, Toast.LENGTH_SHORT).show();
                return;
            }
            workerPrefs.setCenterBaseUrl(normalized);
            taskCenterInput.setText(normalized);
            status.setText(R.string.settings_config_saved);
            Toast.makeText(this, R.string.settings_config_saved, Toast.LENGTH_SHORT).show();
        });

        addButton(layout, getString(R.string.settings_open_accessibility), v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            status.setText(R.string.settings_opened_accessibility);
        });
        addButton(layout, getString(R.string.settings_start_worker), v -> {
            startService(WorkerForegroundService.createStartIntent(this));
            status.setText(R.string.settings_worker_started);
        });
        addButton(layout, getString(R.string.settings_stop_worker), v -> {
            stopService(WorkerForegroundService.createStartIntent(this));
            status.setText(R.string.settings_worker_stopped);
        });
        addButton(layout, getString(R.string.settings_start_inspector), v -> callLocalApi("/inspector/start?maxDepth=40", status));
        addButton(layout, getString(R.string.settings_show_controller), v -> callLocalApi("/inspector/controller/show", status));
        addButton(layout, getString(R.string.settings_refresh_inspector), v -> callLocalApi("/inspector/refresh?maxDepth=40", status));
        addButton(layout, getString(R.string.settings_stop_inspector), v -> callLocalApi("/inspector/stop", status));
        addButton(layout, getString(R.string.settings_stop_all_js), v -> callLocalApi("/exec/stop-all", status));
        addButton(layout, getString(R.string.settings_hide_controller), v -> callLocalApi("/inspector/controller/hide", status));

        setContentView(scrollView);
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
}
