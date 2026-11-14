package org.kpfe.restore.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline exact-image and USB protocol boundary tests; no device access. */
public final class FitUsbTests {
    private static int checks;
    private static byte[] firmware;
    private static FitFirmwareImage image;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected FIT, Elite v8 and Elite v9 fixture paths");
        firmware = Files.readAllBytes(Paths.get(args[0]));
        image = FitFirmwareImage.validate(firmware);
        imageTests(Files.readAllBytes(Paths.get(args[1])), Files.readAllBytes(Paths.get(args[2])));
        goldenTransfer();
        failedWrites();
        responseBoundaries();
        cancellationBoundaries();
        invalidImageBeforeIo();
        System.out.println("FitUsbTests: " + checks + " checks passed");
    }

    private static void imageTests(byte[] elite8, byte[] elite9) throws Exception {
        check(image.size() == 79692, "FIT exact size");
        check("unconfirmed".equals(FitFirmwareImage.VERSION_LABEL), "version must remain unconfirmed");
        check(FitFirmwareImage.EXPECTED_SHA256.equals(FirmwareImage.sha256(firmware)), "FIT exact pin");
        rejects(() -> FitFirmwareImage.validate(null));
        rejects(() -> FitFirmwareImage.validate(new byte[79692]));
        rejects(() -> FitFirmwareImage.validate(elite8));
        rejects(() -> FitFirmwareImage.validate(elite9));
        rejects(() -> FirmwareImage.validate(firmware));
        byte[] mutation = firmware.clone(); mutation[40] ^= 1;
        rejects(() -> FitFirmwareImage.validate(mutation));
        byte[] original = firmware.clone();
        FitFirmwareImage snapshot = FitFirmwareImage.validate(original);
        original[0] ^= 1;
        byte[] exported = snapshot.copyBytes(); exported[1] ^= 1;
        check(Arrays.equals(firmware, snapshot.copyBytes()), "snapshot must isolate both input and output mutations");
        TrackingInput chunks = new TrackingInput(firmware, 13, false);
        check(Arrays.equals(firmware, FitFirmwareImage.read(chunks).copyBytes()), "read all chunks despite available zero");
        check(!chunks.closed, "caller retains stream ownership");
        TrackingInput zeroReads = new TrackingInput(firmware, 37, true);
        check(Arrays.equals(firmware, FitFirmwareImage.read(zeroReads).copyBytes()), "zero-length reads cannot truncate image");
        rejects(() -> FitFirmwareImage.read(new ByteArrayInputStream(Arrays.copyOf(firmware, firmware.length - 1))));
        rejects(() -> FitFirmwareImage.read(new ByteArrayInputStream(Arrays.copyOf(firmware, firmware.length + 1))));
        rejects(() -> FitFirmwareImage.read(null));
    }

    private static void goldenTransfer() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeClock clock = new FakeClock();
        List<FitUsbEngine.Stage> stages = new ArrayList<>();
        FitUsbEngine engine = new FitUsbEngine(transport, (stage, index, total, message) -> {
            check(total == 1374, "progress data count");
            check(index >= 0 && index <= 1374, "progress index bounds");
            stages.add(stage);
        }, clock, () -> false);
        FitUsbEngine.Result result = engine.run(image);
        check(result.status == FitUsbEngine.Status.SUCCESS, "complete transfer succeeds");
        check(result.packetsSent == 1374 && result.packetsAcknowledged == 1374, "all data frames accounted");
        check(result.quitSent && result.flashMayHaveStarted, "Quit and flash flags");
        check(result.message.contains("not verified"), "positive USB data is not image verification");
        check(transport.writes.size() == 1375 && transport.reads == 1375, "one OUT and IN per packet plus Quit");
        check(clock.sleeps.isEmpty(), "no retry delays for immediate responses");
        check(stages.get(stages.size() - 1) == FitUsbEngine.Stage.COMPLETE, "completion progress last");
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        ByteArrayOutputStream reconstructed = new ByteArrayOutputStream();
        for (int i = 0; i < 1374; i++) {
            byte[] packet = transport.writes.get(i);
            check(packet.length == 64, "64-byte data frame");
            check(packet[2] == 58 && packet[3] == 2, "payload and type");
            check(((packet[4] & 255) | ((packet[5] & 255) << 8)) == i, "contiguous little-endian index");
            int sum = 0;
            for (int j = 0; j < 58; j++) {
                byte expected = firmware[i * 58 + j];
                check(packet[j + 6] == expected, "exact selected image payload");
                sum += expected & 255;
            }
            check(((packet[0] & 255) | ((packet[1] & 255) << 8)) == (sum & 65535), "independent payload sum");
            stream.write(packet);
            reconstructed.write(packet, 6, 58);
        }
        check(Arrays.equals(firmware, reconstructed.toByteArray()), "all image bytes reconstructed in order");
        check("3dbe8bcf1460a01c8f8a6a3604b79de6cbcc5e84470f93a1738e933dc4f87c83".equals(
                FirmwareImage.sha256(stream.toByteArray())), "independent offline golden data stream hash");
        check(hex(transport.writes.get(0)).equals("920b3a02000025a0000000000000000000000000000000000000000000004c370100000000004b4e4c5400003b17978107e09381817d978209e0938202fd1681"), "first packet golden");
        check(hex(transport.writes.get(1373)).equals("ff143a025d05038106c0c000003600002000000006000000ffffffffcc000000fffffffff7000000ffffffffff000000e755aa0dffed01000001010001000000"), "last packet golden");
        byte[] quit = new byte[64]; quit[3] = 3;
        check(Arrays.equals(quit, transport.writes.get(1374)), "exact final Quit after full data sequence");
        FitUsbEngine.Result again = engine.run(image);
        check(again.status == FitUsbEngine.Status.FAILED && transport.writes.size() == 1375, "engine cannot replay a completed session");
    }

    private static void failedWrites() {
        for (int returned : new int[] {-1, 0, 1, 63, 65}) {
            FakeTransport transport = new FakeTransport(); transport.writeResult = returned;
            FitUsbEngine.Result result = engine(transport, new FakeClock(), () -> false).run(image);
            check(result.status == FitUsbEngine.Status.INDETERMINATE, "partial or invalid write is uncertain");
            check(result.flashMayHaveStarted && result.packetsSent == 0 && result.packetsAcknowledged == 0, "no completed packet claimed");
            check(transport.writes.size() == 1 && transport.reads == 0 && !result.quitSent, "no read, retry or Quit after short OUT");
        }
        FakeTransport throwing = new FakeTransport(); throwing.writeThrows = true;
        FitUsbEngine.Result result = engine(throwing, new FakeClock(), () -> false).run(image);
        check(result.status == FitUsbEngine.Status.INDETERMINATE && result.flashMayHaveStarted, "write exception can follow partial delivery");
        check(throwing.writes.size() == 1 && throwing.reads == 0 && !result.quitSent, "exception is never retried");
        FakeTransport partialQuit = new FakeTransport(); partialQuit.shortWriteAt = 1375;
        result = engine(partialQuit, new FakeClock(), () -> false).run(image);
        check(result.status == FitUsbEngine.Status.INDETERMINATE && result.packetsAcknowledged == 1374, "short Quit preserves data count");
        check(!result.quitSent && partialQuit.writes.size() == 1375 && partialQuit.reads == 1374, "short Quit never repeated");
    }

    private static void responseBoundaries() {
        for (int returned : new int[] {0, -1}) {
            FakeTransport transport = new FakeTransport(); transport.responseResult = returned;
            FakeClock clock = new FakeClock();
            FitUsbEngine.Result result = engine(transport, clock, () -> false).run(image);
            check(result.status == FitUsbEngine.Status.INDETERMINATE && result.packetsSent == 1 && result.packetsAcknowledged == 0, "missing first response stops transfer");
            check(transport.reads == 30 && transport.writes.size() == 1 && !result.quitSent, "30 read attempts, no retransmission");
            check(clock.sleeps.size() == 30, "stock200ms after every failed read, including the last");
            for (long delay : clock.sleeps) check(delay == 200, "stock response delay");
        }
        FakeTransport late = new FakeTransport(); late.initialFailedReads = 29;
        FakeClock lateClock = new FakeClock();
        FitUsbEngine.Result result = engine(late, lateClock, () -> false).run(image);
        check(result.status == FitUsbEngine.Status.SUCCESS && late.reads == 1404, "30th response succeeds without retrying OUT");
        check(late.writes.size() == 1375 && lateClock.sleeps.size() == 29, "late response does not duplicate packet");
        FakeTransport quitTimeout = new FakeTransport(); quitTimeout.suppressQuitResponse = true;
        FakeClock quitClock = new FakeClock();
        result = engine(quitTimeout, quitClock, () -> false).run(image);
        check(result.status == FitUsbEngine.Status.INDETERMINATE && result.quitSent, "Quit timeout is uncertain despite full OUT");
        check(result.packetsSent == 1374 && result.packetsAcknowledged == 1374, "Quit timeout retains completed data count");
        check(quitTimeout.writes.size() == 1375 && quitTimeout.reads == 1404 && quitClock.sleeps.size() == 30, "Quit has stock final failure delay and no resend");
        FakeTransport impossible = new FakeTransport(); impossible.responseResult = 65;
        result = engine(impossible, new FakeClock(), () -> false).run(image);
        check(result.status == FitUsbEngine.Status.INDETERMINATE && result.packetsAcknowledged == 0, "invalid IN byte count is rejected");
        FakeTransport readError = new FakeTransport(); readError.readThrows = true;
        result = engine(readError, new FakeClock(), () -> false).run(image);
        check(result.status == FitUsbEngine.Status.INDETERMINATE && readError.reads == 1 && readError.writes.size() == 1, "read exception stops without extra commands");
        FakeTransport interrupted = new FakeTransport(); interrupted.readInterrupts = true;
        result = engine(interrupted, new FakeClock(), () -> false).run(image);
        check(result.status == FitUsbEngine.Status.INDETERMINATE && Thread.interrupted(), "interrupted read preserves thread signal");
    }

    private static void cancellationBoundaries() {
        FakeTransport before = new FakeTransport();
        FitUsbEngine.Result result = engine(before, new FakeClock(), () -> true).run(image);
        check(result.status == FitUsbEngine.Status.CANCELLED && !result.flashMayHaveStarted && before.writes.isEmpty(), "cancel before first OUT is safe");
        AtomicBoolean cancelled = new AtomicBoolean();
        FakeTransport afterWrite = new FakeTransport(); afterWrite.afterWrite = () -> cancelled.set(true);
        result = engine(afterWrite, new FakeClock(), cancelled::get).run(image);
        check(result.status == FitUsbEngine.Status.INDETERMINATE && result.packetsSent == 1 && afterWrite.reads == 0, "cancel after OUT stops before IN");
        check(!result.quitSent && afterWrite.writes.size() == 1, "no cleanup Quit on cancellation");
        cancelled.set(false);
        FakeTransport afterRead = new FakeTransport(); afterRead.afterRead = () -> cancelled.set(true);
        result = engine(afterRead, new FakeClock(), cancelled::get).run(image);
        check(result.status == FitUsbEngine.Status.INDETERMINATE && result.packetsAcknowledged == 1 && afterRead.writes.size() == 1, "cancel at data response boundary");
        cancelled.set(false);
        FakeTransport finalData = new FakeTransport();
        finalData.afterRead = () -> { if (finalData.reads == 1374) cancelled.set(true); };
        result = engine(finalData, new FakeClock(), cancelled::get).run(image);
        check(result.status == FitUsbEngine.Status.INDETERMINATE && result.packetsAcknowledged == 1374, "cancel after final data response");
        check(!result.quitSent && finalData.writes.size() == 1374, "cancel before Quit sends no Quit");
        cancelled.set(false);
        FakeTransport noResponse = new FakeTransport(); noResponse.responseResult = 0;
        FakeClock cancelDuringDelay = new FakeClock(); cancelDuringDelay.onSleep = () -> cancelled.set(true);
        result = engine(noResponse, cancelDuringDelay, cancelled::get).run(image);
        check(result.status == FitUsbEngine.Status.INDETERMINATE && noResponse.reads == 1 && noResponse.writes.size() == 1, "cancel during response delay stops before next read");
        Thread.currentThread().interrupt();
        FakeTransport preInterrupted = new FakeTransport();
        result = engine(preInterrupted, new FakeClock(), () -> false).run(image);
        check(result.status == FitUsbEngine.Status.CANCELLED && preInterrupted.writes.isEmpty() && Thread.interrupted(), "preexisting interrupt prevents I/O");
    }

    private static void invalidImageBeforeIo() {
        FakeTransport transport = new FakeTransport();
        FitUsbEngine.Result result = engine(transport, new FakeClock(), () -> false).run(null);
        check(result.status == FitUsbEngine.Status.FAILED && !result.flashMayHaveStarted && transport.writes.isEmpty(), "no unchecked image may reach I/O");
    }

    private static FitUsbEngine engine(FakeTransport transport, FakeClock clock, FitUsbEngine.Cancellation cancel) {
        return new FitUsbEngine(transport, null, clock, cancel);
    }

    private static final class FakeTransport implements FitUsbEngine.Transport {
        final List<byte[]> writes = new ArrayList<>();
        int reads;
        int writeResult = 64;
        int responseResult = 1; // Arbitrary one-byte RX satisfies stock policy, not semantic validation.
        int initialFailedReads;
        int shortWriteAt = -1;
        boolean writeThrows;
        boolean readThrows;
        boolean readInterrupts;
        boolean suppressQuitResponse;
        Runnable afterWrite;
        Runnable afterRead;

        @Override public int write(byte[] packet, int timeoutMillis) throws IOException {
            check(timeoutMillis == 500, "stock OUT timeout");
            writes.add(packet.clone());
            if (afterWrite != null) afterWrite.run();
            if (writeThrows) throw new IOException("test write failure");
            return writes.size() == shortWriteAt ? 63 : writeResult;
        }

        @Override public int read(byte[] buffer, int timeoutMillis) throws IOException, InterruptedException {
            check(timeoutMillis == 500 && buffer.length == 64, "stock IN timeout and buffer");
            reads++;
            if (readThrows) throw new IOException("test read failure");
            if (readInterrupts) throw new InterruptedException("test read interruption");
            if (afterRead != null) afterRead.run();
            buffer[0] = (byte) 0x9f;
            if (reads <= initialFailedReads || (suppressQuitResponse && writes.size() == 1375)) return 0;
            return responseResult;
        }
    }

    private static final class FakeClock implements FitUsbEngine.Clock {
        final List<Long> sleeps = new ArrayList<>();
        Runnable onSleep;
        @Override public void sleep(long millis) { sleeps.add(millis); if (onSleep != null) onSleep.run(); }
    }

    private static final class TrackingInput extends InputStream {
        private final byte[] bytes;
        private final int chunk;
        private final boolean alternateZero;
        private int offset;
        private int reads;
        boolean closed;
        TrackingInput(byte[] bytes, int chunk, boolean alternateZero) {
            this.bytes = bytes; this.chunk = chunk; this.alternateZero = alternateZero;
        }
        @Override public int available() { return 0; }
        @Override public int read() { return offset < bytes.length ? bytes[offset++] & 255 : -1; }
        @Override public int read(byte[] target, int start, int count) {
            if (offset == bytes.length) return -1;
            if (alternateZero && reads++ % 2 == 0) return 0;
            int size = Math.min(count, Math.min(chunk, bytes.length - offset));
            System.arraycopy(bytes, offset, target, start, size); offset += size; return size;
        }
        @Override public void close() { closed = true; }
    }

    private interface Throwing { void run() throws Exception; }
    private static void rejects(Throwing action) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | IOException expected) { rejected = true; }
        check(rejected, "invalid image must be rejected");
    }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
