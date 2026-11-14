package org.kpfe.restore.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Immutable, hash-pinned stock Pocket FIT Elite KR02 B images; no arbitrary firmware. */
public final class FirmwareImage {
    /** Legacy v8 constants; new callers must select a catalog descriptor explicitly. */
    public static final int EXPECTED_SIZE = 58568;
    public static final String EXPECTED_SHA256 = "fd48a4b0852e3f888874cf1acce4ca2cfd7225ad3b40581e144d69a6a9f1e3cd";

    public static final class Descriptor {
        public final String version, assetName, sha256;
        public final int size;
        private Descriptor(String version, String assetName, int size, String sha256) {
            this.version = version; this.assetName = assetName; this.size = size; this.sha256 = sha256;
        }
    }

    public static final Descriptor V8 = new Descriptor("8", "KPFE_KR02_v8.bin", EXPECTED_SIZE, EXPECTED_SHA256);
    public static final Descriptor V9 = new Descriptor("9", "KPFE_KR02_v9.bin", 58592,
            "1a43b810b4a5d19ea6e41c14b2b4b1b734b2959a12b200f38868221dc01ee0fe");
    private final byte[] bytes;
    private final Descriptor descriptor;

    private FirmwareImage(Descriptor descriptor, byte[] bytes) { this.descriptor = descriptor; this.bytes = bytes; }

    /** Strict explicit selection; unknown/null versions never fall back to another image. */
    public static Descriptor forVersion(String version) {
        if (V8.version.equals(version)) return V8;
        if (V9.version.equals(version)) return V9;
        throw new IllegalArgumentException("Unsupported stock Elite firmware version: " + version);
    }

    public static Descriptor[] catalog() { return new Descriptor[] {V8, V9}; }

    /** Identifies one of the two exact pins; explicit-selection callers use the overload. */
    public static FirmwareImage validate(byte[] supplied) {
        if (supplied != null) {
            if (supplied.length == V8.size) return validate(V8, supplied);
            if (supplied.length == V9.size) return validate(V9, supplied);
        }
        throw new IllegalArgumentException("Firmware size does not match a pinned stock Elite image");
    }

    /** Validate both identity and the user's explicit target before creating an immutable snapshot. */
    public static FirmwareImage validate(Descriptor selected, byte[] supplied) {
        requireCatalogDescriptor(selected);
        if (supplied == null || supplied.length != selected.size)
            throw new IllegalArgumentException("Expected exactly " + selected.size + " bytes for stock Elite v" + selected.version);
        byte[] copy = supplied.clone();
        if (!selected.sha256.equals(sha256(copy)))
            throw new IllegalArgumentException("Firmware SHA-256 does not match selected stock Elite v" + selected.version);
        return new FirmwareImage(selected, copy);
    }

    /** Reads completely, rejects excess bytes, and leaves stream ownership with the caller. */
    public static FirmwareImage read(InputStream input) throws IOException {
        return readLimited(null, input, Math.max(V8.size, V9.size));
    }

    public static FirmwareImage read(Descriptor selected, InputStream input) throws IOException {
        requireCatalogDescriptor(selected);
        return readLimited(selected, input, selected.size);
    }

    private static FirmwareImage readLimited(Descriptor selected, InputStream input, int limit) throws IOException {
        if (input == null) throw new IllegalArgumentException("Missing firmware input");
        ByteArrayOutputStream out = new ByteArrayOutputStream(limit);
        byte[] buffer = new byte[4096];
        while (true) {
            int count = input.read(buffer, 0, Math.min(buffer.length, limit + 1 - out.size()));
            if (count < 0) break;
            if (count == 0) {
                int value = input.read();
                if (value < 0) break;
                out.write(value);
            } else out.write(buffer, 0, count);
            if (out.size() > limit) throw new IOException("Firmware input is too large for the selected stock image");
        }
        return selected == null ? validate(out.toByteArray()) : validate(selected, out.toByteArray());
    }

    private static void requireCatalogDescriptor(Descriptor selected) {
        if (selected != V8 && selected != V9) throw new IllegalArgumentException("Missing or unsupported stock image selection");
    }

    public Descriptor descriptor() { return descriptor; }
    public int size() { return bytes.length; }
    public byte[] copyBytes() { return bytes.clone(); }
    byte valueAt(int offset) { return bytes[offset]; }

    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder(64);
            for (byte value : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
