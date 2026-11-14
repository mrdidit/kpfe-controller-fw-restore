package org.kpfe.restore;

import java.io.IOException;
import org.kpfe.restore.core.FirmwareImage;

/** Root-boundary command limits, independent of the protocol engine. Single session only. */
final class StockCommandGuard {
    private final String mode;
    private final byte[] firmware;
    private final int packetCount;
    private boolean entry;
    private boolean quit;
    private int nextPacket;

    StockCommandGuard(String mode) throws IOException {
        this(mode, null);
    }

    StockCommandGuard(String mode, FirmwareImage selectedImage) throws IOException {
        if (!"restore".equals(mode) && !"entry-test".equals(mode)
                && !"exit-only".equals(mode) && !"resume-only".equals(mode)) {
            throw new IOException("Unknown stock transport mode.");
        }
        if ("restore".equals(mode) && selectedImage == null)
            throw new IOException("Restore requires an explicitly selected, validated stock image.");
        this.mode = mode;
        this.firmware = selectedImage == null ? null : selectedImage.copyBytes();
        this.packetCount = firmware == null ? 0 : (firmware.length + 57) / 58;
    }

    String claim(byte[] bytes) throws IOException {
        if (bytes == null || quit || "resume-only".equals(mode)) throw rejected();
        String kind;
        if ("exit-only".equals(mode)) kind = RootPolicy.exitCommandKind(bytes);
        else if ("entry-test".equals(mode)) kind = RootPolicy.entryCommandKind(bytes);
        else {
            try { kind = RootPolicy.entryCommandKind(bytes); }
            catch (IOException notControl) { kind = validateData(bytes); }
        }
        if ("boot-entry".equals(kind)) {
            if (entry || nextPacket != 0) throw rejected();
            entry = true;
        } else if ("quit".equals(kind)) {
            // A full restore must never boot a knowingly incomplete packet sequence.
            // Diagnostic modes have their separate, explicitly selected Quit policy.
            if ("restore".equals(mode) && nextPacket != packetCount) throw rejected();
            quit = true;
        }
        else nextPacket++;
        return kind;
    }

    private String validateData(byte[] bytes) throws IOException {
        if (firmware == null || bytes.length != 64 || (bytes[3] & 255) != 2 || nextPacket >= packetCount) throw rejected();
        int length = bytes[2] & 255;
        int expectedLength = Math.min(58, firmware.length - nextPacket * 58);
        int index = (bytes[4] & 255) | ((bytes[5] & 255) << 8);
        if (length != expectedLength || index != nextPacket) throw rejected();
        int sum = 0;
        for (int i = 6; i < 6 + length; i++) {
            if (bytes[i] != firmware[nextPacket * 58 + i - 6]) throw rejected();
            sum += bytes[i] & 255;
        }
        for (int i = 6 + length; i < bytes.length; i++) if (bytes[i] != 0) throw rejected();
        int supplied = (bytes[0] & 255) | ((bytes[1] & 255) << 8);
        if (supplied != sum) throw rejected();
        return "firmware-data";
    }

    private static IOException rejected() {
        return new IOException("Root transport rejected an out-of-mode, duplicate or malformed command before UART transmission.");
    }
}
