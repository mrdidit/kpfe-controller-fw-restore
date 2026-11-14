package org.kpfe.restore;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;
import org.kpfe.restore.core.FirmwareImage;

/** Small framework-only UI: no accounts, storage permission or network dependency. */
public final class MainActivity extends Activity {
    private int BG, CARD, SECONDARY, INK, MUTED, ACCENT;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView heading, detail, badge, progressText, installedVersion, versionChecked, targetDetail;
    private ProgressBar progress;
    private Button action, recheck, diagnosticEntry, diagnosticExit, diagnosticRecovery;
    private TextView diagnosticHint;
    private AlertDialog diagnosticsMenu;
    private RadioGroup targetChoice;
    private RadioButton targetV8, targetV9;
    private boolean renderingSelection;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 250); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BG = getColor(R.color.app_background); CARD = getColor(R.color.app_card);
        SECONDARY = getColor(R.color.app_secondary);
        INK = getColor(R.color.app_ink); MUTED = getColor(R.color.app_muted); ACCENT = getColor(R.color.app_accent);
        AppState.init(this);
        final boolean wide = getResources().getConfiguration().screenWidthDp >= 700;
        FrameLayout scene = new FrameLayout(this);
        scene.setBackgroundColor(BG);
        ImageView wallpaper = new ImageView(this);
        wallpaper.setImageResource(R.drawable.bduck_background);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setAlpha(0.35f);
        wallpaper.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scene.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
        View paintedFrame = new View(this);
        paintedFrame.setBackground(PaintedDrawable.frame());
        paintedFrame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scene.addView(paintedFrame, new FrameLayout.LayoutParams(-1, -1));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scene.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout outer = column();
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(outer);
        outer.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int edge = dp(wide ? 16 : 24);
            v.setPadding(edge + safe.left, edge + safe.top, edge + safe.right, edge + safe.bottom);
            return insets;
        });
        LinearLayout content = column();
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(wide ? 32 : 48), dp(wide ? 1000 : 620));
        outer.addView(content, new LinearLayout.LayoutParams(width, -2));
        FrameLayout title = new FrameLayout(this);
        title.setClipChildren(false);
        title.setClipToPadding(false);
        View splash = new View(this);
        splash.setBackground(PaintedDrawable.title());
        splash.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        splash.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        title.addView(splash, new FrameLayout.LayoutParams(Math.min(width, dp(520)), dp(64), Gravity.CENTER));
        TextView hero = text("KPFE Controller Firmware Restore", 20, 0xff25261d);
        hero.setTypeface(null, Typeface.BOLD);
        hero.setGravity(Gravity.CENTER);
        hero.setMaxLines(1);
        hero.setPadding(dp(20), 0, dp(20), 0);
        hero.setAutoSizeTextTypeUniformWithConfiguration(14, 20, 1, TypedValue.COMPLEX_UNIT_SP);
        hero.setShadowLayer(dp(3), 0, dp(2), 0x66000000);
        title.addView(hero, new FrameLayout.LayoutParams(-1, -1));
        content.addView(title, new LinearLayout.LayoutParams(-1, dp(58)));
        LinearLayout sections = column();
        add(content, sections, 12);
        LinearLayout identity = column(), controls = column();
        if (wide) {
            sections.setOrientation(LinearLayout.HORIZONTAL);
            sections.addView(identity, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
            cp.leftMargin = dp(20);
            sections.addView(controls, cp);
        } else {
            sections.addView(identity);
            add(sections, controls, 18);
        }

        LinearLayout firmware = card();
        add(identity, firmware, 0);
        LinearLayout installed = new LinearLayout(this);
        installed.setOrientation(LinearLayout.HORIZONTAL);
        installed.setGravity(Gravity.CENTER_VERTICAL);
        installed.addView(text("Installed firmware", 14, MUTED), new LinearLayout.LayoutParams(0, -2, 1f));
        installedVersion = text("Not checked", wide ? 20 : 22, INK);
        installedVersion.setTypeface(null, Typeface.BOLD);
        installed.addView(installedVersion, new LinearLayout.LayoutParams(-2, -2));
        firmware.addView(installed);
        versionChecked = text("Run a device check to read the installed version.", 12, MUTED);
        add(firmware, versionChecked, 4);
        View divider = new View(this);
        divider.setBackgroundColor(MUTED); divider.setAlpha(0.2f);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.topMargin = dp(12); dividerParams.bottomMargin = dp(12);
        firmware.addView(divider, dividerParams);
        TextView ft = text("Firmware to restore", 16, INK);
        ft.setTypeface(null, Typeface.BOLD);
        firmware.addView(ft);
        targetChoice = new RadioGroup(this);
        targetChoice.setOrientation(LinearLayout.HORIZONTAL);
        targetV8 = targetOption("Version 8"); targetV9 = targetOption("Version 9");
        targetChoice.addView(targetV8, new RadioGroup.LayoutParams(0, -2, 1f));
        targetChoice.addView(targetV9, new RadioGroup.LayoutParams(0, -2, 1f));
        add(firmware, targetChoice, 4);
        targetDetail = text("", 12, MUTED);
        add(firmware, targetDetail, 2);
        View batteryDivider = new View(this);
        batteryDivider.setBackgroundColor(MUTED); batteryDivider.setAlpha(0.2f);
        LinearLayout.LayoutParams batteryDividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        batteryDividerParams.topMargin = dp(14);
        firmware.addView(batteryDivider, batteryDividerParams);
        add(firmware, text("Root required. Battery: 30%, or 15% with charger connected.", 10.5f, MUTED), 12);
        targetChoice.setOnCheckedChangeListener((group, checked) -> {
            if (renderingSelection) return;
            int selected = checked == targetV9.getId() ? 9 : 8;
            synchronized (AppState.class) {
                if (AppState.busy) { render(); return; }
                if (selected == AppState.selectedFirmwareVersion) return;
                AppState.selectedFirmwareVersion = selected;
                AppState.ready = false;
                AppState.status = "NEEDS_CHECK";
                AppState.title = "Target firmware changed";
                AppState.message = "Check this device before restoring version " + selected + ".";
                AppState.persist(this);
            }
            render();
        });

        LinearLayout statusCard = card();
        if (wide) statusCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        add(controls, statusCard, 0);
        badge = text("ROOT REQUIRED", 12, ACCENT);
        badge.setLetterSpacing(0.08f);
        statusCard.addView(badge);
        heading = text("Ready when you are", wide ? 20 : 22, INK);
        heading.setTypeface(null, Typeface.BOLD);
        add(statusCard, heading, wide ? 8 : 12);
        detail = text("", 15, MUTED);
        add(statusCard, detail, wide ? 6 : 8);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        add(statusCard, progress, 18);
        progressText = text("", 13, MUTED);
        add(statusCard, progressText, 7);

        action = button("Check root & device", ACCENT, BG);
        add(controls, action, 6);
        action.setOnClickListener(v -> {
            boolean ready;
            synchronized (AppState.class) { if (AppState.busy) return; ready = AppState.ready; }
            if (ready) confirmRestore(); else begin("check");
        });
        recheck = button("Check again", ACCENT, BG);
        recheck.setOnClickListener(v -> begin("check"));
        add(controls, recheck, 5);
        Button diagnostics = button("Diagnostics", ACCENT, BG);
        diagnostics.setOnClickListener(v -> showDiagnostics());
        add(controls, diagnostics, 5);
        setContentView(scene);
        render();
    }

    private void confirmRestore() {
        final int target;
        final String installed;
        synchronized (AppState.class) {
            if (AppState.busy) return;
            target = AppState.selectedFirmwareVersion;
            installed = installedVersionLabel();
        }
        String evidence = target == 9 ? "Restoring v9 through this app has not yet been tested. "
                : "The v8 restore and v9 → v8 paths were tested with app 0.3. ";
        new AlertDialog.Builder(this).setTitle("Restore controller firmware v" + target + "?")
            .setMessage("AYANEO-reported installed firmware: " + installed + "\nFirmware to write: stock v" + target
                + "\n\nKeep power connected and leave this app visible with the screen on until its final display off/on step. Do not close the app or restart during transfer. A delayed reply keeps the app waiting without retrying.\n\n"
                + evidence + "Android is not rebooted.")
            .setNegativeButton("Cancel", null).setPositiveButton("Restore v" + target, (d, w) -> begin("restore", target)).show();
    }

    private void begin(String mode) {
        final int target;
        synchronized (AppState.class) { target = AppState.selectedFirmwareVersion; }
        begin(mode, target);
    }

    private void begin(String mode, int target) {
        synchronized (AppState.class) {
            if (AppState.busy) return;
            if (AppState.holdScreenForRecovery && !mode.equals("check") && !mode.equals("restore")) {
                new AlertDialog.Builder(this).setTitle("Firmware state unresolved")
                    .setMessage("Exit and reinitialization are unavailable while a restore may be incomplete. Keep power connected and this app visible.")
                    .setPositiveButton("Close", null).show();
                return;
            }
            if (target != AppState.selectedFirmwareVersion || (target != 8 && target != 9)
                    || (!mode.equals("check") && !mode.equals("resume-only")
                    && (!AppState.ready || target != AppState.checkedFirmwareVersion
                    || SystemClock.elapsedRealtime() - AppState.readyAt > AppState.CHECK_TTL_MS))) {
                AppState.ready = false; AppState.status = "NEEDS_CHECK";
                AppState.title = "Check again first";
                AppState.message = "Run a fresh device check before continuing.";
                AppState.persist(this); render(); return;
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        Intent i = new Intent(this, RestoreService.class).putExtra("mode", mode).putExtra("targetVersion", target);
        try { startForegroundService(i); }
        catch (RuntimeException e) { new AlertDialog.Builder(this).setMessage("Could not start: " + e.getMessage()).setPositiveButton("OK", null).show(); }
    }

    private void render() {
        synchronized (AppState.class) {
            if (!AppState.busy && AppState.ready && SystemClock.elapsedRealtime() - AppState.readyAt > AppState.CHECK_TTL_MS) {
                AppState.ready = false; AppState.title = "Check again first"; AppState.message = "Device checks expire after five minutes. Run a fresh check before continuing.";
            }
            heading.setText(AppState.title);
            installedVersion.setText(installedVersionLabel());
            versionChecked.setText(AppState.currentFirmwareVersionObservedAtMillis > 0
                    ? "Reported by AYANEO · checked " + DateFormat.getTimeInstance(DateFormat.SHORT)
                        .format(new Date(AppState.currentFirmwareVersionObservedAtMillis))
                    : "Run a device check to read the installed version.");
            renderingSelection = true;
            targetChoice.check(AppState.selectedFirmwareVersion == 9 ? targetV9.getId() : targetV8.getId());
            renderingSelection = false;
            targetV8.setEnabled(!AppState.busy); targetV9.setEnabled(!AppState.busy);
            FirmwareImage.Descriptor selectedImage = FirmwareImage.forVersion(Integer.toString(AppState.selectedFirmwareVersion));
            targetDetail.setText("Verified stock image · " + NumberFormat.getIntegerInstance().format(selectedImage.size) + " bytes");
            String description = AppState.message.length() > 1600 ? AppState.message.substring(0, 1600) + "… See diagnostics." : AppState.message;
            if (!AppState.busy && AppState.holdScreenForRecovery)
                description += "\n\nFirmware may be incomplete. Keep power connected, this app visible and the screen on. Do not reboot or try an exit/reset while the recovery state is unresolved.";
            detail.setText(description);
            badge.setText(AppState.busy ? "IN PROGRESS" : AppState.ready ? "CHECKS PASSED" : "ROOTED ELITE ONLY");
            action.setEnabled(!AppState.busy);
            action.setText(AppState.busy ? (AppState.mode.equals("restore") ? "Restoring…" : AppState.mode.equals("check") ? "Checking…" : "Working…") : AppState.ready ? "Restore firmware v" + AppState.selectedFirmwareVersion : "Check root & device");
            recheck.setVisibility(AppState.ready && !AppState.busy ? View.VISIBLE : View.GONE);
            refreshDiagnosticActions();
            progress.setVisibility(AppState.busy ? View.VISIBLE : View.GONE);
            progressText.setVisibility(AppState.busy ? View.VISIBLE : View.GONE);
            progress.setIndeterminate(AppState.total <= 0);
            if (AppState.total > 0) { progress.setMax(AppState.total); progress.setProgress(AppState.packet); }
            progressText.setText(AppState.total > 0 ? AppState.packet + " / " + AppState.total + " packets" : "No controller buttons needed");
            if (AppState.busy || AppState.holdScreenForRecovery) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void showDiagnostics() {
        LinearLayout panel = column();
        panel.setPadding(dp(20), dp(12), dp(20), dp(16));
        diagnosticEntry = button("Test update mode", ACCENT, BG);
        diagnosticExit = button("Exit update mode", ACCENT, BG);
        diagnosticRecovery = button("Reinitialize AYANEO controls", ACCENT, BG);
        diagnosticEntry.setMinHeight(dp(48));
        diagnosticExit.setMinHeight(dp(48));
        diagnosticRecovery.setMinHeight(dp(48));
        panel.addView(diagnosticEntry);
        add(panel, diagnosticExit, 6);
        add(panel, diagnosticRecovery, 6);
        diagnosticHint = text("", 13, MUTED);
        add(panel, diagnosticHint, 8);
        Button logs = button("View logs", ACCENT, BG);
        logs.setMinHeight(dp(48));
        add(panel, logs, 10);
        ScrollView sc = new ScrollView(this); sc.addView(panel);
        final AlertDialog menu = new AlertDialog.Builder(this).setTitle("Diagnostics").setView(sc)
            .setPositiveButton("Close", null).create();
        diagnosticsMenu = menu;
        menu.setOnDismissListener(d -> {
            diagnosticsMenu = null; diagnosticEntry = null; diagnosticExit = null;
            diagnosticRecovery = null; diagnosticHint = null;
        });
        diagnosticEntry.setOnClickListener(v -> { menu.dismiss(); confirmDiagnosticAction("entry-test"); });
        diagnosticExit.setOnClickListener(v -> { menu.dismiss(); confirmDiagnosticAction("exit-only"); });
        diagnosticRecovery.setOnClickListener(v -> { menu.dismiss(); confirmDiagnosticAction("resume-only"); });
        logs.setOnClickListener(v -> { menu.dismiss(); showLog(); });
        menu.show();
        refreshDiagnosticActions();
    }

    private void refreshDiagnosticActions() {
        if (diagnosticsMenu == null || diagnosticEntry == null) return;
        synchronized (AppState.class) {
            boolean recoveryAllowed = !AppState.busy && !AppState.holdScreenForRecovery;
            boolean checked = hasFreshTargetCheck();
            // An idle tap can explain a missing check; it must never silently do nothing.
            diagnosticEntry.setEnabled(recoveryAllowed);
            diagnosticExit.setEnabled(recoveryAllowed);
            diagnosticRecovery.setEnabled(recoveryAllowed);
            diagnosticHint.setText(AppState.busy ? "A controller session is running. Logs remain available."
                    : AppState.holdScreenForRecovery ? "Update-mode and reinitialization actions are unavailable while firmware state is unresolved."
                    : !checked ? "A fresh device check is needed. Tap Test or Exit to check first."
                    : "Update-mode actions do not write firmware.");
        }
    }

    /** Read only while holding AppState's monitor; begin() checks again after confirmation. */
    private boolean hasFreshTargetCheck() {
        return AppState.ready && AppState.selectedFirmwareVersion == AppState.checkedFirmwareVersion
                && SystemClock.elapsedRealtime() - AppState.readyAt <= AppState.CHECK_TTL_MS;
    }

    private void confirmDiagnosticAction(String mode) {
        final int target;
        final boolean needsCheck;
        final String blockedReason;
        synchronized (AppState.class) {
            target = AppState.selectedFirmwareVersion;
            blockedReason = AppState.busy ? "A controller session is running. Wait for it to finish."
                    : AppState.holdScreenForRecovery ? "Firmware state is unresolved. Keep power connected and the app visible. Update-mode and reinitialization actions are unavailable."
                    : null;
            needsCheck = !mode.equals("resume-only") && !hasFreshTargetCheck();
        }
        if (blockedReason != null) {
            new AlertDialog.Builder(this).setTitle("Action unavailable").setMessage(blockedReason)
                .setPositiveButton("Close", null).show();
            return;
        }
        if (needsCheck) {
            new AlertDialog.Builder(this).setTitle("Check device first")
                .setMessage("Run a fresh check for the selected firmware, then choose Test or Exit again. Changing the selected firmware or waiting more than five minutes requires another check.\n\nCheck now only checks the device; the update-mode action still needs your separate confirmation.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Check now", (d, w) -> begin("check", target)).show();
            return;
        }
        if (mode.equals("entry-test")) {
            new AlertDialog.Builder(this).setTitle("Test entry without flashing?")
                .setMessage("Temporarily enter update mode, then return to the installed firmware after entry is confirmed. No firmware data will be sent.\n\nControls may pause during the test. After the controller restarts, the display turns off for five seconds and wakes to restore normal controls. Android is not rebooted.")
                .setNegativeButton("Cancel", null).setPositiveButton("Test entry", (d, w) -> begin(mode, target)).show();
        } else if (mode.equals("exit-only")) {
            new AlertDialog.Builder(this).setTitle("Return to installed firmware?")
                .setMessage("Send the stock exit command once, then wait for the controller to restart. No update-entry command or firmware data will be sent.\n\nAfter startup is confirmed, the display turns off for five seconds and wakes to restore normal controls. Android is not rebooted.")
                .setNegativeButton("Cancel", null).setPositiveButton("Send exit", (d, w) -> begin(mode, target)).show();
        } else if (mode.equals("resume-only")) {
            new AlertDialog.Builder(this).setTitle("Reinitialize AYANEO controls?")
                .setMessage("Turn the display off for five seconds, then wake it so the stock AYANEO software reinitializes the controller and extra buttons. No firmware update is started, and Android is not rebooted.")
                .setNegativeButton("Cancel", null).setPositiveButton("Reinitialize", (d, w) -> begin(mode, target)).show();
        }
    }

    private void showLog() {
        final String log;
        synchronized (AppState.class) { log = "KPFE Controller Firmware Restore 0.4.2-preview\n" + AppState.status + "\n" + AppState.message
                + "\n\nSelected target: v" + AppState.selectedFirmwareVersion
                + "\nInstalled firmware: " + installedVersionLabel()
                + "\nVersion source: " + AppState.currentFirmwareVersionSource
                + "\nObserved at (epoch ms): " + AppState.currentFirmwareVersionObservedAtMillis
                + "\n\nRecent diagnostics (full trace is saved privately):\n" + AppState.log
                + "\nFull session trace: " + getSharedPreferences("restore", 0).getString("lastTracePath", "None")
                + "\nTrace complete: " + getSharedPreferences("restore", 0).getBoolean("lastTraceComplete", false)
                + "\n" + getSharedPreferences("restore", 0).getString("lastTraceFailure", "");
        }
        LinearLayout panel = column();
        panel.setPadding(dp(20), dp(12), dp(20), dp(16));
        panel.addView(text("Recovery from damaged firmware remains unproven. A completed restore does not include flash readback.", 13, MUTED));
        TextView body = text(log, 12, INK);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        add(panel, body, 16);
        ScrollView sc = new ScrollView(this); sc.addView(panel);
        new AlertDialog.Builder(this).setTitle("Diagnostic log").setView(sc)
            .setPositiveButton("Close", null).setNeutralButton("Copy", (d, w) -> ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Controller FW Restore diagnostics", log)))
            .setNegativeButton("Share", (d, w) -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log), "Share diagnostics"))).show();
    }

    /** A target choice never substitutes for an observation of the installed version. */
    private String installedVersionLabel() {
        String value = AppState.currentFirmwareVersion;
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("unknown") || value.equalsIgnoreCase("unavailable"))
            return AppState.currentFirmwareVersionObservedAtMillis > 0 ? "Unavailable" : "Not checked";
        return value.matches("[0-9]+") ? "v" + value : value;
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private TextView text(String value, float size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setLineSpacing(dp(3), 1f); return v; }
    private LinearLayout card() { LinearLayout v = column(); v.setPadding(dp(16), dp(16), dp(16), dp(16)); v.setBackground(PaintedDrawable.panel(CARD)); return v; }
    private RadioButton targetOption(String label) { RadioButton b = new RadioButton(this); b.setId(View.generateViewId()); b.setText(label); b.setTextColor(INK); b.setTextSize(16); b.setMinHeight(dp(52)); b.setButtonTintList(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {android.R.attr.state_checked}, new int[] {}}, new int[] {MUTED, ACCENT, MUTED})); return b; }
    private Button button(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {0xff8b8c90, fg}));
        b.setTextSize(15); b.setAllCaps(false);
        b.setMinHeight(dp(38)); b.setMinimumHeight(dp(38));
        b.setPadding(dp(12), 0, dp(12), 0); b.setStateListAnimator(null);
        StateListDrawable fill = new StateListDrawable();
        fill.addState(new int[] {-android.R.attr.state_enabled}, PaintedDrawable.button(SECONDARY));
        fill.addState(new int[] {}, PaintedDrawable.button(bg));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22808080), fill, PaintedDrawable.button(0xffffffff)));
        return b;
    }
    private void add(LinearLayout parent, View child, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); parent.addView(child, p); }
}
