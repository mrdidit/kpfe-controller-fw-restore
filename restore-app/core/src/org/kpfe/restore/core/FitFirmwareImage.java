package org.kpfe.restore.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Exact user-supplied standard Pocket FIT image; its firmware version is unconfirmed. */
public final class FitFirmwareImage {
    public static final int EXPECTED_SIZE = 79692;
    public static final String EXPECTED_SHA256 = "c152c91813437e7d7124cff477a6e46b230ed1adb83c4ad84c48ad74239f2ba2";
    public static final String ASSET_NAME = "Pocket_FIT_supplied.bin";
    public static final String VERSION_LABEL = "unconfirmed";
    private final byte[] bytes;

    private FitFirmwareImage(byte[] bytes) { this.bytes = bytes; }

    public static FitFirmwareImage validate(byte[] supplied) {
        if (supplied == null || supplied.length != EXPECTED_SIZE)
            throw new IllegalArgumentException("Expected exactly " + EXPECTED_SIZE + " bytes for the supplied Pocket FIT image");
        byte[] snapshot = supplied.clone();
        if (!EXPECTED_SHA256.equals(FirmwareImage.sha256(snapshot)))
            throw new IllegalArgumentException("Firmware SHA-256 does not match the pinned Pocket FIT image");
        return new FitFirmwareImage(snapshot);
    }

    /** Reads completely with a strict bound; stream ownership stays with the caller. */
    public static FitFirmwareImage read(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("Missing Pocket FIT firmware input");
        ByteArrayOutputStream out = new ByteArrayOutputStream(EXPECTED_SIZE);
        byte[] block = new byte[4096];
        while (true) {
            int count = input.read(block, 0, Math.min(block.length, EXPECTED_SIZE + 1 - out.size()));
            if (count < 0) break;
            if (count == 0) {
                int value = input.read();
                if (value < 0) break;
                out.write(value);
            } else out.write(block, 0, count);
            if (out.size() > EXPECTED_SIZE) throw new IOException("Pocket FIT firmware input exceeds the exact image size");
        }
        return validate(out.toByteArray());
    }

    public int size() { return bytes.length; }
    public byte[] copyBytes() { return bytes.clone(); }
    byte valueAt(int offset) { return bytes[offset]; }
}
