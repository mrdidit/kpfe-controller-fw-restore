package org.kpfe.restore;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parsing/policy shared by root preflight and its offline tests. */
final class RootPolicy {
    private RootPolicy() { }

    static final class Battery {
        final int level;
        final boolean powered;
        Battery(int level, boolean powered) { this.level = level; this.powered = powered; }
    }

    static Battery requireBattery(String dump) throws IOException {
        Matcher levels = Pattern.compile("(?m)^\\s*level:\\s*(\\d+)\\s*$").matcher(dump);
        if (!levels.find()) throw new IOException("Cannot verify battery level.");
        int rawLevel;
        try { rawLevel = Integer.parseInt(levels.group(1)); }
        catch (NumberFormatException invalid) { throw new IOException("Invalid battery level.", invalid); }
        if (levels.find()) throw new IOException("Ambiguous battery level.");
        Matcher scales = Pattern.compile("(?m)^\\s*scale:\\s*(\\d+)\\s*$").matcher(dump);
        if (!scales.find()) throw new IOException("Cannot verify battery scale.");
        int scale;
        try { scale = Integer.parseInt(scales.group(1)); }
        catch (NumberFormatException invalid) { throw new IOException("Invalid battery scale.", invalid); }
        if (scales.find() || scale < 1 || rawLevel > scale) throw new IOException("Ambiguous or invalid battery scale/level.");
        int level = (int) ((long) rawLevel * 100 / scale);
        boolean powered = Pattern.compile("(?m)^\\s*(AC|USB|Wireless|Dock) powered:\\s*true\\s*$").matcher(dump).find();
        if (level < 30 && !(powered && level >= 15)) {
            throw new IOException("Charge to at least 30%, or connect power with at least 15% battery, before restoring.");
        }
        return new Battery(level, powered);
    }

    static boolean isSavedTermios(String state) {
        return state.length() <= 1024 && state.indexOf(':') >= 0 && state.matches("[0-9a-fA-F:]+");
    }

    static boolean isGameWindowActivity(String component) {
        return component.matches("com\\.ayaneo\\.gamewindow/[A-Za-z0-9_.$]+");
    }

    /** Independent transport allowlist: an entry test cannot send a firmware-data frame. */
    static String entryCommandKind(byte[] bytes) throws IOException {
        if (bytes.length == 11) {
            int[] allowed = {0xe7, 0, 0, 0, 0, 0, 0, 0, 7, 7, 0xed};
            boolean matches = true;
            for (int i = 0; i < allowed.length; i++) matches &= (bytes[i] & 255) == allowed[i];
            if (matches) return "boot-entry";
        }
        if (bytes.length == 64) {
            boolean matches = true;
            for (int i = 0; i < bytes.length; i++) matches &= (bytes[i] & 255) == (i == 3 ? 3 : 0);
            if (matches) return "quit";
        }
        throw new IOException("Entry-test transport rejected a command outside the exact boot-entry/quit allowlist; no bytes sent.");
    }

    /** Recovery exit cannot enter the loader or transmit any firmware payload. */
    static String exitCommandKind(byte[] bytes) throws IOException {
        if (bytes.length == 64) {
            boolean matches = true;
            for (int i = 0; i < bytes.length; i++) matches &= (bytes[i] & 255) == (i == 3 ? 3 : 0);
            if (matches) return "quit";
        }
        throw new IOException("Exit-only transport rejected a command outside the exact Quit allowlist; no bytes sent.");
    }

    static String boundedDiagnostic(String text) {
        return text.length() <= 512 ? text : text.substring(0, 497) + "... [truncated]";
    }

    static String commandFailure(String operation, int exitCode, String output) {
        return boundedDiagnostic(operation + " failed (exit " + exitCode + "): " + output.trim());
    }

    /** Historical broadcasts may contain error text belonging to unrelated commands. */
    static void requireBroadcastDump(int exitCode, String output) throws IOException {
        if (exitCode != 0 || !output.trim().startsWith("ACTIVITY MANAGER BROADCAST STATE (dumpsys activity broadcasts)")) {
            throw new IOException(commandFailure("Inspect GameWindow receiver registration", exitCode, output));
        }
    }

    /** Match a live registration, never a resolver entry or historical broadcast. */
    static boolean hasFinishReceiver(String dump, java.util.List<String> currentPids) {
        boolean registered = false;
        boolean ownerMatches = false;
        for (String line : dump.split("\\r?\\n")) {
            String text = line.trim();
            if (text.equals("Registered Receivers:")) { registered = true; continue; }
            if (!registered) continue;
            if (line.startsWith("  ") && !line.startsWith("    ") && !text.startsWith("* ReceiverList{") && !text.isEmpty()) return false;
            if (text.startsWith("* ReceiverList{")) { ownerMatches = false; continue; }
            if (text.startsWith("app=")) {
                for (String pid : currentPids) {
                    if (text.startsWith("app=" + pid + ":com.ayaneo.gamewindow/")) ownerMatches = true;
                }
            }
            if (ownerMatches && text.equals("Action: \"AYA_CONTROLLER_UPDATE_FINISH\"")) return true;
        }
        return false;
    }
}
