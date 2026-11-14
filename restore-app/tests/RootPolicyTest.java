package org.kpfe.restore;

import java.io.IOException;

/** Host-only parsing tests: no Android runtime, UART, command or process actions. */
public final class RootPolicyTest {
    private static int checks;
    private static String dump(int level, int scale, boolean powered) {
        return "Current Battery Service state:\n  AC powered: false\n  USB powered: " + powered
                + "\n  Wireless powered: false\n  level: " + level + "\n  scale: " + scale + "\n";
    }
    private static void expect(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("check " + checks);
    }
    private static void rejects(String text) throws Exception {
        try { RootPolicy.requireBattery(text); throw new AssertionError("Unsafe battery accepted"); }
        catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        expect(RootPolicy.requireBattery(dump(30, 100, false)).level == 30);
        rejects(dump(29, 100, false));
        expect(RootPolicy.requireBattery(dump(15, 100, true)).powered);
        rejects(dump(14, 100, true));
        expect(RootPolicy.requireBattery(dump(30, 200, true)).level == 15);
        rejects(dump(29, 200, true));
        rejects(dump(101, 100, true));
        rejects(dump(0, 0, true));
        rejects("USB powered: true\nscale: 100\n");
        rejects("USB powered: true\nlevel: 80\n");
        rejects(dump(80, 100, true) + "level: 14\n");
        rejects(dump(80, 100, true) + "scale: 200\n");
        rejects("level: 99999999999999999999999999\nscale: 100\n");
        rejects(dump(-1, 100, true));
        expect(RootPolicy.requireBattery(dump(Integer.MAX_VALUE, Integer.MAX_VALUE, false)).level == 100);
        expect(RootPolicy.isSavedTermios("500:5:18b2:8a3b:3:1c:7f:15:4:0:1:0:11:13:1a:0"));
        expect(!RootPolicy.isSavedTermios("-F /dev/other"));
        expect(!RootPolicy.isSavedTermios("500:5\nerror"));
        expect(!RootPolicy.isSavedTermios(""));
        expect(!RootPolicy.isSavedTermios("1234"));
        expect(RootPolicy.isGameWindowActivity("com.ayaneo.gamewindow/.LauncherActivity"));
        expect(!RootPolicy.isGameWindowActivity("com.other/.LauncherActivity"));
        expect(!RootPolicy.isGameWindowActivity("com.ayaneo.gamewindow/.Main\n--user 10"));
        byte[] entry = {(byte) 0xe7, 0, 0, 0, 0, 0, 0, 0, 7, 7, (byte) 0xed};
        byte[] quit = new byte[64]; quit[3] = 3;
        expect("boot-entry".equals(RootPolicy.entryCommandKind(entry)));
        expect("quit".equals(RootPolicy.entryCommandKind(quit)));
        for (byte[] valid : new byte[][]{entry, quit}) {
            for (int i = 0; i < valid.length; i++) {
                int original = valid[i] & 255;
                for (int replacement = 0; replacement < 256; replacement++) {
                    if (replacement == original) continue;
                    byte[] altered = valid.clone(); altered[i] = (byte) replacement;
                    rejectsEntry(altered);
                }
            }
        }
        byte[] data = new byte[64]; data[2] = 58; data[3] = 2;
        rejectsEntry(data);
        rejectsEntry(new byte[0]);
        rejectsEntry(new byte[10]);
        rejectsEntry(new byte[65]);
        expect("quit".equals(RootPolicy.exitCommandKind(quit)));
        rejectsExit(entry);
        rejectsExit(data);
        rejectsExit(new byte[0]);
        rejectsExit(new byte[63]);
        rejectsExit(new byte[65]);
        for (int i = 0; i < quit.length; i++) {
            int original = quit[i] & 255;
            for (int replacement = 0; replacement < 256; replacement++) {
                if (replacement == original) continue;
                byte[] altered = quit.clone(); altered[i] = (byte) replacement;
                rejectsExit(altered);
            }
        }
        String registered = "  Registered Receivers:\n  * ReceiverList{abc 15936 com.ayaneo.gamewindow/1000/u0 remote:def}\n"
                + "    app=15936:com.ayaneo.gamewindow/1000 pid=15936 uid=1000 user=0 #receivers=6\n"
                + "    Filter #0: BroadcastFilter{123}\n      Action: \"AYA_CONTROLLER_UPDATE_FINISH\"\n";
        expect(RootPolicy.hasFinishReceiver(registered, java.util.Arrays.asList("15936")));
        expect(!RootPolicy.hasFinishReceiver(registered, java.util.Arrays.asList("99999")));
        expect(!RootPolicy.hasFinishReceiver(registered.replace("com.ayaneo.gamewindow", "com.other"), java.util.Arrays.asList("15936")));
        expect(!RootPolicy.hasFinishReceiver("  Registered Receivers:\n  Receiver Resolver Table:\n" + registered, java.util.Arrays.asList("15936")));
        expect(!RootPolicy.hasFinishReceiver(registered.replace("      Action:", "  * ReceiverList{another}\n      Action:"), java.util.Arrays.asList("15936")));
        String history = "ACTIVITY MANAGER BROADCAST STATE (dumpsys activity broadcasts)\n" + registered
                + "  Historical broadcasts:\n    error: permission denial SecurityException exception occurred\n";
        RootPolicy.requireBroadcastDump(0, history); checks++;
        expect(RootPolicy.hasFinishReceiver(history, java.util.Arrays.asList("15936")));
        rejectsBroadcastDump(1, history);
        rejectsBroadcastDump(0, "Permission denial: cannot dump broadcasts");
        rejectsBroadcastDump(0, "");
        String huge = new String(new char[1048576]).replace('\0', 'x');
        String diagnostic = RootPolicy.commandFailure("Test command", 1, huge);
        expect(diagnostic.length() <= 512);
        expect(diagnostic.startsWith("Test command failed (exit 1): "));
        expect(diagnostic.endsWith("[truncated]"));
        expect("short".equals(RootPolicy.boundedDiagnostic("short")));
        if (args.length == 2) {
            String captured = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0])), java.nio.charset.StandardCharsets.UTF_8);
            RootPolicy.requireBroadcastDump(0, captured); checks++;
            expect(RootPolicy.hasFinishReceiver(captured, java.util.Arrays.asList(args[1])));
            expect(!RootPolicy.hasFinishReceiver(captured, java.util.Arrays.asList("-1")));
        }
        System.out.println("RootPolicyTest: " + checks + " checks passed");
    }
    private static void rejectsEntry(byte[] bytes) throws Exception {
        try { RootPolicy.entryCommandKind(bytes); throw new AssertionError("Non-allowlisted entry command accepted"); }
        catch (IOException expected) { checks++; }
    }
    private static void rejectsExit(byte[] bytes) throws Exception {
        try { RootPolicy.exitCommandKind(bytes); throw new AssertionError("Non-allowlisted exit command accepted"); }
        catch (IOException expected) { checks++; }
    }
    private static void rejectsBroadcastDump(int exitCode, String text) throws Exception {
        try { RootPolicy.requireBroadcastDump(exitCode, text); throw new AssertionError("Invalid broadcast dump accepted"); }
        catch (IOException expected) { checks++; }
    }
}
