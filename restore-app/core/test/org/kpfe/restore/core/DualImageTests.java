package org.kpfe.restore.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Arrays;

/** Both preserved real fixtures; no Android, device, network or firmware I/O. */
public final class DualImageTests {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    private static void reject(FirmwareImage.Descriptor selected, byte[] bytes) {
        try { FirmwareImage.validate(selected, bytes); throw new AssertionError("Unpinned/incorrect selection accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void inspect(FirmwareImage.Descriptor selected, byte[] bytes, int count, int last, String packetHash) throws Exception {
        FirmwareImage image = FirmwareImage.validate(selected, bytes);
        check(image.descriptor() == selected && FirmwareImage.validate(bytes).descriptor() == selected, "explicit and byte identity agree");
        check(FirmwareImage.forVersion(selected.version) == selected, "strict version lookup");
        check(image.size() == selected.size && FirmwareImage.sha256(bytes).equals(selected.sha256), "catalog size/hash match fixture");
        check(selected.assetName.equals("KPFE_KR02_v" + selected.version + ".bin"), "catalog asset name");
        check(PacketCodec.packetCount(image) == count, "selected packet count");
        ByteArrayOutputStream wire = new ByteArrayOutputStream(), reassembled = new ByteArrayOutputStream();
        for (int index = 0; index < count; index++) {
            byte[] packet = PacketCodec.dataPacket(image, index);
            int length = index == count - 1 ? last : 58;
            check(packet.length == 64 && packet[2] == length && packet[3] == 2, "fixed framing and selected final length");
            check(((packet[4] & 255) | ((packet[5] & 255) << 8)) == index, "monotonic LE16 sequence");
            int checksum = 0;
            for (int i = 0; i < length; i++) {
                check(packet[6 + i] == bytes[index * 58 + i], "exact selected payload");
                checksum += packet[6 + i] & 255;
            }
            check(((packet[0] & 255) | ((packet[1] & 255) << 8)) == checksum, "unsigned payload checksum");
            for (int i = 6 + length; i < packet.length; i++) check(packet[i] == 0, "zero final padding");
            wire.write(packet); reassembled.write(packet, 6, length);
        }
        check(Arrays.equals(bytes, reassembled.toByteArray()), "entire fixture reassembled");
        check(FirmwareImage.sha256(wire.toByteArray()).equals(packetHash), "independent Python packet-stream hash");
        for (int index : new int[]{-1, count, count + 1}) {
            try { PacketCodec.dataPacket(image, index); throw new AssertionError("Out of range packet accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        for (int offset : new int[]{0, 20, bytes.length / 2, bytes.length - 1}) {
            byte[] changed = bytes.clone(); changed[offset] ^= 1; reject(selected, changed);
        }
        reject(selected, Arrays.copyOf(bytes, bytes.length - 1));
        reject(selected, Arrays.copyOf(bytes, bytes.length + 1));
        reject(selected, null);
        byte[] source = bytes.clone(); FirmwareImage snapshot = FirmwareImage.validate(selected, source);
        source[0] ^= 1; byte[] exported = snapshot.copyBytes(); exported[1] ^= 1;
        check(Arrays.equals(bytes, snapshot.copyBytes()), "snapshot cannot be mutated through caller bytes");
        check(FirmwareImage.read(selected, new ByteArrayInputStream(bytes)).descriptor() == selected, "explicit stream identity");
        check(FirmwareImage.read(new ByteArrayInputStream(bytes)).descriptor() == selected, "legacy stream recognizes exact catalog identity");
        final boolean[] closed = {false};
        ByteArrayInputStream shortReads = new ByteArrayInputStream(bytes) {
            private boolean zero = true;
            public synchronized int read(byte[] target, int offset, int length) {
                if (zero) { zero = false; return 0; }
                return super.read(target, offset, Math.min(7, length));
            }
            public void close() { closed[0] = true; }
        };
        check(FirmwareImage.read(selected, shortReads).descriptor() == selected && !closed[0], "short/zero reads complete without closing caller stream");
        ByteArrayInputStream tooLong = new ByteArrayInputStream(new byte[selected.size + 100]);
        try { FirmwareImage.read(selected, tooLong); throw new AssertionError("Overlong stream accepted"); }
        catch (IOException expected) { checks++; }
        check(tooLong.available() == 99, "bounded stream stops at selected size plus one byte");
    }

    private static final class Session implements RestoreEngine.Transport, RestoreEngine.Clock {
        final FirmwareImage image;
        final boolean noFlash;
        final ArrayDeque<byte[]> rx = new ArrayDeque<byte[]>();
        long now;
        int sent, entries, quits, prepares, closes;
        Session(FirmwareImage image, boolean noFlash) { this.image = image; this.noFlash = noFlash; }
        public long nanoTime() { return now; }
        public void sleepMillis(long millis) { now += millis * 1000000L; }
        public void open() { }
        public void prepareFirmwareTransfer() {
            check(!noFlash && prepares++ == 0 && sent == 0 && entries == 1, "only full mode prepares after entry");
        }
        public int read(byte[] bytes, int offset, int length, int timeoutMillis) {
            if (rx.isEmpty()) { now += timeoutMillis * 1000000L; return 0; }
            byte[] chunk = rx.remove();
            check(chunk.length <= length, "fixture response fits");
            System.arraycopy(chunk, 0, bytes, offset, chunk.length);
            return chunk.length;
        }
        public void write(byte[] bytes, int timeoutMillis) {
            if (Arrays.equals(bytes, PacketCodec.bootEntry())) {
                check(entries++ == 0 && sent == 0 && quits == 0, "one E7 before data");
                rx.add(RestoreEngine.BOOT_BANNER.getBytes(StandardCharsets.US_ASCII));
            } else if (Arrays.equals(bytes, PacketCodec.quitPacket())) {
                check(quits++ == 0 && sent == (noFlash ? 0 : PacketCodec.packetCount(image)), "Quit only at selected complete sequence");
                rx.add(RestoreEngine.APPLICATION_BANNER.getBytes(StandardCharsets.US_ASCII));
            } else {
                check(!noFlash && prepares == 1 && quits == 0, "no firmware in diagnostic modes");
                check(Arrays.equals(bytes, PacketCodec.dataPacket(image, sent++)), "engine sends selected exact sequence");
                rx.add(new byte[]{(byte) 0xff});
            }
        }
        public void close() { closes++; }
    }

    private static void sessions(FirmwareImage image) {
        Session full = new Session(image, false);
        RestoreEngine.Result result = new RestoreEngine(full, null, full).run(image.copyBytes());
        check(result.status == RestoreEngine.Status.SUCCESS && full.closes == 1 && full.prepares == 1, "selected full transfer succeeds and cleans once");
        check(result.packetsSent == PacketCodec.packetCount(image) && result.packetsAcknowledged == result.packetsSent, "selected full transfer counts");
        for (String mode : new String[]{"entry", "exit", "guided"}) {
            Session session = new Session(image, true);
            RestoreEngine engine = new RestoreEngine(session, null, session);
            if ("entry".equals(mode)) result = engine.testEntryOnly(image.copyBytes());
            else if ("exit".equals(mode)) result = engine.exitOnly(image.copyBytes());
            else {
                session.rx.add(RestoreEngine.BOOT_BANNER.getBytes(StandardCharsets.US_ASCII));
                result = engine.guidedEntryTest(image.copyBytes(), new RestoreEngine.ExitApproval() {
                    public boolean approved() { return true; }
                });
            }
            check(result.status == RestoreEngine.Status.SUCCESS && session.sent == 0 && session.prepares == 0
                    && !result.flashMayHaveStarted && session.quits == 1 && session.closes == 1, "both image identities preserve no-flash limits");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Pass preserved v8 and v9 fixtures");
        byte[] v8 = Files.readAllBytes(Paths.get(args[0])), v9 = Files.readAllBytes(Paths.get(args[1]));
        check(FirmwareImage.catalog().length == 2, "only two supported images");
        FirmwareImage.Descriptor[] catalog = FirmwareImage.catalog(); catalog[0] = null;
        check(FirmwareImage.catalog()[0] == FirmwareImage.V8, "catalog array is defensive");
        for (String unknown : new String[]{null, "", "7", "10", "08", "v9", "9 "}) {
            try { FirmwareImage.forVersion(unknown); throw new AssertionError("Unknown version accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        reject(null, v8); reject(FirmwareImage.V8, v9); reject(FirmwareImage.V9, v8);
        reject(FirmwareImage.V9, Arrays.copyOf(v8, v9.length));
        reject(FirmwareImage.V8, Arrays.copyOf(v9, v8.length));
        for (FirmwareImage.Descriptor selected : FirmwareImage.catalog()) {
            byte[] other = selected == FirmwareImage.V8 ? v9 : v8;
            try { FirmwareImage.read(selected, new ByteArrayInputStream(other)); throw new AssertionError("Stream silently changed selected image"); }
            catch (IOException | IllegalArgumentException expected) { checks++; }
        }
        inspect(FirmwareImage.V8, v8, 1010, 46, "94e0b64b8f24d9b3e21ab16e85697e9820227fc96602ca645f72af526c24a9a7");
        inspect(FirmwareImage.V9, v9, 1011, 12, "760d556bb1d2f89771fec5eb786c0ba4b29a2b871d9cc0fe59f524469e17df63");
        sessions(FirmwareImage.validate(FirmwareImage.V8, v8));
        sessions(FirmwareImage.validate(FirmwareImage.V9, v9));
        System.out.println("DualImageTests: " + checks + " checks passed; preserved v8/v9 fixtures, no hardware access");
    }
}
