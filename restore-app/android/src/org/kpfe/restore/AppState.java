package org.kpfe.restore;

import android.content.Context;
import android.content.SharedPreferences;

/** Process-wide observable state; the service owns the work, never the activity. */
final class AppState {
    static boolean initialized, busy, ready, holdScreenForRecovery, collectingReport;
    static String mode = "", status = "IDLE", title = "Ready when you are", message = "Check this device and grant root access to begin.";
    static int packet, total;
    static int selectedFirmwareVersion = 8, checkedFirmwareVersion;
    static String currentFirmwareVersion = "unknown", currentFirmwareVersionSource = "";
    static long currentFirmwareVersionObservedAtMillis;
    static long readyAt;
    static final long CHECK_TTL_MS = 300000;
    static final StringBuilder log = new StringBuilder();

    static synchronized void init(Context context) {
        if (initialized) return;
        initialized = true;
        SharedPreferences p = context.getSharedPreferences("restore", 0);
        int savedTarget = p.getInt("selectedFirmwareVersion", 8);
        selectedFirmwareVersion = DeviceIdentity.hint() == DeviceProfile.FIT
                ? FirmwareTarget.FIT_SUPPLIED : savedTarget == 9 ? 9 : 8;
        // A previous process's version observation is not a fresh device check.
        // The next root check populates the actual stock-reported version again.
        holdScreenForRecovery = p.getBoolean("holdScreenForRecovery", false);
        if (p.getInt("uiVersionCode", 0) != 7 && !p.getBoolean("busy", false) && !holdScreenForRecovery) {
            // Keep the full prior diagnostics, but do not present an old build's
            // result or obsolete instructions as this build's current action.
            p.edit().putInt("uiVersionCode", 7).putString("status", "IDLE")
                    .putString("title", "Ready when you are")
                    .putString("message", "Choose a stock firmware version, then check this device to begin.").commit();
        }
        if (p.getBoolean("busy", false)) {
            status = "INTERRUPTED";
            title = "Previous session interrupted";
            message = "The app stopped before recording a result. Check the controller before attempting another restore.";
        } else if (!p.getString("status", "IDLE").equals("IDLE")) {
            status = p.getString("status", "IDLE");
            title = p.getString("title", "Previous result");
            message = p.getString("message", "");
        }
        log.append(p.getString("log", ""));
    }

    static synchronized void persist(Context c) {
        c.getSharedPreferences("restore", 0).edit().putBoolean("busy", busy)
            .putInt("uiVersionCode", 7)
            .putInt("selectedFirmwareVersion", selectedFirmwareVersion)
            .putBoolean("holdScreenForRecovery", holdScreenForRecovery)
            .putString("status", status).putString("title", title).putString("message", message)
            .putString("log", log.toString()).commit();
    }

    static synchronized void append(String line) {
        if (log.length() > 100000) log.delete(0, log.length() - 80000);
        log.append(line).append('\n');
    }
}
