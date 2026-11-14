package org.kpfe.restore;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Complete private session evidence, independent of the UI's bounded recent log. */
final class SessionTrace {
    static final long MAX_BYTES = 16L * 1024 * 1024;
    private final FileOutputStream file;
    private final BufferedOutputStream output;
    private final long limit;
    private long bytes;
    private String failure = "";
    private boolean closed;

    SessionTrace(File path) throws IOException { this(path, MAX_BYTES); }

    SessionTrace(File path, long limit) throws IOException {
        if (limit < 1) throw new IllegalArgumentException("Trace limit must be positive");
        this.limit = limit;
        file = new FileOutputStream(path);
        output = new BufferedOutputStream(file, 8192);
    }

    /** Host fault-injection seam; Android uses the owned-file constructor. */
    SessionTrace(OutputStream destination, long limit) {
        if (limit < 1 || destination == null) throw new IllegalArgumentException("Invalid trace sink");
        this.limit = limit;
        file = null;
        output = new BufferedOutputStream(destination, 8192);
    }

    /** A logging failure must never interrupt a firmware transfer. Keep the first reason. */
    void append(String line) {
        if (closed || !failure.isEmpty()) return;
        byte[] encoded = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (encoded.length > limit - bytes) {
            failure = "Full session trace reached its size limit; later records were not saved.";
            return;
        }
        try {
            output.write(encoded);
            output.flush();
            bytes += encoded.length;
        } catch (IOException error) {
            failure = "Full session trace could not be written: " + error.getMessage();
        }
    }

    String failure() { return failure; }

    void close() {
        if (closed) return;
        closed = true;
        try { output.flush(); if (file != null) file.getFD().sync(); }
        catch (IOException error) {
            if (failure.isEmpty()) failure = "Full session trace could not be synchronized: " + error.getMessage();
        }
        try { output.close(); }
        catch (IOException error) {
            if (failure.isEmpty()) failure = "Full session trace could not be closed: " + error.getMessage();
        }
    }
}
