package org.kpfe.restore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Host-only archive containment and child-process bounds; no Android/device access. */
public final class SupportCaptureTest {
    private static int checks;
    private static void expect(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static InputStream text(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            if (args[0].equals("flood")) { for (int i = 0; i < 8192; i++) System.out.print('x'); System.out.print("TAIL"); }
            else if (args[0].equals("wait")) Thread.sleep(10000);
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (SupportArchive zip = new SupportArchive(bytes)) {
            for (String name : new String[] {"../escape", "/absolute", "a/../../b", "a/./b", "a//b", "a\\b", "", "a/", "C:/path"}) {
                try { zip.add(name, text("x"), 10); throw new AssertionError("Unsafe ZIP name accepted"); }
                catch (IOException expected) { checks++; }
            }
            expect(zip.add("logs/limited.txt", text("abcdefgh"), 4));
            expect(!zip.add("exact.txt", text("1234"), 4));
            try { zip.add("exact.txt", text("other"), 4); throw new AssertionError("Duplicate ZIP name accepted"); }
            catch (IOException expected) { checks++; }
            zip.finish();
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            ZipEntry first = zip.getNextEntry(); expect(first.getName().equals("logs/limited.txt"));
            expect(read(zip).equals("abcd"));
            expect(zip.getNextEntry().getName().equals("exact.txt")); expect(read(zip).equals("1234"));
            expect(zip.getNextEntry() == null);
        }
        ByteArrayOutputStream large = new ByteArrayOutputStream();
        try (SupportArchive zip = new SupportArchive(large)) {
            expect(zip.add("first.log", zeros(SupportArchive.MAX_ENTRY + 1), SupportArchive.MAX_ENTRY));
            expect(zip.add("second.log", zeros(SupportArchive.MAX_ENTRY), SupportArchive.MAX_ENTRY));
            try { zip.add("third.log", text("x"), 1); throw new AssertionError("Total bound exceeded"); }
            catch (IOException expected) { checks++; }
        }
        long unpacked = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(large.toByteArray()))) {
            byte[] buffer = new byte[8192];
            while (zip.getNextEntry() != null) { int n; while ((n = zip.read(buffer)) >= 0) unpacked += n; }
        }
        expect(unpacked == SupportArchive.MAX_TOTAL);
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        String cp = System.getProperty("java.class.path");
        SupportProcess.Result flood = SupportProcess.run(3000, 32, java, "-cp", cp, SupportCaptureTest.class.getName(), "flood");
        expect(flood.exitCode == 0 && !flood.timedOut && flood.truncated && flood.output.length() == 32);
        SupportProcess.Result tail = SupportProcess.runTail(3000, 32, java, "-cp", cp, SupportCaptureTest.class.getName(), "flood");
        expect(tail.exitCode == 0 && tail.truncated && tail.output.length() == 32 && tail.output.endsWith("TAIL"));
        long started = System.nanoTime();
        SupportProcess.Result timeout = SupportProcess.run(150, 32, java, "-cp", cp, SupportCaptureTest.class.getName(), "wait");
        expect(timeout.timedOut && timeout.exitCode == -1);
        expect((System.nanoTime() - started) / 1000000 < 3000);
        System.out.println("SupportCaptureTest: " + checks + " checks passed; ZIP path/size and process bounds");
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int n; byte[] bytes = new byte[1024];
        while ((n = input.read(bytes)) >= 0) output.write(bytes, 0, n);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
    private static InputStream zeros(final int count) {
        return new InputStream() {
            private int left = count;
            @Override public int read() { if (left == 0) return -1; left--; return 0; }
            @Override public int read(byte[] buffer, int offset, int length) {
                if (left == 0) return -1;
                int n = Math.min(left, length); java.util.Arrays.fill(buffer, offset, offset + n, (byte)0); left -= n; return n;
            }
        };
    }
}
