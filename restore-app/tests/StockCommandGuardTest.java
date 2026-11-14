package org.kpfe.restore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import org.kpfe.restore.core.FirmwareImage;
import org.kpfe.restore.core.PacketCodec;

public final class StockCommandGuardTest {
    private static int checks;
    private static final byte[] ENTRY = {(byte)0xe7, 0, 0, 0, 0, 0, 0, 0, 7, 7, (byte)0xed};
    private static byte[] quit() { byte[] q = new byte[64]; q[3] = 3; return q; }
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError(); }
    private static void reject(StockCommandGuard guard, byte[] bytes) throws Exception {
        try { guard.claim(bytes); throw new AssertionError("Unexpected command accepted"); }
        catch (IOException expected) { checks++; }
    }
    private static void checksum(byte[] bytes) {
        int count = bytes[2] & 255;
        int sum = 0;
        for (int i = 0; i < count; i++) sum += bytes[6+i] & 255;
        bytes[0] = (byte)sum; bytes[1] = (byte)(sum >>> 8);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Pass preserved v8 and v9 fixtures");
        FirmwareImage v8 = FirmwareImage.validate(FirmwareImage.V8, Files.readAllBytes(Paths.get(args[0])));
        FirmwareImage v9 = FirmwareImage.validate(FirmwareImage.V9, Files.readAllBytes(Paths.get(args[1])));
        byte[] first = PacketCodec.dataPacket(v8, 0);
        StockCommandGuard diagnostic = new StockCommandGuard("entry-test");
        check("boot-entry".equals(diagnostic.claim(ENTRY)));
        reject(diagnostic, ENTRY); reject(diagnostic, first);
        check("quit".equals(diagnostic.claim(quit())));
        reject(diagnostic, quit()); reject(diagnostic, ENTRY);
        check("quit".equals(new StockCommandGuard("entry-test").claim(quit())));
        check("quit".equals(new StockCommandGuard("exit-only").claim(quit())));
        reject(new StockCommandGuard("exit-only"), ENTRY);
        reject(new StockCommandGuard("exit-only"), first);
        reject(new StockCommandGuard("resume-only"), ENTRY);
        reject(new StockCommandGuard("resume-only"), quit());
        reject(new StockCommandGuard("entry-test"), null);
        for (String mode : new String[]{"entry-test", "exit-only"}) {
            for (byte[] original : new byte[][]{ENTRY, quit()}) {
                for (int offset = 0; offset < original.length; offset++) {
                    for (int value = 0; value < 256; value++) {
                        if ((original[offset] & 255) == value) continue;
                        byte[] changed = original.clone(); changed[offset] = (byte)value;
                        reject(new StockCommandGuard(mode), changed);
                    }
                }
            }
        }
        try { new StockCommandGuard("restore"); throw new AssertionError("Missing image accepted"); }
        catch (IOException expected) { checks++; }
        for (FirmwareImage selected : new FirmwareImage[]{v8, v9}) {
            FirmwareImage other = selected == v8 ? v9 : v8;
            for (String mode : new String[]{"entry-test", "exit-only", "resume-only"}) {
                StockCommandGuard noFlash = new StockCommandGuard(mode, selected);
                reject(noFlash, PacketCodec.dataPacket(v8, 0));
                reject(noFlash, PacketCodec.dataPacket(v9, 0));
                if (!"resume-only".equals(mode)) check("quit".equals(noFlash.claim(quit())));
            }
            restore(selected, other);
        }
        System.out.println("StockCommandGuardTest: " + checks + " checks passed; exact v8/v9 fixtures");
    }

    private static void restore(FirmwareImage image, FirmwareImage other) throws Exception {
        int count = PacketCodec.packetCount(image);
        StockCommandGuard restore = new StockCommandGuard("restore", image);
        reject(restore, quit()); // No firmware packet has been claimed.
        check("boot-entry".equals(restore.claim(ENTRY)));
        reject(restore, quit()); // E7 does not authorize a restore-mode Quit.
        reject(restore, ENTRY);
        for (int i = 0; i < count; i++) {
            byte[] correct = PacketCodec.dataPacket(image, i);
            if (i < PacketCodec.packetCount(other)) {
                byte[] differentImage = PacketCodec.dataPacket(other, i);
                if (!Arrays.equals(correct, differentImage)) reject(restore, differentImage);
            }
            reject(restore, quit()); // Every incomplete sequence rejects Quit, including v9 after 1010 packets.
            if (i + 1 < count) reject(restore, PacketCodec.dataPacket(image, i + 1));
            if (i == count - 1) {
                byte[] wrongLength = correct.clone(); wrongLength[2] = (byte)(count == 1010 ? 12 : 46);
                checksum(wrongLength); reject(restore, wrongLength);
                byte[] badPadding = correct.clone(); badPadding[63] = 1; reject(restore, badPadding);
            }
            check("firmware-data".equals(restore.claim(correct)));
            reject(restore, correct); // Never accept a repeated accepted packet.
            reject(restore, ENTRY); // Entry cannot restart after data begins.
        }
        reject(restore, PacketCodec.dataPacket(other, PacketCodec.packetCount(other) - 1));
        check("quit".equals(restore.claim(quit())));
        reject(restore, quit()); reject(restore, PacketCodec.dataPacket(image, 0)); reject(restore, ENTRY);
        // An already-running loader need not receive E7, but still needs every packet.
        StockCommandGuard alreadyInLoader = new StockCommandGuard("restore", image);
        for (int i = 0; i < count; i++) check("firmware-data".equals(alreadyInLoader.claim(PacketCodec.dataPacket(image, i))));
        check("quit".equals(alreadyInLoader.claim(quit())));
        reject(alreadyInLoader, quit());
        StockCommandGuard rejects = new StockCommandGuard("restore", image);
        byte[] first = PacketCodec.dataPacket(image, 0);
        for (int offset = 0; offset < first.length; offset++) {
            for (int value = 0; value < 256; value++) {
                if ((first[offset] & 255) == value) continue;
                byte[] bad = first.clone(); bad[offset] = (byte)value; reject(rejects, bad);
            }
        }
        // A well-formed checksum cannot authorize changed/foreign firmware bytes.
        for (int offset = 6; offset < 64; offset++) {
            byte[] altered = first.clone(); altered[offset] ^= 1; checksum(altered); reject(rejects, altered);
        }
        for (int length = 0; length < 64; length++) reject(rejects, Arrays.copyOf(first, length));
        reject(rejects, Arrays.copyOf(first, 65)); reject(rejects, null);
        reject(rejects, PacketCodec.dataPacket(image, 1)); reject(rejects, new byte[64]);
        check("firmware-data".equals(rejects.claim(first))); // Rejections never consume the expected index.
    }
}
