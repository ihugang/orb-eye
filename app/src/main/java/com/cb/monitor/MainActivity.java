package com.cb.monitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.cb.monitor.model.TaskEnvelope;
import com.cb.monitor.model.TaskResultPayload;
import com.cb.monitor.network.TaskCenterClient;
import com.cb.monitor.storage.ScriptStore;
import com.cb.monitor.storage.WorkerPrefs;
import com.cb.monitor.storage.WorkerStatsStore;
import com.cb.monitor.worker.TaskRunner;
import com.cb.monitor.worker.WorkerForegroundService;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PAGE_HORIZONTAL_PADDING_DP = 20;
    private static final int PAGE_TOP_PADDING_DP = 20;
    private static final int PAGE_BOTTOM_PADDING_DP = 28;
    private static final int PAGE_BACKGROUND = Color.parseColor("#F5F1E8");
    private static final int INK_PRIMARY = Color.parseColor("#1F1A17");
    private static final int INK_SECONDARY = Color.parseColor("#5B514A");
    private static final int CARD_BACKGROUND = Color.parseColor("#FFFDF8");
    private static final int BORDER_COLOR = Color.parseColor("#D9CBB8");
    private static final int HERO_BACKGROUND = Color.parseColor("#2F241C");
    private static final int HERO_ACCENT = Color.parseColor("#D89B2B");
    private static final int HERO_TEXT_PRIMARY = Color.parseColor("#FFF8ED");
    private static final int HERO_TEXT_SECONDARY = Color.parseColor("#F3E2C3");
    private static final int HERO_TEXT_MUTED = Color.parseColor("#E4C796");

    private TextView todayCompletedView;
    private TextView totalCompletedView;
    private TextView runtimeStatusView;
    private TextView runtimeStatusBadgeView;
    private TextView runtimeSignalView;
    private TextView serverStatusView;
    private TextView serverStatusBadgeView;
    private TextView serverSignalView;
    private TextView workerDeviceView;
    private TextView workerServerView;
    private TextView workerLastTaskView;
    private LinearLayout recentTasksContainer;
    private LinearLayout recentIssuesContainer;
    private TextView versionInfoView;
    private TextView heroStatusBadge;
    private TextView heroStatusSummary;
    private ScriptStore scriptStore;
    private WorkerStatsStore workerStatsStore;
    private WorkerPrefs workerPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        workerStatsStore = new WorkerStatsStore(this);
        workerPrefs = new WorkerPrefs(this);
        scriptStore = new ScriptStore(this);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(PAGE_BACKGROUND);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        applyRootPadding(page);
        scrollView.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        page.addView(createHeroCard());
        page.addView(createPrimaryAutomationButton());
        page.addView(createMetricsRow());
        page.addView(createSectionTitle(
                getString(R.string.main_section_runtime_title),
                getString(R.string.main_section_runtime_subtitle)
        ));
        page.addView(createRuntimeCard());
        page.addView(createSectionTitle(
                getString(R.string.main_section_server_title),
                getString(R.string.main_section_server_subtitle)
        ));
        page.addView(createServerHealthCard());

        page.addView(createSectionTitle(
                getString(R.string.main_section_tasks_title),
                getString(R.string.main_section_tasks_subtitle)
        ));
        recentTasksContainer = createCardContainer(page);

        page.addView(createSectionTitle(
                getString(R.string.main_section_issues_title),
                getString(R.string.main_section_issues_subtitle)
        ));
        recentIssuesContainer = createCardContainer(page);

        page.addView(createSectionTitle(
                getString(R.string.main_section_actions_title),
                getString(R.string.main_section_actions_subtitle)
        ));
        page.addView(createActionPanel());

        page.addView(createSectionTitle(
                getString(R.string.main_section_worker_title),
                getString(R.string.main_section_worker_subtitle)
        ));
        page.addView(createWorkerDetailsRow());

        versionInfoView = new TextView(this);
        versionInfoView.setTextColor(INK_SECONDARY);
        versionInfoView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        versionInfoView.setPadding(dp(2), dp(20), dp(2), 0);
        page.addView(versionInfoView);

        setContentView(scrollView);
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
        probeDeviceApprovalState();
    }

    private void applyRootPadding(LinearLayout page) {
        int horizontal = dp(PAGE_HORIZONTAL_PADDING_DP);
        int top = dp(PAGE_TOP_PADDING_DP);
        int bottom = dp(PAGE_BOTTOM_PADDING_DP);
        page.setPadding(horizontal, top, horizontal, bottom);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            page.setOnApplyWindowInsetsListener((view, insets) -> {
                int topInset = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    topInset = insets.getInsets(WindowInsets.Type.statusBars()).top;
                } else {
                    topInset = insets.getSystemWindowInsetTop();
                }
                view.setPadding(horizontal, top + topInset, horizontal, bottom);
                return insets;
            });
            page.requestApplyInsets();
        }
    }

    private LinearLayout createHeroCard() {
        LinearLayout hero = createCard(HERO_BACKGROUND, HERO_ACCENT, 1.4f);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView eyebrow = new TextView(this);
        eyebrow.setText(R.string.main_eyebrow);
        eyebrow.setTextColor(HERO_TEXT_MUTED);
        eyebrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        eyebrow.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        hero.addView(eyebrow);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, dp(10), 0, dp(6));
        hero.addView(titleRow);

        ImageView titleIcon = new ImageView(this);
        titleIcon.setImageResource(R.drawable.ic_launcher_foreground);
        LinearLayout.LayoutParams titleIconParams = new LinearLayout.LayoutParams(dp(30), dp(30));
        titleIconParams.rightMargin = dp(10);
        titleIcon.setLayoutParams(titleIconParams);
        titleRow.addView(titleIcon);

        TextView title = new TextView(this);
        title.setText(R.string.main_title);
        title.setTextColor(HERO_TEXT_PRIMARY);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        titleRow.addView(title);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(10), 0, 0);
        hero.addView(statusRow);

        heroStatusBadge = new TextView(this);
        heroStatusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        heroStatusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        heroStatusBadge.setTextColor(HERO_BACKGROUND);
        heroStatusBadge.setPadding(dp(14), dp(8), dp(14), dp(8));
        statusRow.addView(heroStatusBadge);

        heroStatusSummary = new TextView(this);
        heroStatusSummary.setTextColor(HERO_TEXT_PRIMARY);
        heroStatusSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        heroStatusSummary.setPadding(dp(12), 0, 0, 0);
        statusRow.addView(heroStatusSummary);

        return hero;
    }

    private LinearLayout createMetricsRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(16), 0, 0);

        todayCompletedView = createMetricTile(
                row,
                getString(R.string.metric_today),
                "0",
                HERO_ACCENT
        );
        totalCompletedView = createMetricTile(
                row,
                getString(R.string.metric_lifetime),
                "0",
                Color.parseColor("#18794E")
        );
        return row;
    }

    private LinearLayout createPrimaryAutomationButton() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(14), 0, 0);
        container.addView(createActionButton(
                getString(R.string.action_execute_js_automation),
                true,
                v -> showScriptExecuteDialog()
        ));
        return container;
    }

    private LinearLayout createRuntimeCard() {
        LinearLayout card = createStandaloneCardContainer();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(topRow);

        runtimeSignalView = createSignalIcon();
        topRow.addView(runtimeSignalView);

        runtimeStatusBadgeView = new TextView(this);
        runtimeStatusBadgeView.setText(R.string.status_unknown_upper);
        runtimeStatusBadgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        runtimeStatusBadgeView.setTypeface(Typeface.DEFAULT_BOLD);
        runtimeStatusBadgeView.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams runtimeBadgeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        runtimeBadgeParams.leftMargin = dp(10);
        runtimeStatusBadgeView.setLayoutParams(runtimeBadgeParams);
        topRow.addView(runtimeStatusBadgeView);

        TextView runtimeCaption = new TextView(this);
        runtimeCaption.setText(R.string.runtime_caption);
        runtimeCaption.setTextColor(INK_SECONDARY);
        runtimeCaption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        runtimeCaption.setPadding(dp(12), 0, 0, 0);
        topRow.addView(runtimeCaption);

        runtimeStatusView = new TextView(this);
        runtimeStatusView.setTextColor(INK_PRIMARY);
        runtimeStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        runtimeStatusView.setLineSpacing(0f, 1.18f);
        runtimeStatusView.setPadding(0, dp(14), 0, 0);
        card.addView(runtimeStatusView);

        return card;
    }

    private LinearLayout createServerHealthCard() {
        LinearLayout card = createStandaloneCardContainer();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(topRow);

        serverSignalView = createSignalIcon();
        topRow.addView(serverSignalView);

        serverStatusBadgeView = new TextView(this);
        serverStatusBadgeView.setText(R.string.status_unknown_upper);
        serverStatusBadgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        serverStatusBadgeView.setTypeface(Typeface.DEFAULT_BOLD);
        serverStatusBadgeView.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams serverBadgeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        serverBadgeParams.leftMargin = dp(10);
        serverStatusBadgeView.setLayoutParams(serverBadgeParams);
        topRow.addView(serverStatusBadgeView);

        TextView serverCaption = new TextView(this);
        serverCaption.setText(R.string.server_caption);
        serverCaption.setTextColor(INK_SECONDARY);
        serverCaption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        serverCaption.setPadding(dp(12), 0, 0, 0);
        topRow.addView(serverCaption);

        serverStatusView = new TextView(this);
        serverStatusView.setTextColor(INK_PRIMARY);
        serverStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        serverStatusView.setLineSpacing(0f, 1.18f);
        serverStatusView.setPadding(0, dp(14), 0, 0);
        card.addView(serverStatusView);

        return card;
    }

    private LinearLayout createWorkerDetailsRow() {
        LinearLayout column = createStandaloneCardContainer();
        column.setPadding(dp(16), dp(16), dp(16), dp(16));

        workerDeviceView = createDetailRow(column, getString(R.string.worker_device), "-");
        workerServerView = createDetailRow(column, getString(R.string.worker_task_center), "-");
        workerLastTaskView = createDetailRow(column, getString(R.string.worker_last_task), "-");
        return column;
    }

    private TextView createSignalIcon() {
        TextView icon = new TextView(this);
        icon.setGravity(Gravity.CENTER);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(28), dp(28));
        icon.setLayoutParams(params);
        return icon;
    }

    private TextView createMetricTile(LinearLayout parent, String label, String initialValue, int accent) {
        LinearLayout tile = createCard(CARD_BACKGROUND, BORDER_COLOR, 1f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = parent.getChildCount() == 0 ? dp(8) : 0;
        params.leftMargin = parent.getChildCount() == 0 ? 0 : dp(8);
        tile.setLayoutParams(params);
        tile.setPadding(dp(16), dp(16), dp(16), dp(16));
        parent.addView(tile);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(INK_SECONDARY);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        tile.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setText(initialValue);
        valueView.setTextColor(INK_PRIMARY);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        valueView.setPadding(0, dp(10), 0, dp(4));
        tile.addView(valueView);

        TextView accentBar = new TextView(this);
        accentBar.setBackgroundColor(accent);
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(4)
        );
        accentParams.topMargin = dp(8);
        accentBar.setLayoutParams(accentParams);
        tile.addView(accentBar);

        return valueView;
    }

    private TextView createDetailRow(LinearLayout parent, String label, String initialValue) {
        LinearLayout row = createCard(Color.parseColor("#FBF7EF"), BORDER_COLOR, 1f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        if (parent.getChildCount() > 0) {
            params.topMargin = dp(10);
        }
        row.setLayoutParams(params);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        parent.addView(row);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(INK_SECONDARY);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setText(initialValue);
        valueView.setTextColor(INK_PRIMARY);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        valueView.setLineSpacing(0f, 1.15f);
        valueView.setPadding(0, dp(10), 0, 0);
        row.addView(valueView);
        return valueView;
    }

    private LinearLayout createActionPanel() {
        LinearLayout card = createStandaloneCardContainer();
        card.addView(createActionButton(getString(R.string.action_refresh_status), false, v -> probeDeviceApprovalState()));
        card.addView(createActionButton(getString(R.string.action_execute_js_automation), true, v -> showScriptExecuteDialog()));
        card.addView(createActionButton(getString(R.string.action_accessibility_settings), true, v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        ));
        card.addView(createActionButton(getString(R.string.action_start_worker), true, v -> {
            startForegroundService(WorkerForegroundService.createStartIntent(this));
            refreshUi();
        }));
        card.addView(createActionButton(getString(R.string.action_stop_worker), false, v -> {
            stopService(WorkerForegroundService.createStartIntent(this));
            refreshUi();
        }));
        card.addView(createActionButton(getString(R.string.action_open_rpa_settings), false, v ->
                startActivity(new Intent(this, SettingsActivity.class))
        ));
        return card;
    }

    private void probeDeviceApprovalState() {
        String baseUrl = workerPrefs.getCenterBaseUrl().trim();
        String deviceId = workerPrefs.getDeviceId().trim();
        if (baseUrl.isEmpty() || deviceId.isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                TaskCenterClient client = new TaskCenterClient(workerPrefs);
                TaskCenterClient.DeviceState deviceState = client.fetchDeviceState();
                boolean changed = false;
                if ("APPROVED".equals(deviceState.approvalStatus)) {
                    workerStatsStore.markServerOnline(getString(R.string.worker_connected_waiting));
                    if (WorkerStatsStore.RUNTIME_PENDING_APPROVAL.equals(workerStatsStore.getRuntimeStatus())) {
                        workerStatsStore.markWorkerIdle(getString(R.string.worker_approval_cleared_message));
                    }
                    changed = true;
                } else if ("PENDING".equals(deviceState.approvalStatus)) {
                    workerStatsStore.markPendingApproval(getString(R.string.worker_pending_approval_message));
                    changed = true;
                }
                if (changed) {
                    runOnUiThread(this::refreshUi);
                }
            } catch (Exception ignored) {
                // Keep current cached state when the probe cannot reach task-center.
            }
        }, "cb-status-probe").start();
    }

    private void showScriptExecuteDialog() {
        List<ScriptStore.ScriptEntry> scripts = scriptStore.listScripts();
        if (scripts.isEmpty()) {
            Toast.makeText(this, R.string.settings_no_scripts, Toast.LENGTH_SHORT).show();
            return;
        }

        String[] fileNames = new String[scripts.size()];
        for (int i = 0; i < scripts.size(); i++) {
            fileNames[i] = scripts.get(i).fileName;
        }
        final int[] selectedIndex = {0};

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_execute_js_title)
                .setSingleChoiceItems(fileNames, 0, (dialog, which) -> selectedIndex[0] = which)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_execute_js_automation, (dialog, which) ->
                        executeSelectedScript(fileNames[selectedIndex[0]]))
                .show();
    }

    private void executeSelectedScript(String fileName) {
        OrbAccessibilityService service = OrbAccessibilityService.getActiveInstance();
        if (service == null) {
            Toast.makeText(this, R.string.settings_accessibility_required, Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, getString(R.string.settings_script_running, fileName), Toast.LENGTH_SHORT).show();
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
                runOnUiThread(() ->
                        Toast.makeText(this, getString(R.string.settings_script_result, safeSummary), Toast.LENGTH_LONG).show()
                );
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : "script failed";
                runOnUiThread(() ->
                        Toast.makeText(this, getString(R.string.settings_script_failed, message), Toast.LENGTH_LONG).show()
                );
            }
        }, "cb-run-js").start();
    }

    private Button createActionButton(String label, boolean filled, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(filled ? HERO_BACKGROUND : INK_PRIMARY);
        button.setBackground(createRoundedDrawable(
                filled ? Color.parseColor("#F1DEC0") : CARD_BACKGROUND,
                filled ? HERO_ACCENT : BORDER_COLOR,
                filled ? 1.5f : 1f
        ));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(10);
        button.setLayoutParams(params);
        button.setPadding(dp(14), dp(14), dp(14), dp(14));
        button.setOnClickListener(listener);
        return button;
    }

    private TextView createInfoCard(LinearLayout parent) {
        TextView view = new TextView(this);
        view.setTextColor(INK_PRIMARY);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setLineSpacing(0f, 1.18f);
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        view.setBackground(createRoundedDrawable(CARD_BACKGROUND, BORDER_COLOR, 1f));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(6);
        parent.addView(view, params);
        return view;
    }

    private LinearLayout createCardContainer(LinearLayout parent) {
        LinearLayout container = createStandaloneCardContainer();
        if (parent != null) {
            parent.addView(container);
        }
        return container;
    }

    private LinearLayout createStandaloneCardContainer() {
        LinearLayout container = createCard(CARD_BACKGROUND, BORDER_COLOR, 1f);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(16), dp(16), dp(8));
        return container;
    }

    private TextView createSectionTitle(String title, String subtitle) {
        TextView section = new TextView(this);
        section.setText(title);
        section.setTextColor(INK_PRIMARY);
        section.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setLineSpacing(0f, 1.15f);
        section.setPadding(dp(2), dp(18), dp(2), dp(10));
        return section;
    }

    private LinearLayout createCard(int fill, int stroke, float strokeWidthDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(createRoundedDrawable(fill, stroke, strokeWidthDp));
        return card;
    }

    private GradientDrawable createRoundedDrawable(int fill, int stroke, float strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(18));
        drawable.setStroke(Math.max(1, dp(strokeWidthDp)), stroke);
        return drawable;
    }

    private void refreshUi() {
        todayCompletedView.setText(String.valueOf(workerStatsStore.getTodayCompleted()));
        totalCompletedView.setText(String.valueOf(workerStatsStore.getTotalCompleted()));

        bindRuntimeCard();
        bindServerStatusCard();
        bindRecentTasksCard();
        bindRecentIssuesCard();

        bindWorkerDetailCards();

        versionInfoView.setText(getVersionInfoText());
    }

    private String getVersionInfoText() {
        String version = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
        String buildTime = BuildConfig.BUILD_TIME_UTC;
        String installedAt = getString(R.string.version_unknown);
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
            // Keep fallback value.
        }
        return getString(R.string.version_info, version, BuildConfig.BUILD_NUMBER, buildTime, installedAt);
    }

    private void bindRuntimeCard() {
        String runtimeStatus = workerStatsStore.getRuntimeStatus();
        String message = workerStatsStore.getRuntimeMessage();
        String headline;
        String guidance;
        int badgeFill;
        int badgeText;

        switch (runtimeStatus) {
            case WorkerStatsStore.RUNTIME_IDLE:
                headline = getString(R.string.status_idle);
                guidance = getString(R.string.runtime_guidance_idle);
                badgeFill = Color.parseColor("#E4F3EA");
                badgeText = Color.parseColor("#18794E");
                break;
            case WorkerStatsStore.RUNTIME_RUNNING:
                headline = getString(R.string.status_running);
                guidance = getString(R.string.runtime_guidance_running);
                badgeFill = Color.parseColor("#E7F0FE");
                badgeText = Color.parseColor("#1A73E8");
                break;
            case WorkerStatsStore.RUNTIME_WAITING_ACCESSIBILITY:
                headline = getString(R.string.status_waiting_accessibility);
                guidance = getString(R.string.runtime_guidance_waiting_accessibility);
                badgeFill = Color.parseColor("#FCE8E6");
                badgeText = Color.parseColor("#C5221F");
                break;
            case WorkerStatsStore.RUNTIME_PENDING_APPROVAL:
                headline = getString(R.string.status_pending_approval);
                guidance = getString(R.string.runtime_guidance_pending_approval);
                badgeFill = Color.parseColor("#FDF0D4");
                badgeText = Color.parseColor("#B25E09");
                break;
            case WorkerStatsStore.RUNTIME_ERROR:
                headline = getString(R.string.status_error);
                guidance = getString(R.string.runtime_guidance_error);
                badgeFill = Color.parseColor("#F3E8FD");
                badgeText = Color.parseColor("#8E4EC6");
                break;
            default:
                headline = getString(R.string.status_unknown);
                guidance = getString(R.string.runtime_guidance_unknown);
                badgeFill = Color.parseColor("#ECEFF1");
                badgeText = Color.parseColor("#5F6368");
                break;
        }

        runtimeStatusBadgeView.setText(headline.toUpperCase(Locale.ROOT));
        runtimeStatusBadgeView.setTextColor(badgeText);
        runtimeStatusBadgeView.setBackground(createRoundedDrawable(badgeFill, badgeFill, 1f));
        bindSignalIcon(runtimeSignalView, runtimeStatusToIcon(headline), badgeText);
        if (WorkerStatsStore.RUNTIME_IDLE.equals(runtimeStatus)) {
            runtimeStatusView.setText(getString(R.string.label_detail) + "\n" + message);
        } else {
            runtimeStatusView.setText(
                    headline
                            + "\n\n" + guidance
                            + "\n\n" + getString(R.string.label_detail) + "\n" + message
            );
        }
        runtimeStatusView.setTextColor(INK_PRIMARY);
    }

    private void bindWorkerDetailCards() {
        List<WorkerStatsStore.RecentTaskEntry> recentTasks = workerStatsStore.getRecentTasks();
        WorkerStatsStore.RecentTaskEntry latestTask = recentTasks.isEmpty() ? null : recentTasks.get(0);
        String deviceSummary = getString(
                R.string.worker_summary,
                Build.BRAND,
                Build.MODEL,
                workerPrefs.getDeviceId()
        );
        String taskCenterSummary = getString(
                R.string.server_summary,
                extractHost(workerPrefs.getCenterBaseUrl()),
                workerPrefs.getNodeSecret().isEmpty()
                        ? getString(R.string.worker_mode_direct)
                        : getString(R.string.worker_mode_compatible),
                workerPrefs.getPollIntervalMs()
        );
        String lastTaskSummary;
        if (latestTask == null) {
            lastTaskSummary = getString(R.string.no_task_yet);
        } else {
            lastTaskSummary = getString(
                    R.string.last_task_summary,
                    latestTask.taskNo,
                    getTaskStatusLabel(latestTask.status),
                    WorkerStatsStore.formatTimestamp(latestTask.timestamp),
                    latestTask.detail
            );
        }

        workerDeviceView.setText(deviceSummary);
        workerServerView.setText(taskCenterSummary);
        workerLastTaskView.setText(lastTaskSummary);
    }

    private void bindServerStatusCard() {
        String status = workerStatsStore.getServerStatus();
        String message = workerStatsStore.getServerMessage();
        String updatedAt = formatUpdatedAt(workerStatsStore.getLastUpdatedAt());

        String headline;
        String guidance;
        int badgeFill;
        int badgeText;

        switch (status) {
            case WorkerStatsStore.STATUS_ONLINE:
                headline = getString(R.string.stats_connected);
                guidance = getString(R.string.server_guidance_online);
                badgeFill = Color.parseColor("#E4F3EA");
                badgeText = Color.parseColor("#18794E");
                break;
            case WorkerStatsStore.STATUS_PENDING_APPROVAL:
                headline = getString(R.string.status_pending_approval);
                guidance = getString(R.string.server_guidance_pending_approval);
                badgeFill = Color.parseColor("#FDF0D4");
                badgeText = Color.parseColor("#B25E09");
                break;
            case WorkerStatsStore.STATUS_OFFLINE:
                headline = getString(R.string.status_offline);
                guidance = getString(R.string.server_guidance_offline);
                badgeFill = Color.parseColor("#FCE8E6");
                badgeText = Color.parseColor("#C5221F");
                break;
            case WorkerStatsStore.STATUS_ERROR:
                headline = getString(R.string.status_error);
                guidance = getString(R.string.server_guidance_error);
                badgeFill = Color.parseColor("#F3E8FD");
                badgeText = Color.parseColor("#8E4EC6");
                break;
            default:
                headline = getString(R.string.status_unknown);
                guidance = getString(R.string.server_guidance_unknown);
                badgeFill = Color.parseColor("#ECEFF1");
                badgeText = Color.parseColor("#5F6368");
                break;
        }

        heroStatusBadge.setText(headline.toUpperCase(Locale.ROOT));
        heroStatusBadge.setBackground(createRoundedDrawable(badgeText, badgeText, 1f));
        heroStatusSummary.setText(getString(R.string.last_update, updatedAt));

        serverStatusBadgeView.setText(headline.toUpperCase(Locale.ROOT));
        serverStatusBadgeView.setTextColor(badgeText);
        serverStatusBadgeView.setBackground(createRoundedDrawable(badgeFill, badgeFill, 1f));
        bindSignalIcon(serverSignalView, serverStatusToIcon(headline), badgeText);
        if (WorkerStatsStore.STATUS_ONLINE.equals(status)) {
            serverStatusView.setText(getString(R.string.label_updated) + "\n" + updatedAt);
        } else {
            serverStatusView.setText(
                    headline
                            + "\n\n" + guidance
                            + "\n\n" + getString(R.string.label_detail) + "\n" + message
                            + "\n\n" + getString(R.string.label_updated) + "\n" + updatedAt
            );
        }
        serverStatusView.setTextColor(INK_PRIMARY);
    }

    private void bindRecentTasksCard() {
        recentTasksContainer.removeAllViews();
        List<WorkerStatsStore.RecentTaskEntry> recentTasks = workerStatsStore.getRecentTasks();
        if (recentTasks.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText(R.string.empty_recent_tasks);
            emptyView.setTextColor(INK_SECONDARY);
            emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            emptyView.setLineSpacing(0f, 1.15f);
            recentTasksContainer.addView(emptyView);
            return;
        }

        for (int i = 0; i < recentTasks.size(); i++) {
            recentTasksContainer.addView(createRecentTaskRow(recentTasks.get(i), i));
        }
    }

    private void bindRecentIssuesCard() {
        recentIssuesContainer.removeAllViews();
        List<WorkerStatsStore.RecentIssueEntry> recentIssues = workerStatsStore.getRecentIssues();
        if (recentIssues.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText(R.string.empty_recent_issues);
            emptyView.setTextColor(INK_SECONDARY);
            emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            emptyView.setLineSpacing(0f, 1.15f);
            recentIssuesContainer.addView(emptyView);
            return;
        }

        for (int i = 0; i < recentIssues.size(); i++) {
            recentIssuesContainer.addView(createRecentIssueRow(recentIssues.get(i), i));
        }
    }

    private LinearLayout createRecentTaskRow(WorkerStatsStore.RecentTaskEntry task, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(12));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView marker = new TextView(this);
        marker.setText(String.valueOf(index + 1));
        marker.setGravity(Gravity.CENTER);
        marker.setTextColor(Color.WHITE);
        marker.setTypeface(Typeface.DEFAULT_BOLD);
        marker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        marker.setBackground(createRoundedDrawable(resolveTaskStatusColor(task.status), resolveTaskStatusColor(task.status), 1f));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        markerParams.topMargin = dp(2);
        marker.setLayoutParams(markerParams);
        row.addView(marker);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(10));
        body.setBackground(createRoundedDrawable(Color.parseColor("#FBF7EF"), BORDER_COLOR, 1f));
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        bodyParams.leftMargin = dp(12);
        row.addView(body, bodyParams);

        TextView topLine = new TextView(this);
        topLine.setText(task.taskNo + "  ·  " + getTaskStatusLabel(task.status));
        topLine.setTextColor(INK_PRIMARY);
        topLine.setTypeface(Typeface.DEFAULT_BOLD);
        topLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        body.addView(topLine);

        TextView detailView = new TextView(this);
        detailView.setText(task.detail);
        detailView.setTextColor(INK_SECONDARY);
        detailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        detailView.setPadding(0, dp(4), 0, dp(4));
        body.addView(detailView);

        TextView timeView = new TextView(this);
        timeView.setText(WorkerStatsStore.formatTimestamp(task.timestamp));
        timeView.setTextColor(Color.parseColor("#7A6E64"));
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.addView(timeView);

        return row;
    }

    private LinearLayout createRecentIssueRow(WorkerStatsStore.RecentIssueEntry issue, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(12));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView marker = new TextView(this);
        marker.setText(issueCategoryToIcon(issue.category));
        marker.setGravity(Gravity.CENTER);
        marker.setTextColor(Color.WHITE);
        marker.setTypeface(Typeface.DEFAULT_BOLD);
        marker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        marker.setBackground(createRoundedDrawable(resolveIssueCategoryColor(issue.category), resolveIssueCategoryColor(issue.category), 1f));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        markerParams.topMargin = dp(2);
        marker.setLayoutParams(markerParams);
        row.addView(marker);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(10));
        body.setBackground(createRoundedDrawable(Color.parseColor("#FBF7EF"), BORDER_COLOR, 1f));
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        bodyParams.leftMargin = dp(12);
        row.addView(body, bodyParams);

        TextView topLine = new TextView(this);
        topLine.setText(getString(
                R.string.recent_issue_title,
                index + 1,
                issue.title,
                getIssueCategoryLabel(issue.category)
        ));
        topLine.setTextColor(INK_PRIMARY);
        topLine.setTypeface(Typeface.DEFAULT_BOLD);
        topLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        body.addView(topLine);

        TextView detailView = new TextView(this);
        detailView.setText(issue.detail);
        detailView.setTextColor(INK_SECONDARY);
        detailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        detailView.setPadding(0, dp(4), 0, dp(4));
        body.addView(detailView);

        TextView timeView = new TextView(this);
        timeView.setText(WorkerStatsStore.formatTimestamp(issue.timestamp));
        timeView.setTextColor(Color.parseColor("#7A6E64"));
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.addView(timeView);

        return row;
    }

    private int resolveTaskStatusColor(String status) {
        if ("SUCCESS".equalsIgnoreCase(status)) {
            return Color.parseColor("#18794E");
        }
        if ("FAILED".equalsIgnoreCase(status)) {
            return Color.parseColor("#C5221F");
        }
        if ("RUNNING".equalsIgnoreCase(status)) {
            return Color.parseColor("#1A73E8");
        }
        return Color.parseColor("#5F6368");
    }

    private int resolveIssueCategoryColor(String category) {
        if ("SERVER".equalsIgnoreCase(category)) {
            return Color.parseColor("#8E4EC6");
        }
        if ("WORKER".equalsIgnoreCase(category)) {
            return Color.parseColor("#C5221F");
        }
        if ("ACCESSIBILITY".equalsIgnoreCase(category)) {
            return Color.parseColor("#C5221F");
        }
        if ("APPROVAL".equalsIgnoreCase(category)) {
            return Color.parseColor("#B25E09");
        }
        return Color.parseColor("#5F6368");
    }

    private String issueCategoryToIcon(String category) {
        if ("SERVER".equalsIgnoreCase(category)) {
            return "S";
        }
        if ("WORKER".equalsIgnoreCase(category)) {
            return "W";
        }
        if ("ACCESSIBILITY".equalsIgnoreCase(category)) {
            return "A";
        }
        if ("APPROVAL".equalsIgnoreCase(category)) {
            return "P";
        }
        return "!";
    }

    private String formatUpdatedAt(long updatedAt) {
        if (updatedAt <= 0L) {
            return getString(R.string.time_never);
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return fmt.format(new Date(updatedAt));
    }

    private void bindSignalIcon(TextView iconView, String iconText, int fillColor) {
        if (iconView == null) {
            return;
        }
        iconView.setText(iconText);
        iconView.setBackground(createRoundedDrawable(fillColor, fillColor, 1f));
    }

    private String runtimeStatusToIcon(String headline) {
        if (getString(R.string.status_running).equalsIgnoreCase(headline)) {
            return "▶";
        }
        if (getString(R.string.status_idle).equalsIgnoreCase(headline)) {
            return "•";
        }
        if (getString(R.string.status_waiting_accessibility).equalsIgnoreCase(headline)) {
            return "!";
        }
        if (getString(R.string.status_pending_approval).equalsIgnoreCase(headline)) {
            return "…";
        }
        if (getString(R.string.status_error).equalsIgnoreCase(headline)) {
            return "×";
        }
        return "?";
    }

    private String serverStatusToIcon(String headline) {
        if (getString(R.string.stats_connected).equalsIgnoreCase(headline)) {
            return "↗";
        }
        if (getString(R.string.status_pending_approval).equalsIgnoreCase(headline)) {
            return "…";
        }
        if (getString(R.string.status_offline).equalsIgnoreCase(headline)) {
            return "↓";
        }
        if (getString(R.string.status_error).equalsIgnoreCase(headline)) {
            return "×";
        }
        return "?";
    }

    private String getTaskStatusLabel(String status) {
        if ("SUCCESS".equalsIgnoreCase(status)) {
            return getString(R.string.task_status_success);
        }
        if ("FAILED".equalsIgnoreCase(status)) {
            return getString(R.string.task_status_failed);
        }
        if ("RUNNING".equalsIgnoreCase(status)) {
            return getString(R.string.task_status_running);
        }
        return getString(R.string.task_status_unknown);
    }

    private String getIssueCategoryLabel(String category) {
        if ("SERVER".equalsIgnoreCase(category)) {
            return getString(R.string.issue_category_server);
        }
        if ("WORKER".equalsIgnoreCase(category)) {
            return getString(R.string.issue_category_worker);
        }
        if ("ACCESSIBILITY".equalsIgnoreCase(category)) {
            return getString(R.string.issue_category_accessibility);
        }
        if ("APPROVAL".equalsIgnoreCase(category)) {
            return getString(R.string.issue_category_approval);
        }
        return getString(R.string.issue_category_general);
    }

    private String extractHost(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.trim().isEmpty()) {
                return rawUrl;
            }
            return port > 0 ? host + ":" + port : host;
        } catch (Exception ignored) {
            return rawUrl;
        }
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        ));
    }
}
