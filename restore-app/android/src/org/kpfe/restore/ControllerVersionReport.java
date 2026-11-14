package org.kpfe.restore;

/** Pure interpretation of AYANEO's shared controller report, not a live UART query. */
final class ControllerVersionReport {
    final String version;
    final boolean available;
    final String reason;

    private ControllerVersionReport(String version, boolean available, String reason) {
        this.version = version;
        this.available = available;
        this.reason = reason;
    }

    static ControllerVersionReport unavailable(String reason) {
        return new ControllerVersionReport("unknown", false, reason);
    }

    static ControllerVersionReport fromCache(String raw, String updateZone, String working) {
        if (!"0".equals(updateZone) || !"1".equals(working)) {
            return unavailable("AYANEO has not reported the controller in normal working mode.");
        }
        if (raw == null) return unavailable("AYANEO has no controller version report.");
        // Accept surrounding text-file whitespace, but never silently discard NULs
        // or other control characters from a partial/invalid cache write.
        String value = raw.replaceAll("^[ \\t\\r\\n]+|[ \\t\\r\\n]+$", "");
        if ("-1".equals(value)) return unavailable("AYANEO is waiting for controller initialization.");
        if (!value.matches("[0-9A-Fa-f]{1,8}")) return unavailable("AYANEO's controller version report is malformed.");
        // Settings149 About uses hexadecimal only when A-F appears. GameWindow230
        // normally writes the decimal value decoded from a single response byte.
        int radix = value.matches(".*[A-Fa-f].*") ? 16 : 10;
        try {
            int version = Integer.parseInt(value, radix);
            if (version < 0 || version > 255) return unavailable("AYANEO's controller version report is outside the controller byte range.");
            return new ControllerVersionReport(Integer.toString(version), true,
                    "Cached controller report read from AYANEO; freshness is not independently verified.");
        } catch (NumberFormatException error) {
            return unavailable("AYANEO's controller version report is malformed.");
        }
    }
}
