package com.orb.eye;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.view.Gravity;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("👁️ Orb Eye");
        title.setTextSize(32);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView versionInfo = new TextView(this);
        versionInfo.setText(getVersionInfoText());
        versionInfo.setTextSize(13);
        versionInfo.setGravity(Gravity.CENTER);
        layout.addView(versionInfo);

        TextView desc = new TextView(this);
        desc.setText("\nAccessibility Service for Orb AI\n\nHTTP API on localhost:7333\n\nEnable the service in Settings → Accessibility → Orb Eye\n");
        desc.setTextSize(16);
        desc.setGravity(Gravity.CENTER);
        layout.addView(desc);

        TextView status = new TextView(this);
        status.setText("Inspector status: idle");
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        layout.addView(status);

        Button btn = new Button(this);
        btn.setText("Open Accessibility Settings");
        btn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
        layout.addView(btn);

        Button startInspector = new Button(this);
        startInspector.setText("Start Inspector");
        startInspector.setOnClickListener(v -> callLocalApi("/inspector/start?maxDepth=40", status));
        layout.addView(startInspector);

        Button showController = new Button(this);
        showController.setText("Show Floating Controller");
        showController.setOnClickListener(v -> callLocalApi("/inspector/controller/show", status));
        layout.addView(showController);

        Button refreshInspector = new Button(this);
        refreshInspector.setText("Refresh Inspector");
        refreshInspector.setOnClickListener(v -> callLocalApi("/inspector/refresh?maxDepth=40", status));
        layout.addView(refreshInspector);

        Button stopInspector = new Button(this);
        stopInspector.setText("Stop Inspector");
        stopInspector.setOnClickListener(v -> callLocalApi("/inspector/stop", status));
        layout.addView(stopInspector);

        Button hideController = new Button(this);
        hideController.setText("Hide Floating Controller");
        hideController.setOnClickListener(v -> callLocalApi("/inspector/controller/hide", status));
        layout.addView(hideController);

        setContentView(layout);
    }

    private void callLocalApi(String path, TextView statusView) {
        statusView.setText("Inspector status: calling " + path);
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
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                result = sb.toString();
            } catch (Exception e) {
                result = "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
            }

            String finalResult = result;
            runOnUiThread(() -> {
                statusView.setText("Inspector status: " + finalResult);
                Toast.makeText(this, "Inspector API done", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private String getVersionInfoText() {
        String version = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
        String buildTime = BuildConfig.BUILD_TIME_UTC;
        String installedAt = "unknown";
        try {
            PackageInfo packageInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageInfo = getPackageManager().getPackageInfo(
                        getPackageName(),
                        android.content.pm.PackageManager.PackageInfoFlags.of(0)
                );
            } else {
                packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            }
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            installedAt = fmt.format(new Date(packageInfo.lastUpdateTime));
        } catch (Exception ignored) {
            // keep fallback value
        }
        return "Version: " + version
                + "\nBuild No: " + BuildConfig.BUILD_NUMBER
                + "\nBuild: " + buildTime
                + "\nInstalled: " + installedAt;
    }
}
