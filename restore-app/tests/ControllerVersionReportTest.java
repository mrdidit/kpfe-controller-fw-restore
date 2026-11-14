package org.kpfe.restore;

import java.util.Locale;

public final class ControllerVersionReportTest {
    private static int checks;

    public static void main(String[] args) {
        for (int version = 0; version < 256; version++) {
            known(Integer.toString(version), Integer.toString(version));
            known(" \t" + version + "\r\n", Integer.toString(version));
            String hex = Integer.toHexString(version);
            if (hex.matches(".*[a-f].*")) {
                known(hex, Integer.toString(version));
                known(hex.toUpperCase(Locale.ROOT), Integer.toString(version));
            }
        }
        known("08", "8");
        known("09", "9");
        known("10", "10"); // Numeric-only cache values are decimal, matching stock About.
        known("FF", "255");
        known("00000008", "8");
        String[] bad = {null, "", " ", "-1", "-8", "+8", "v8", "0x08", "8.0", "8 9", "8\n9",
                "8\u0000", "\u00008", "\u0000", "256", "99999999", "FFFFFFFF", "100000000", "8\u0085", "８", "unknown", "null"};
        for (String raw : bad) unknown(raw, "0", "1");
        String[] flags = {null, "", "null", "unknown", "0", "1", "true", "false", "2", " 0", "1\n"};
        for (String updating : flags) for (String working : flags) {
            if (!"0".equals(updating) || !"1".equals(working)) unknown("8", updating, working);
        }
        ControllerVersionReport unavailable = ControllerVersionReport.unavailable("Read failed");
        check(!unavailable.available && "unknown".equals(unavailable.version), "read failure remains unknown");
        check("Read failed".equals(unavailable.reason), "read failure reason preserved");
        System.out.println("ControllerVersionReportTest: " + checks + " checks passed");
    }

    private static void known(String raw, String expected) {
        ControllerVersionReport report = ControllerVersionReport.fromCache(raw, "0", "1");
        check(report.available, "expected available: " + raw);
        check(expected.equals(report.version), "wrong version: " + raw + " -> " + report.version);
        check(report.reason.contains("freshness is not independently verified"), "cache is not presented as live");
    }

    private static void unknown(String raw, String updateZone, String working) {
        ControllerVersionReport report = ControllerVersionReport.fromCache(raw, updateZone, working);
        check(!report.available, "invalid report must be unavailable");
        check("unknown".equals(report.version), "unknown report must not retain a target or cached version");
        check(!report.reason.isEmpty(), "unknown report needs a reason");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
