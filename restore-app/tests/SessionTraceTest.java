package org.kpfe.restore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Host-only trace preservation/failure tests, no Android or controller access. */
public final class SessionTraceTest {
    private static int checks;

    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File path = File.createTempFile("elite-session-trace-test-", ".log");
        try {
            SessionTrace trace = new SessionTrace(path);
            trace.append("first firmware reply: 66");
            String payload = new String(new char[512]).replace('\0', 'a');
            for (int i = 0; i < 1010; i++) trace.append("packet " + i + ": " + payload);
            trace.append("last packet response and final result");
            trace.close();
            expect(trace.failure().isEmpty(), "Full 1010-packet trace should close cleanly");
            String saved = new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8);
            expect(saved.length() > 100000, "Trace exceeds the UI ring-log limit");
            expect(saved.startsWith("first firmware reply: 66\n"), "First reply survives full transfer logging");
            expect(saved.contains("packet 1009: "), "Final packet survives");
            expect(saved.endsWith("last packet response and final result\n"), "Result survives");
            long size = path.length();
            trace.close(); trace.append("must not append after close");
            expect(path.length() == size, "Closed trace is idempotent");
        } finally { Files.deleteIfExists(path.toPath()); }

        ByteArrayOutputStream bounded = new ByteArrayOutputStream();
        SessionTrace limited = new SessionTrace(bounded, 6);
        limited.append("é"); // Three encoded bytes including newline.
        limited.append("ok");
        expect(limited.failure().isEmpty(), "Exact UTF-8 byte limit accepted");
        limited.append("later");
        expect(!limited.failure().isEmpty(), "Overflow reports incomplete trace");
        limited.close();
        expect("é\nok\n".equals(new String(bounded.toByteArray(), StandardCharsets.UTF_8)),
                "Overflow preserves earlier complete records without partial last line");

        OutputStream failedDisk = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("simulated storage full"); }
        };
        SessionTrace failing = new SessionTrace(failedDisk, 1024);
        failing.append("a raw reply");
        expect(failing.failure().contains("simulated storage full"), "Mid-session storage error reported");
        failing.append("later"); failing.close(); failing.close();
        expect(failing.failure().contains("simulated storage full"), "Original evidence failure retained without throwing into transfer");
        System.out.println("SessionTraceTest: " + checks + " checks passed");
    }
}
