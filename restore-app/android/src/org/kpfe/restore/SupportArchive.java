package org.kpfe.restore;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Bounded report archive with fixed, relative entry names. No extraction is performed. */
final class SupportArchive implements Closeable {
    static final int MAX_ENTRY = 16 * 1024 * 1024;
    static final int MAX_TOTAL = 20 * 1024 * 1024;
    private final ZipOutputStream zip;
    private final Set<String> names = new HashSet<>();
    private int total;

    SupportArchive(OutputStream output) { zip = new ZipOutputStream(output); }

    /** Returns true if bytes beyond the selected limit were omitted. Does not close input. */
    boolean add(String name, InputStream input, int limit) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,119}")
                || name.contains("//") || name.endsWith("/")) throw new IOException("Invalid report entry name");
        for (String part : name.split("/"))
            if (part.equals(".") || part.equals("..")) throw new IOException("Invalid report entry path");
        if (limit < 1 || limit > MAX_ENTRY || names.size() >= 16 || !names.add(name))
            throw new IOException("Duplicate entry or report limit exceeded");
        int available = Math.min(limit, MAX_TOTAL - total);
        if (available < 1) throw new IOException("Report total limit exceeded");
        zip.putNextEntry(new ZipEntry(name));
        try {
            byte[] buffer = new byte[8192];
            int copied = 0;
            while (copied < available) {
                int count = input.read(buffer, 0, Math.min(buffer.length, available - copied));
                if (count < 0) return false;
                if (count == 0) {
                    int value = input.read();
                    if (value < 0) return false;
                    zip.write(value); copied++; total++;
                } else {
                    zip.write(buffer, 0, count); copied += count; total += count;
                }
            }
            return input.read() >= 0;
        } finally { zip.closeEntry(); }
    }

    void finish() throws IOException { zip.finish(); zip.flush(); }
    @Override public void close() throws IOException { zip.close(); }
}
