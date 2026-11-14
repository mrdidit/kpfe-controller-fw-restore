package org.kpfe.restore;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.os.PowerManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.kpfe.restore.core.FirmwareImage;

/** Work survives Activity recreation; no automated retry or background restore. */
public final class RestoreService extends Service {
    private static final String CHANNEL = "restore";
    private PowerManager.WakeLock wakeLock;
    private volatile Process rootProcess;
    private volatile boolean resultSeen;
    private volatile boolean destroyed;
    private String lastNotificationText = "";
    private SessionTrace sessionTrace;
    private boolean traceWarningReported;
    private boolean inheritedRecoveryHold;
    private final Object sessionLock = new Object();

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        AppState.init(this);
        // Every startForegroundService request must be promoted, even when a
        // stale confirmation or duplicate request is rejected below.
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Controller restoration", NotificationManager.IMPORTANCE_LOW));
        Notification n = notification("Checking root and device");
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        final String mode = intent == null ? "" : intent.getStringExtra("mode");
        final int targetVersion = intent == null ? 0 : intent.getIntExtra("targetVersion", 0);
        final FirmwareImage.Descriptor target;
        synchronized (AppState.class) {
            if (AppState.busy) return START_NOT_STICKY;
            if (!"check".equals(mode) && !"restore".equals(mode) && !"entry-test".equals(mode) && !"exit-only".equals(mode) && !"resume-only".equals(mode)) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY;
            }
            try { target = FirmwareImage.forVersion(Integer.toString(targetVersion)); }
            catch (IllegalArgumentException invalid) {
                fail("FAILED", "Choose one of the included stock firmware versions.");
                AppState.persist(this);
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY;
            }
            if (targetVersion != AppState.selectedFirmwareVersion) {
                fail("NEEDS_CHECK", "The selected firmware changed. Check the selected version again before continuing.");
                AppState.persist(this);
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY;
            }
            if (AppState.holdScreenForRecovery && !mode.equals("check") && !mode.equals("restore")) {
                AppState.title = "Firmware state unresolved";
                AppState.message = "Keep power connected, this app visible and the screen on. An exit or screen reset could leave incomplete firmware unable to start.";
                AppState.persist(this);
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY;
            }
            if (!"check".equals(mode) && !"resume-only".equals(mode) && (!AppState.ready
                    || AppState.checkedFirmwareVersion != targetVersion
                    || SystemClock.elapsedRealtime() - AppState.readyAt > AppState.CHECK_TTL_MS)) {
                AppState.ready = false; AppState.title = "Check again first";
                AppState.message = "Run a fresh device check before continuing.";
                AppState.status = "NEEDS_CHECK"; AppState.persist(this);
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY;
            }
            AppState.busy = true; AppState.ready = false; AppState.mode = mode;
            AppState.checkedFirmwareVersion = 0;
            AppState.currentFirmwareVersion = "unknown";
            AppState.currentFirmwareVersionSource = "";
            AppState.currentFirmwareVersionObservedAtMillis = 0;
            inheritedRecoveryHold = AppState.holdScreenForRecovery;
            // Persist before starting a firmware-capable helper. An interrupted
            // restore must not let our own UI silently allow display sleep.
            if (mode.equals("restore")) AppState.holdScreenForRecovery = true;
            AppState.packet = 0; AppState.total = 0;
            AppState.title = mode.equals("restore") ? "Preparing restore" : mode.equals("entry-test") ? "Testing update-mode entry" : mode.equals("exit-only") ? "Exiting update mode" : mode.equals("resume-only") ? "Resuming AYANEO controls" : "Checking your Elite";
            AppState.message = "Grant access when your root manager asks.";
            AppState.status = "RUNNING";
            AppState.log.setLength(0);
            AppState.persist(this);
        }
        wakeLock = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kpfe:restore");
        // Stock transfer can keep waiting for a reply. Do not silently release
        // CPU wake protection while that session is still active.
        if (mode.equals("restore")) wakeLock.acquire();
        else wakeLock.acquire(10 * 60 * 1000L);
        new Thread(() -> run(mode, target), "kpfe-controller-restore").start();
        return START_NOT_STICKY;
    }

    private void run(String mode, FirmwareImage.Descriptor target) {
        resultSeen = false;
        sessionTrace = null;
        traceWarningReported = false;
        lastNotificationText = "";
        try {
            File traceDirectory = new File(getFilesDir(), "session-traces");
            if (!traceDirectory.isDirectory() && !traceDirectory.mkdirs())
                throw new IOException("Could not prepare session diagnostics. No controller action started.");
            if (mode.equals("restore") && traceDirectory.getUsableSpace() < 32L * 1024 * 1024)
                throw new IOException("Free at least 32 MB of internal storage for the restore diagnostics.");
            File traceFile = File.createTempFile("session-" + mode + "-", ".log", traceDirectory);
            sessionTrace = new SessionTrace(traceFile);
            sessionTrace.append("KPFE Controller Firmware Restore 0.4.2-preview; mode=" + mode
                    + "; targetVersion=" + target.version + "; trace=" + traceFile.getName());
            if (!sessionTrace.failure().isEmpty()) throw new IOException(sessionTrace.failure());
            AppState.append("Full session trace: " + traceFile.getAbsolutePath());
            getSharedPreferences("restore", 0).edit().putString("lastTracePath", traceFile.getAbsolutePath())
                    .putBoolean("lastTraceComplete", false).putString("lastTraceFailure", "").commit();
            if (!"PocketFITElite".equals(Build.DEVICE) || !"Pocket FIT Elite".equals(Build.MODEL))
                throw new IOException("This app supports Pocket FIT Elite only. Detected: " + Build.MODEL + " / " + Build.DEVICE);
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) throw new IOException("Could not read battery level.");
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            boolean plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            int percent = scale > 0 && level >= 0 ? level * 100 / scale : -1;
            if (percent < 0 || percent < (plugged ? 15 : 30))
                throw new IOException("Charge to 30%, or connect power with at least 15% charge, before restoring.");
            AppState.append("Device: " + Build.MODEL + " / " + Build.DEVICE + "; battery " + percent + "%");
            File firmware = prepareFirmware(target);
            File nativeLibrary = new File(getApplicationInfo().nativeLibraryDir, "libserial_port.so");
            if (!nativeLibrary.isFile()) throw new IOException("The stock serial library is missing. Reinstall this build.");
            String command = "exec /system/bin/app_process -cp " + quote(getApplicationInfo().sourceDir)
                + " / org.kpfe.restore.RootMain " + mode + " " + quote(firmware.getCanonicalPath())
                + " " + quote(nativeLibrary.getCanonicalPath()) + " " + quote(target.version);
            synchronized (sessionLock) {
                if (destroyed) throw new IOException("The service stopped before starting the helper.");
                rootProcess = startRoot(command);
            }
            // A check may time out while waiting for a denied/ignored root prompt. Never
            // kill an active helper mid-write. Entry/exit waits have deadlines;
            // stock data-response waits have no automatic abort. Blocking native
            // I/O cannot promise a hard wall-clock limit either.
            final Process process = rootProcess;
            synchronized (sessionLock) {
                if (destroyed) { process.getOutputStream().close(); throw new IOException("The service stopped before authorizing the helper."); }
                if (!mode.equals("check")) {
                    process.getOutputStream().write("START\n".getBytes(StandardCharsets.US_ASCII));
                    process.getOutputStream().flush();
                }
            }
            if (mode.equals("check")) new Thread(() -> {
                try {
                    if (!process.waitFor(90, TimeUnit.SECONDS)) {
                        process.destroy();
                        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                    }
                }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "root-check-timeout").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) receive(line, mode, target);
            }
            int exit = process.waitFor();
            AppState.append("Root helper exit: " + exit);
            if (sessionTrace != null) sessionTrace.append("Root helper exit: " + exit);
            if (!resultSeen) throw new IOException(mode.equals("check")
                ? "Root access was not granted, timed out, or the helper could not start. See diagnostics and allow Controller FW Restore in your root manager."
                : "The helper exited without a confirmed result. Do not assume restoration completed; check the controller and diagnostics.");
            if (exit != 0) {
                synchronized (AppState.class) {
                    if (AppState.ready || AppState.status.equals("SUCCESS")) {
                        if (mode.equals("restore")) AppState.holdScreenForRecovery = true;
                        fail("INDETERMINATE", "Helper exited unexpectedly after its result. Check diagnostics.");
                    }
                }
            } else if (mode.equals("restore")) {
                synchronized (AppState.class) {
                    if (AppState.status.equals("SUCCESS")) AppState.holdScreenForRecovery = false;
                }
            }
        } catch (Exception e) {
            AppState.append(e.toString());
            if (sessionTrace != null) sessionTrace.append(e.toString());
            if (mode.equals("restore")) {
                synchronized (AppState.class) {
                    AppState.holdScreenForRecovery = rootProcess == null ? inheritedRecoveryHold : true;
                }
            }
            fail(mode.equals("check") ? "FAILED" : "INDETERMINATE", e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            Process process = rootProcess;
            if (process != null) try { process.getOutputStream().close(); } catch (IOException ignored) { }
            if (sessionTrace != null) {
                sessionTrace.close();
                reportTraceWarning();
                String failure = sessionTrace.failure();
                // Preserve capture quality even if its first warning aged out of
                // the UI ring log. It is separate from the controller result.
                getSharedPreferences("restore", 0).edit().putBoolean("lastTraceComplete", failure.isEmpty())
                        .putString("lastTraceFailure", failure).commit();
                AppState.append(failure.isEmpty() ? "Full session trace saved and synchronized." : failure);
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                synchronized (sessionLock) { rootProcess = null; }
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                synchronized (AppState.class) { AppState.busy = false; AppState.persist(this); }
            });
        }
    }

    private Process startRoot(String command) throws IOException {
        String[] candidates = {"/debug_ramdisk/su", "/sbin/su", "/system/bin/su", "/system/xbin/su", "su"};
        for (String su : candidates) {
            if (su.startsWith("/") && !new File(su).canExecute()) continue;
            try { return new ProcessBuilder(su, "-c", command).redirectErrorStream(true).start(); }
            catch (IOException e) { if (su.equals("su")) throw e; }
        }
        throw new IOException("No root manager found. This app needs an already-rooted device.");
    }

    private File prepareFirmware(FirmwareImage.Descriptor target) throws Exception {
        File dest = new File(getFilesDir(), target.assetName);
        File tmp = new File(getFilesDir(), "firmware.tmp");
        final byte[] bytes;
        try (InputStream input = getAssets().open(target.assetName)) {
            bytes = FirmwareImage.read(target, input).copyBytes();
        }
        try (FileOutputStream output = new FileOutputStream(tmp)) {
            output.write(bytes);
            output.getFD().sync();
        }
        if (!tmp.renameTo(dest)) { tmp.delete(); throw new IOException("Could not prepare verified firmware."); }
        AppState.append("Bundled firmware v" + target.version + " verified: " + bytes.length + " bytes; SHA-256 " + target.sha256);
        return dest;
    }

    private void receive(String line, String mode, FirmwareImage.Descriptor target) {
        if (sessionTrace != null) { sessionTrace.append(line); reportTraceWarning(); }
        AppState.append(line);
        Log.i("EliteRestore", line);
        try {
            JSONObject e = new JSONObject(line);
            synchronized (AppState.class) {
                if (e.optString("event").equals("current-firmware-version")) {
                    AppState.currentFirmwareVersion = e.optBoolean("available", false) ? e.optString("version", "unknown") : "unknown";
                    AppState.currentFirmwareVersionSource = e.optString("source", "");
                    AppState.currentFirmwareVersionObservedAtMillis = e.optLong("observedAtMillis", 0);
                    // Source metadata belongs in diagnostics, not the operation status.
                    return;
                }
                if (e.has("message")) AppState.message = e.getString("message");
                if (e.has("packetCount")) AppState.total = e.optInt("packetCount", 0);
                if (e.has("packetIndex") && e.optInt("packetIndex", -1) >= 0) AppState.packet = e.optInt("packetIndex", 0);
                String phase = e.optString("phase", "");
                if (!phase.isEmpty()) { AppState.title = titleFor(phase); AppState.message = messageFor(phase, mode); }
                if (e.optString("event").equals("result")) {
                    resultSeen = true;
                    AppState.status = e.optString("status", "INDETERMINATE");
                    if (mode.equals("restore")) {
                        // Success clears the hold only after clean helper exit.
                        if (AppState.status.equals("FAILED")
                                || (e.has("flashMayHaveStarted") && !e.optBoolean("flashMayHaveStarted", true)))
                            AppState.holdScreenForRecovery = inheritedRecoveryHold;
                    }
                    if (e.has("packetsAcknowledged")) AppState.packet = e.optInt("packetsAcknowledged", 0);
                    AppState.ready = mode.equals("check") && AppState.status.equals("CHECK_OK");
                    if (AppState.ready) {
                        AppState.readyAt = SystemClock.elapsedRealtime();
                        AppState.checkedFirmwareVersion = Integer.parseInt(target.version);
                        AppState.title = "Ready to restore v" + target.version;
                        AppState.message = "Root, device and firmware checks passed.\nRestore starts after your confirmation.";
                    }
                    else if (AppState.status.equals("SUCCESS")) {
                        if (mode.equals("resume-only")) { AppState.title = "AYANEO controls reinitialized"; AppState.message = "The stock display off/on sequence completed and normal controller state was observed. Android was not rebooted. Check all buttons and sticks now."; }
                        else if (mode.equals("exit-only")) { AppState.title = "Startup message received"; AppState.message = "The installed firmware's startup message was received and stock initialization completed. No firmware data was sent. Check the buttons, sticks and AYANEO quick menu now."; }
                        else if (mode.equals("entry-test")) { AppState.title = "Entry test completed"; AppState.message = "Update-mode and application startup messages were received, then stock initialization completed. No firmware data was sent. Check the buttons, sticks and AYANEO quick menu now."; }
                        else {
                            AppState.title = "v" + target.version + " transfer completed";
                            AppState.message = "The selected stock image was sent, the controller restarted and normal initialization completed. Check the reported version, sticks, buttons and RGB now.";
                            if (e.optBoolean("versionMismatch", false)) {
                                AppState.title = "Check the reported firmware version";
                                AppState.message = "The transfer sequence completed for v" + target.version
                                        + ", but AYANEO currently reports v" + e.optString("reportedFirmwareVersion", "unknown")
                                        + ". Its report may be stale. Check stock Settings and the diagnostics; no retry has been started.";
                            }
                        }
                    }
                    else { AppState.title = mode.equals("resume-only") ? "AYANEO initialization not confirmed" : mode.equals("exit-only") ? "Controller restart not confirmed" : mode.equals("entry-test") ? "Entry test not confirmed" : mode.equals("check") ? "Check needs attention" : "Restoration not confirmed"; }
                    AppState.persist(this);
                }
                if (!AppState.title.equals(lastNotificationText)) {
                    lastNotificationText = AppState.title;
                    getSystemService(NotificationManager.class).notify(1, notification(AppState.title));
                }
            }
        } catch (Exception ignored) { /* su/ART diagnostics are retained without becoming success. */ }
    }

    private String titleFor(String phase) {
        String p = phase.toUpperCase(java.util.Locale.ROOT);
        if (p.equals("WAITING_FOR_RESPONSE_DELAYED")) return "Waiting for the controller";
        if (p.equals("REINITIALIZING")) return "Reinitializing AYANEO controls";
        if (p.equals("SENDING") || p.equals("WAITING_FOR_RESPONSE") || p.contains("WRITE") || p.contains("TRANSFER") || p.contains("PACKET")) return "Restoring stock firmware";
        if (p.contains("BOOT") || p.contains("ENTER")) return "Entering update mode";
        if (p.contains("QUIT") || p.contains("RESTART") || p.contains("APPLICATION")) return "Waiting for the controller";
        if (p.contains("CLEAN") || p.contains("RESUME")) return "Restoring normal operation";
        return "Checking your Elite";
    }

    private String messageFor(String phase, String mode) {
        if (phase.equals("WAITING_FOR_RESPONSE_DELAYED")) return "The controller has not replied yet. Keep power connected and leave this app open. The current packet is not being retried.";
        if (phase.equals("REINITIALIZING")) return "The display turns off for five seconds, then wakes to restore normal controls. Android is not being rebooted.";
        if (phase.equals("CLEANUP")) return "Returning control to the stock AYANEO software.";
        if (phase.equals("WAITING_FOR_BOOTLOADER")) return "Waiting up to ten seconds for the controller's update-mode message. No firmware data is sent before it arrives.";
        if (mode.equals("exit-only")) return "Requesting a return to the installed firmware. No firmware data will be sent.";
        if (mode.equals("entry-test")) return "Testing entry and return only. No firmware data will be sent.";
        if (phase.equals("SENDING") || phase.equals("WAITING_FOR_RESPONSE")) return "Keep power connected, this app visible and the screen on.";
        if (phase.equals("WAITING_FOR_APPLICATION")) return "Waiting for the controller to restart with its installed firmware.";
        if (phase.equals("VALIDATING")) return "Verifying the included Elite stock image.";
        return "Preparing the controller. No controller-button presses are needed.";
    }

    private void fail(String status, String message) {
        synchronized (AppState.class) { AppState.ready = false; AppState.status = status; AppState.title = "Action needs attention"; AppState.message = message; }
    }
    private void reportTraceWarning() {
        if (!traceWarningReported && sessionTrace != null && !sessionTrace.failure().isEmpty()) {
            traceWarningReported = true;
            AppState.append(sessionTrace.failure());
            Log.w("EliteRestore", sessionTrace.failure());
        }
    }
    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private Notification notification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_restore).setContentTitle("Controller FW Restore").setContentText(text).setContentIntent(pi).setOngoing(true).build();
    }
    @Override public void onDestroy() {
        // Closing stdin asks a live helper to cancel cooperatively and clean up.
        synchronized (sessionLock) {
            destroyed = true;
            Process p = rootProcess;
            if (p != null) try { p.getOutputStream().close(); } catch (IOException ignored) { }
        }
        super.onDestroy();
    }
}
