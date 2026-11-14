package org.kpfe.restore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Bounded child output and wait; only support collectors may use this helper. */
final class SupportProcess {
    static final class Result {
        final String output;
        final int exitCode;
        final boolean timedOut, truncated;
        final String readError;
        Result(String output, int exitCode, boolean timedOut, boolean truncated, String readError) {
            this.output = output; this.exitCode = exitCode; this.timedOut = timedOut;
            this.truncated = truncated; this.readError = readError;
        }
    }

    private static final class Reader implements Runnable {
        final InputStream input;
        final int limit;
        final byte[] tail;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int cursor, filled;
        boolean truncated;
        String error = "";
        Reader(InputStream input, int limit, boolean keepTail) {
            this.input = input; this.limit = limit; this.tail = keepTail ? new byte[limit] : null;
        }
        @Override public void run() {
            try {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    synchronized (this) {
                        if (tail == null) {
                            int keep = Math.min(count, Math.max(0, limit - bytes.size()));
                            bytes.write(buffer, 0, keep);
                            if (keep < count) truncated = true;
                        } else {
                            if (count > limit - filled) truncated = true;
                            int start = Math.max(0, count - limit), keep = count - start;
                            int first = Math.min(keep, limit - cursor);
                            System.arraycopy(buffer, start, tail, cursor, first);
                            System.arraycopy(buffer, start + first, tail, 0, keep - first);
                            cursor = (cursor + keep) % limit;
                            filled = Math.min(limit, filled + keep);
                        }
                    }
                }
            } catch (IOException e) { synchronized (this) { error = e.getClass().getSimpleName(); } }
            finally { try { input.close(); } catch (IOException ignored) { } }
        }
        synchronized Result result(int code, boolean timeout, boolean unfinished) {
            byte[] captured;
            if (tail == null) captured = bytes.toByteArray();
            else {
                captured = new byte[filled];
                int start = filled == limit ? cursor : 0, first = Math.min(filled, limit - start);
                System.arraycopy(tail, start, captured, 0, first);
                System.arraycopy(tail, 0, captured, first, filled - first);
            }
            return new Result(new String(captured, StandardCharsets.UTF_8), code, timeout, truncated,
                    unfinished ? "Output reader still pending at collection limit" : error);
        }
    }

    private SupportProcess() { }

    static Result run(long timeoutMillis, int outputLimit, String... command) throws IOException, InterruptedException {
        return run(false, timeoutMillis, outputLimit, command);
    }

    static Result runTail(long timeoutMillis, int outputLimit, String... command) throws IOException, InterruptedException {
        return run(true, timeoutMillis, outputLimit, command);
    }

    private static Result run(boolean tail, long timeoutMillis, int outputLimit, String... command) throws IOException, InterruptedException {
        if (timeoutMillis < 1 || outputLimit < 1 || outputLimit > 2 * 1024 * 1024)
            throw new IOException("Invalid support command limits");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        Reader reader = new Reader(process.getInputStream(), outputLimit, tail);
        Thread thread = new Thread(reader, "support-command-output");
        thread.setDaemon(true); thread.start();
        boolean complete = false;
        try {
            complete = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!complete) process.destroyForcibly();
            thread.join(100);
            int code = complete ? process.exitValue() : -1;
            return reader.result(code, !complete, thread.isAlive());
        } finally {
            if (!complete) process.destroyForcibly();
            // Do not block closing a pipe while its reader holds an I/O monitor.
            // The daemon reader owns closure; the root helper has its own deadline.
        }
    }
}
