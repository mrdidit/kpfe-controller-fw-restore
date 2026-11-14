package org.kpfe.restore.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** No Android or serial I/O. Test input must be the preserved, exact stock image. */
public final class CoreTests {
    private static int checks;
    private static byte[] stock;
    private static final byte[] GENERIC_RESPONSE = {(byte) 0x55, (byte) 0xaa, 0, 2};

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    private static final class Fake implements RestoreEngine.Transport, RestoreEngine.Clock {
        long now;
        int opens, closes, prepares, readCalls, dataWrites, entryWrites, quitWrites, readsBeforeFirstWrite = -1;
        boolean boot = true, application = true, initialBoot, initialApplication, fragment, failOpen, failClose;
        boolean partialWriteFailure, partialEntryFailure, partialQuitFailure, eof, interruptRead;
        boolean retireOnWrite;
        boolean failPrepare, cancelOnBootRead, cancelAfterPrepare;
        int omitResponse = -1, errorResponse = -1, lateResponse = -1, delayMs, everyResponseDelay;
        int lateDelayMs = 7500, writeCostMs, readsAtFirstData = -1, readsAfterLastResponse = -1;
        int eofAtPacket = -1, interruptAtPacket = -1;
        long failReadAtMillis = -1;
        String errorText = "ERROR: failed";
        byte[] responseBytes = GENERIC_RESPONSE;
        RestoreEngine engine;
        int cancelAtDataWrite = -1;
        long cancelAtMillis = -1;
        final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>();
        final List<byte[]> writes = new ArrayList<byte[]>();
        final List<String> order = new ArrayList<String>();
        final List<Long> dataAtMillis = new ArrayList<Long>();
        final List<Long> sleeps = new ArrayList<Long>();
        final List<Long> noticeAtMillis = new ArrayList<Long>();
        final BannerScanner observedBoot = new BannerScanner(RestoreEngine.BOOT_BANNER);
        long quitAtMillis = -1;

        public long nanoTime() { return now; }

        private void advance(long millis) {
            now += millis * 1000000L;
            delayMs = (int) Math.max(0, delayMs - millis);
            if (cancelAtMillis >= 0 && now / 1000000L >= cancelAtMillis) engine.cancel();
        }

        public void sleepMillis(long millis) {
            check(millis == 100 || millis == 500 || millis == 1000, "only source-derived settle/poll sleeps");
            sleeps.add(millis);
            advance(millis);
        }

        void enqueue(byte[] bytes) {
            if (fragment) for (byte value : bytes) queue.add(new byte[]{value});
            else queue.add(bytes.clone());
        }

        public void open() throws IOException {
            opens++;
            order.add("open");
            if (failOpen) throw new IOException("partial open failure");
            if (initialBoot) enqueue(ascii(RestoreEngine.BOOT_BANNER));
            if (initialApplication) enqueue(ascii(RestoreEngine.APPLICATION_BANNER));
        }

        public void prepareFirmwareTransfer() throws IOException {
            prepares++; order.add("prepare");
            check(prepares == 1 && observedBoot.found() && dataWrites == 0 && sleeps.isEmpty(),
                    "full preparation exactly once after received loader banner and before settle/data");
            if (failPrepare) throw new IOException("preparation reopen failure");
            if (cancelAfterPrepare) engine.cancel();
        }

        public int read(byte[] bytes, int offset, int length, int timeoutMs) throws IOException, InterruptedException {
            readCalls++;
            check(timeoutMs >= 0 && timeoutMs <= 100, "bounded polling including retained RX checks");
            if (cancelAtMillis >= 0 && now / 1000000L >= cancelAtMillis) engine.cancel();
            if (failReadAtMillis >= 0 && now / 1000000L >= failReadAtMillis) throw new IOException("injected RX failure");
            if (interruptRead) throw new InterruptedException("simulated interruption");
            if (eof) return -1;
            if (queue.isEmpty()) { advance(timeoutMs); return 0; }
            if (delayMs > 0) {
                int wait = Math.min(timeoutMs, delayMs);
                advance(wait);
                if (delayMs > 0) return 0;
            }
            byte[] response = queue.remove();
            int count = Math.min(response.length, length);
            System.arraycopy(response, 0, bytes, offset, count);
            if (count < response.length) queue.addFirst(Arrays.copyOfRange(response, count, response.length));
            if (dataWrites == 1010 && quitWrites == 0) readsAfterLastResponse = readCalls;
            if (observedBoot.feed(bytes, offset, count) && cancelOnBootRead) engine.cancel();
            return count;
        }

        public void write(byte[] bytes, int timeoutMs) throws IOException {
            check(timeoutMs > 0 && timeoutMs <= 2000, "bounded write");
            if (writes.isEmpty()) readsBeforeFirstWrite = readCalls;
            writes.add(bytes.clone());
            // Model the Android adapter's explicit retirement of its previous RX
            // generation, not a core/UART drain. Diagnostic tests leave this off.
            if (retireOnWrite) queue.clear();
            if (bytes.length == 11) {
                entryWrites++; order.add("entry");
                check(Arrays.equals(bytes, PacketCodec.bootEntry()), "entry exact");
                if (partialEntryFailure) throw new IOException("partial E7 write");
                if (boot) enqueue(ascii(RestoreEngine.BOOT_BANNER));
            } else if (bytes.length == 64 && bytes[3] == 2) {
                int index = (bytes[4] & 255) | ((bytes[5] & 255) << 8);
                check(index == dataWrites, "sequential packets without retry");
                dataAtMillis.add(now / 1000000L);
                if (dataWrites == 0) readsAtFirstData = readCalls;
                dataWrites++; order.add("packet");
                advance(writeCostMs);
                if (partialWriteFailure) throw new IOException("partial UART write");
                if (index == cancelAtDataWrite) engine.cancel();
                if (index == errorResponse) enqueue(ascii(errorText));
                else if (index != omitResponse) enqueue(responseBytes);
                delayMs = everyResponseDelay;
                if (index == lateResponse) delayMs = lateDelayMs;
                if (index == eofAtPacket) eof = true;
                if (index == interruptAtPacket) interruptRead = true;
            } else {
                quitWrites++; order.add("quit");
                quitAtMillis = now / 1000000L;
                if (dataWrites == 1010) check(readCalls == readsAfterLastResponse, "no drain/read between final response and Quit");
                check(Arrays.equals(bytes, PacketCodec.quitPacket()), "quit exact");
                if (partialQuitFailure) throw new IOException("partial Quit write");
                enqueue(GENERIC_RESPONSE); // Quit response alone must never establish success.
                if (application) enqueue(ascii(RestoreEngine.APPLICATION_BANNER));
            }
        }

        public void close() throws IOException {
            closes++; order.add("close");
            if (failClose) throw new IOException("cleanup failure");
        }
    }

    /** Timing/bytes from capture 04; hardware lifecycle remains an adapter concern. */
    private static final class RecordedTrace implements RestoreEngine.Transport, RestoreEngine.Clock {
        long now, entryAt = -1, quitAt = -1;
        int closes, writes, bootParts, prepares;
        boolean ackRead, appRead, missingBoot, partialQuit, failClose, cancelDuringBoot;
        RestoreEngine engine;
        final byte[][] boot = {ascii("firmware updata "), ascii("mode runing")};

        public long nanoTime() { return now * 1000000L; }
        public void open() { }
        public void prepareFirmwareTransfer() { prepares++; }
        public void close() throws IOException {
            closes++;
            if (failClose) throw new IOException("recorded-trace cleanup failed");
        }
        public int read(byte[] buffer, int offset, int length, int timeoutMs) {
            check(timeoutMs > 0 && timeoutMs <= 100, "trace read is bounded");
            if (cancelDuringBoot && entryAt >= 0 && now - entryAt >= 5300) engine.cancel();
            long availableAt = Long.MAX_VALUE;
            byte[] bytes = null;
            int part = 0;
            if (entryAt >= 0 && !ackRead) {
                availableAt = entryAt + 10; bytes = new byte[]{(byte)0xe7, 0x55, (byte)0xaa, 8, (byte)0xed}; part = 1;
            } else if (entryAt >= 0 && !missingBoot && bootParts < boot.length) {
                availableAt = entryAt + (bootParts == 0 ? 5400 : 5434); bytes = boot[bootParts]; part = 2;
            } else if (quitAt >= 0 && !appRead) {
                availableAt = quitAt + 387; bytes = ascii(RestoreEngine.APPLICATION_BANNER); part = 3;
            }
            if (availableAt > now + timeoutMs) { now += timeoutMs; return 0; }
            now = Math.max(now, availableAt);
            check(bytes != null && bytes.length <= length, "trace chunk fits");
            System.arraycopy(bytes, 0, buffer, offset, bytes.length);
            if (part == 1) ackRead = true;
            if (part == 2) bootParts++;
            if (part == 3) appRead = true;
            return bytes.length;
        }
        public void write(byte[] bytes, int timeoutMs) throws IOException {
            check(timeoutMs > 0 && timeoutMs <= 2000, "trace write is bounded");
            writes++;
            if (writes == 1) {
                check(Arrays.equals(bytes, PacketCodec.bootEntry()), "trace starts with exactly E7");
                entryAt = now;
            } else {
                check(writes == 2 && Arrays.equals(bytes, PacketCodec.quitPacket()), "trace only allows one Quit after E7");
                check(bootParts == boot.length, "generic E7 response and partial banner cannot authorize Quit");
                check(now - entryAt == 5434, "Quit follows complete boot banner without input drain or quiet wait");
                quitAt = now;
                if (partialQuit) throw new IOException("partial trace Quit");
            }
        }
    }

    private static RestoreEngine.Result run(Fake fake) {
        fake.retireOnWrite = true;
        final Fake observed = fake;
        fake.engine = new RestoreEngine(fake, new RestoreEngine.Listener() {
            public void onEvent(RestoreEngine.Event event) {
                if (event.phase == RestoreEngine.Phase.WAITING_FOR_RESPONSE_DELAYED)
                    observed.noticeAtMillis.add(observed.now / 1000000L);
            }
        }, fake);
        RestoreEngine.Result result = fake.engine.run(stock);
        Thread.interrupted(); // Leave the host test thread reusable after cancellation.
        check(fake.opens == 1 && fake.closes == 1, "one open/cleanup");
        check(fake.order.get(fake.order.size() - 1).equals("close"), "cleanup is last transport action");
        return result;
    }

    private static void codec() throws Exception {
        FirmwareImage image = FirmwareImage.validate(stock);
        check(PacketCodec.packetCount(image) == 1010, "1010 packets");
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        ByteArrayOutputStream reassembled = new ByteArrayOutputStream();
        for (int index = 0; index < 1010; index++) {
            byte[] packet = PacketCodec.dataPacket(image, index);
            int length = packet[2] & 255;
            check(packet.length == 64 && packet[3] == 2, "packet layout");
            check(length == (index == 1009 ? 46 : 58), "payload length");
            check(((packet[4] & 255) | ((packet[5] & 255) << 8)) == index, "LE sequence");
            int sum = 0;
            for (int i = 0; i < length; i++) sum += packet[i + 6] & 255;
            check(((packet[0] & 255) | ((packet[1] & 255) << 8)) == sum, "LE payload sum");
            for (int i = 6 + length; i < 64; i++) check(packet[i] == 0, "zero padding");
            stream.write(packet);
            reassembled.write(packet, 6, length);
        }
        check(Arrays.equals(stock, reassembled.toByteArray()), "exact reassembly");
        check(FirmwareImage.sha256(stream.toByteArray()).equals(
                "94e0b64b8f24d9b3e21ab16e85697e9820227fc96602ca645f72af526c24a9a7"),
                "independent saved vendor packet-stream hash");
        byte[] original = stock.clone();
        FirmwareImage isolated = FirmwareImage.validate(original);
        original[0] ^= 1;
        byte[] copy = isolated.copyBytes(); copy[1] ^= 1;
        check(Arrays.equals(stock, isolated.copyBytes()), "immutable validated snapshot");
        check(FirmwareImage.read(new ByteArrayInputStream(stock)).size() == stock.length, "full stream read");
        InputStream shortReads = new ByteArrayInputStream(stock) {
            public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(len, 7)); }
        };
        check(FirmwareImage.read(shortReads).size() == stock.length, "short stream reads");
        for (int size : new int[] {0, 1, stock.length - 1, stock.length + 1}) {
            try { FirmwareImage.read(new ByteArrayInputStream(new byte[size])); throw new AssertionError("wrong size accepted"); }
            catch (IOException | IllegalArgumentException expected) { checks++; }
        }
        try { PacketCodec.dataPacket(image, -1); throw new AssertionError("negative index"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { PacketCodec.dataPacket(image, 1010); throw new AssertionError("large index"); }
        catch (IllegalArgumentException expected) { checks++; }
    }

    private static void scanners() {
        byte[] banner = ascii(RestoreEngine.BOOT_BANNER);
        for (int split = 0; split <= banner.length; split++) {
            BannerScanner scanner = new BannerScanner(RestoreEngine.BOOT_BANNER);
            scanner.feed(ascii("noise"), 0, 5);
            scanner.feed(banner, 0, split);
            scanner.feed(banner, split, banner.length - split);
            check(scanner.found(), "every boot banner split");
        }
        BannerScanner overlap = new BannerScanner("ababac");
        byte[] input = ascii("ababababac-tail");
        check(overlap.feed(input, 0, input.length), "overlapping prefixes");
        BannerScanner hugeNoise = new BannerScanner(RestoreEngine.APPLICATION_BANNER);
        byte[] noise = new byte[1024];
        for (int i = 0; i < 10000; i++) hugeNoise.feed(noise, 0, noise.length);
        check(!hugeNoise.found(), "large noise does not match");
    }

    private static void sessions() {
        Fake happy = new Fake(); happy.fragment = true;
        RestoreEngine.Result result = run(happy);
        check(result.status == RestoreEngine.Status.SUCCESS && result.applicationBannerObserved, "fragmented successful session");
        check(result.packetsSent == 1010 && result.packetsAcknowledged == 1010 && result.quitSent, "all packets then quit");
        check(happy.entryWrites == 1 && happy.quitWrites == 1, "one entry and one quit");
        check(happy.prepares == 1 && happy.order.indexOf("prepare") > happy.order.indexOf("entry")
                        && happy.order.indexOf("prepare") < happy.order.indexOf("packet"),
                "full transfer orders entry, preparation, then first data packet");
        check(happy.sleeps.size() == 2 && happy.sleeps.get(0) == 100 && happy.sleeps.get(1) == 500,
                "full restore performs separate stock 100/500 ms settle waits");
        check(happy.dataAtMillis.get(0) == 1600 && happy.readsAtFirstData == happy.readsBeforeFirstWrite + RestoreEngine.BOOT_BANNER.length(),
                "settling consumes time but no RX after complete fragmented loader banner");

        Fake alreadyBoot = new Fake(); alreadyBoot.initialBoot = true;
        check(run(alreadyBoot).status == RestoreEngine.Status.SUCCESS && alreadyBoot.entryWrites == 0, "fresh initial boot banner skips entry");
        check(alreadyBoot.dataAtMillis.get(0) == 600 && alreadyBoot.readsAtFirstData == 1,
                "initially observed loader still settles without RX drain or E7");

        Fake absent = new Fake(); absent.boot = false;
        result = run(absent);
        check(result.status == RestoreEngine.Status.INDETERMINATE && absent.dataWrites == 0 && absent.entryWrites == 1,
                "missing banner after E7 is uncertain and sends no firmware");
        check(absent.now == 11000 * 1000000L && absent.sleeps.isEmpty(), "silent loader cannot bypass unchanged entry deadline");
        check(absent.prepares == 0, "missing loader banner cannot prepare transfer");

        Fake prepareFailure = new Fake(); prepareFailure.failPrepare = true;
        result = run(prepareFailure);
        check(result.status == RestoreEngine.Status.INDETERMINATE && prepareFailure.prepares == 1
                        && prepareFailure.dataWrites == 0 && prepareFailure.quitWrites == 0
                        && prepareFailure.sleeps.isEmpty() && !result.flashMayHaveStarted,
                "preparation I/O failure cleans up without settling/data/Quit");
        for (boolean cancelBefore : new boolean[] {true, false}) {
            Fake preparationCancel = new Fake(); preparationCancel.cancelOnBootRead = cancelBefore;
            preparationCancel.cancelAfterPrepare = !cancelBefore;
            result = run(preparationCancel);
            check(result.status == RestoreEngine.Status.CANCELLED
                            && preparationCancel.prepares == (cancelBefore ? 0 : 1)
                            && preparationCancel.dataWrites == 0 && preparationCancel.quitWrites == 0
                            && preparationCancel.sleeps.isEmpty() && !result.flashMayHaveStarted,
                    "cancellation immediately before/after preparation prevents firmware data and Quit");
        }

        Fake settleCancelled = new Fake(); settleCancelled.initialBoot = true; settleCancelled.cancelAtMillis = 300;
        result = run(settleCancelled);
        check(result.status == RestoreEngine.Status.CANCELLED && settleCancelled.dataWrites == 0
                        && settleCancelled.quitWrites == 0 && !result.flashMayHaveStarted,
                "cancellation during pre-transfer settling prevents the first data attempt");

        for (int missing : new int[] {0, 1, 1009}) {
            Fake lost = new Fake(); lost.omitResponse = missing;
            lost.cancelAtMillis = 70000;
            result = run(lost);
            check(result.status == RestoreEngine.Status.CANCELLED && result.flashMayHaveStarted, "silent response waits until explicit cancellation");
            check(lost.dataWrites == missing + 1 && lost.quitWrites == 0 && result.packetsAcknowledged == missing, "no retry or quit after missing response");
            check(lost.noticeAtMillis.size() == 3, "silence reports three nonfatal delayed-wait notices");
            long packetAt = lost.dataAtMillis.get(missing);
            check(lost.noticeAtMillis.get(0) - packetAt == 5500, "first delayed notice on first stock poll at or after five seconds");
            for (int i = 1; i < lost.noticeAtMillis.size(); i++)
                check(lost.noticeAtMillis.get(i) - lost.noticeAtMillis.get(i - 1) == 30000, "notice repeats every thirty seconds");
        }
        for (int delayed : new int[] {0, 505, 1009}) {
            Fake late = new Fake(); late.lateResponse = delayed;
            result = run(late);
            check(result.status == RestoreEngine.Status.SUCCESS && result.packetsAcknowledged == 1010 && late.quitWrites == 1,
                    "first/middle/last response beyond old deadlines completes without retry");
            check(late.noticeAtMillis.size() == 1, "late response produces one soft notice");
        }

        for (byte[] binary : new byte[][] {{0x66}, {(byte) 0xff}, {0}, {0x66, (byte) 0xff}}) {
            Fake response = new Fake(); response.responseBytes = binary;
            result = run(response);
            check(result.status == RestoreEngine.Status.SUCCESS && result.packetsAcknowledged == 1010,
                    "any nonempty binary response accepted without ASCII/prefix heuristic");
        }

        for (String text : new String[] {RestoreEngine.APPLICATION_BANNER, RestoreEngine.BOOT_BANNER,
                "ERROR: invalid", "failed", "TIMEOUT", "firm"}) {
            Fake error = new Fake(); error.errorResponse = 0; error.errorText = text; error.fragment = true;
            result = run(error);
            check(result.status == RestoreEngine.Status.SUCCESS && result.packetsAcknowledged == 1010,
                    "stock policy accepts nonempty text without assigning ACK/status meaning: " + text);
        }
        Fake oversized = new Fake(); oversized.errorResponse = 0;
        char[] excessive = new char[5000]; Arrays.fill(excessive, 'Z');
        oversized.errorText = new String(excessive);
        result = run(oversized);
        check(result.status == RestoreEngine.Status.SUCCESS && result.packetsAcknowledged == 1010,
                "response completion does not wait for entire chunk or invent response size validation");
        Fake prematureApplication = new Fake(); prematureApplication.errorResponse = 1009;
        prematureApplication.errorText = RestoreEngine.APPLICATION_BANNER;
        prematureApplication.application = false;
        result = run(prematureApplication);
        check(result.status == RestoreEngine.Status.INDETERMINATE && !result.applicationBannerObserved && prematureApplication.quitWrites == 1,
                "pre-Quit application text counts as unverified response but not post-Quit success");

        Fake noApplication = new Fake(); noApplication.application = false;
        result = run(noApplication);
        check(result.status == RestoreEngine.Status.INDETERMINATE && result.quitSent && !result.applicationBannerObserved,
                "quit response alone is not success");

        Fake cancelled = new Fake(); cancelled.cancelAtDataWrite = 5;
        result = run(cancelled);
        check(result.status == RestoreEngine.Status.CANCELLED && cancelled.dataWrites == 6 && cancelled.quitWrites == 0,
                "cancellation stops further TX");

        Fake interrupted = new Fake(); interrupted.interruptRead = true;
        result = run(interrupted);
        check(result.status == RestoreEngine.Status.CANCELLED && interrupted.writes.isEmpty(), "interruption before entry");
        Fake midInterrupt = new Fake(); midInterrupt.interruptAtPacket = 3;
        result = run(midInterrupt);
        check(result.status == RestoreEngine.Status.CANCELLED && midInterrupt.dataWrites == 4 && midInterrupt.quitWrites == 0,
                "interruption during transfer stops further TX");
        Fake midEof = new Fake(); midEof.eofAtPacket = 2;
        result = run(midEof);
        check(result.status == RestoreEngine.Status.INDETERMINATE && midEof.dataWrites == 3 && midEof.quitWrites == 0,
                "EOF during transfer stops further TX");

        Fake partial = new Fake(); partial.partialWriteFailure = true;
        result = run(partial);
        check(result.status == RestoreEngine.Status.INDETERMINATE && result.flashMayHaveStarted && partial.dataWrites == 1,
                "partial write failure cannot be treated as safe non-transfer");

        Fake openFailure = new Fake(); openFailure.failOpen = true;
        check(run(openFailure).status == RestoreEngine.Status.FAILED && openFailure.writes.isEmpty(), "partial open cleaned");
        Fake cleanupFailure = new Fake(); cleanupFailure.failClose = true;
        check(run(cleanupFailure).status == RestoreEngine.Status.INDETERMINATE, "cleanup failure blocks success");
        Fake closed = new Fake(); closed.eof = true;
        check(run(closed).status == RestoreEngine.Status.FAILED && closed.writes.isEmpty(), "EOF aborts");

        for (int responseDelay : new int[] {200, 750}) {
            Fake slow = new Fake(); slow.everyResponseDelay = responseDelay;
            result = run(slow);
            check(result.status == RestoreEngine.Status.SUCCESS && slow.dataWrites == 1010 && slow.quitWrites == 1,
                    "responsive full transfer completes beyond old cumulative deadline");
            check(slow.now > (responseDelay == 200 ? 180000L : 600000L) * 1000000L,
                    "test really crosses three minutes / ten minutes");
            check(slow.dataAtMillis.get(1) - slow.dataAtMillis.get(0) == (responseDelay == 200 ? 200 : 1500),
                    "early responses return immediately; late RX is retained until the one-second poll");
        }

        Fake slowWrite = new Fake(); slowWrite.writeCostMs = 800; slowWrite.everyResponseDelay = 100;
        result = run(slowWrite);
        check(result.status == RestoreEngine.Status.SUCCESS
                        && slowWrite.dataAtMillis.get(1) - slowWrite.dataAtMillis.get(0) == 1800,
                "initial 500 ms window includes close/open/write rather than restarting afterward");
        Fake latchedDuringWrite = new Fake(); latchedDuringWrite.writeCostMs = 800;
        result = run(latchedDuringWrite);
        check(result.status == RestoreEngine.Status.SUCCESS
                        && latchedDuringWrite.dataAtMillis.get(1) - latchedDuringWrite.dataAtMillis.get(0) == 800,
                "retained response after a long send advances immediately with no extra one-second sleep");

        Fake slowNoRestart = new Fake(); slowNoRestart.everyResponseDelay = 750; slowNoRestart.application = false;
        result = run(slowNoRestart);
        check(result.status == RestoreEngine.Status.INDETERMINATE && result.quitSent
                        && slowNoRestart.now / 1000000L - slowNoRestart.quitAtMillis == 10000,
                "post-Quit banner deadline remains ten seconds even after a transfer longer than ten minutes");

        Fake ioAfterSilence = new Fake(); ioAfterSilence.omitResponse = 2; ioAfterSilence.failReadAtMillis = 650000;
        result = run(ioAfterSilence);
        check(result.status == RestoreEngine.Status.INDETERMINATE && ioAfterSilence.dataWrites == 3
                        && ioAfterSilence.quitWrites == 0 && result.packetsAcknowledged == 2,
                "indefinite silence past ten minutes sends no retry/next/Quit; explicit I/O failure ends it");

        final Fake noticeFailure = new Fake(); noticeFailure.lateResponse = 0; noticeFailure.retireOnWrite = true;
        RestoreEngine noticeEngine = new RestoreEngine(noticeFailure, new RestoreEngine.Listener() {
            public void onEvent(RestoreEngine.Event event) {
                if (event.phase == RestoreEngine.Phase.WAITING_FOR_RESPONSE_DELAYED) throw new IllegalStateException("notice failure");
            }
        }, noticeFailure);
        check(noticeEngine.run(stock).status == RestoreEngine.Status.SUCCESS && noticeFailure.dataWrites == 1010,
                "soft notice listener exception cannot abort or advance transfer");

        final Fake listenerFailure = new Fake();
        RestoreEngine brokenListener = new RestoreEngine(listenerFailure, new RestoreEngine.Listener() {
            public void onEvent(RestoreEngine.Event event) {
                if (event.phase == RestoreEngine.Phase.SENDING) throw new IllegalStateException("listener failure");
            }
        }, listenerFailure);
        result = brokenListener.run(stock);
        check(result.status == RestoreEngine.Status.INDETERMINATE && listenerFailure.dataWrites == 0 && listenerFailure.closes == 1,
                "listener failure aborts before packet and cleans up");

        for (byte[] wrong : new byte[][] {null, new byte[5], new byte[FirmwareImage.EXPECTED_SIZE]}) {
            Fake fake = new Fake();
            result = new RestoreEngine(fake, null, fake).run(wrong);
            check(result.status == RestoreEngine.Status.FAILED && fake.opens == 0 && fake.closes == 0 && fake.writes.isEmpty(),
                    "invalid image causes no transport actions");
        }
        Fake preCancel = new Fake();
        RestoreEngine engine = new RestoreEngine(preCancel, null, preCancel);
        engine.cancel(); result = engine.run(stock); Thread.interrupted();
        check(result.status == RestoreEngine.Status.CANCELLED && preCancel.opens == 0, "pre-cancel no open");
        check(engine.run(stock).status == RestoreEngine.Status.FAILED && preCancel.opens == 0, "single-use engine");
    }

    private static RestoreEngine.Result entryOnly(Fake fake) {
        fake.engine = new RestoreEngine(fake, null, fake);
        RestoreEngine.Result result = fake.engine.testEntryOnly(stock);
        Thread.interrupted();
        check(fake.opens == 1 && fake.closes == 1, "entry-only cleanup");
        check(fake.prepares == 0, "entry-only never invokes full-transfer preparation");
        check(fake.dataWrites == 0 && result.packetsSent == 0 && result.packetsAcknowledged == 0
                && !result.flashMayHaveStarted, "entry-only never sends firmware data");
        for (byte[] write : fake.writes)
            check(Arrays.equals(write, PacketCodec.bootEntry()) || Arrays.equals(write, PacketCodec.quitPacket()),
                    "entry-only allowlisted writes");
        return result;
    }

    private static void entryTests() {
        Fake normal = new Fake(); normal.fragment = true;
        RestoreEngine.Result result = entryOnly(normal);
        check(result.status == RestoreEngine.Status.SUCCESS && result.bootloaderBannerObserved
                && result.applicationBannerObserved && result.quitSent, "entry-only both banners");
        check(normal.writes.size() == 2 && Arrays.equals(normal.writes.get(0), PacketCodec.bootEntry())
                && Arrays.equals(normal.writes.get(1), PacketCodec.quitPacket()), "entry-only exact E7 then Quit");

        Fake ready = new Fake(); ready.initialBoot = true;
        result = entryOnly(ready);
        check(result.status == RestoreEngine.Status.SUCCESS && result.bootloaderBannerObserved
                && ready.writes.size() == 1 && Arrays.equals(ready.writes.get(0), PacketCodec.quitPacket()),
                "fresh-loader entry-only exact Quit");

        Fake absent = new Fake(); absent.boot = false;
        result = entryOnly(absent);
        check(result.status == RestoreEngine.Status.INDETERMINATE && !result.bootloaderBannerObserved && !result.quitSent
                && absent.writes.size() == 1 && Arrays.equals(absent.writes.get(0), PacketCodec.bootEntry()),
                "missing entry banner is indeterminate with only E7 and never Quit");
        check(result.message.contains("may have changed controller state")
                && result.message.contains("no firmware data packets were sent"), "entry timeout explains controller uncertainty");

        Fake partialEntry = new Fake(); partialEntry.partialEntryFailure = true;
        result = entryOnly(partialEntry);
        check(result.status == RestoreEngine.Status.INDETERMINATE && !result.bootloaderBannerObserved
                && !result.quitSent && partialEntry.writes.size() == 1 && partialEntry.entryWrites == 1
                && result.message.contains("may have changed controller state"),
                "partial E7 failure is indeterminate without any later write");

        Fake noRestart = new Fake(); noRestart.application = false;
        result = entryOnly(noRestart);
        check(result.status == RestoreEngine.Status.INDETERMINATE && result.bootloaderBannerObserved
                && result.quitSent && !result.applicationBannerObserved && noRestart.writes.size() == 2,
                "entry-only missing restart remains indeterminate with no additional writes");

        Fake wrong = new Fake();
        result = new RestoreEngine(wrong, null, wrong).testEntryOnly(new byte[58568]);
        check(result.status == RestoreEngine.Status.FAILED && wrong.opens == 0 && wrong.writes.isEmpty(),
                "entry-only validates image before transport");

        final Fake cancelled = new Fake();
        final RestoreEngine[] engine = new RestoreEngine[1];
        engine[0] = new RestoreEngine(cancelled, new RestoreEngine.Listener() {
            public void onEvent(RestoreEngine.Event event) {
                if (event.phase == RestoreEngine.Phase.QUITTING) engine[0].cancel();
            }
        }, cancelled);
        result = engine[0].testEntryOnly(stock); Thread.interrupted();
        check(result.status == RestoreEngine.Status.CANCELLED && result.bootloaderBannerObserved
                && !result.quitSent && cancelled.closes == 1 && cancelled.dataWrites == 0
                && cancelled.writes.size() == 1 && Arrays.equals(cancelled.writes.get(0), PacketCodec.bootEntry()),
                "entry-only cancellation sends no later Quit or data");

        Fake cleanupFailure = new Fake(); cleanupFailure.failClose = true;
        result = entryOnly(cleanupFailure);
        check(result.status == RestoreEngine.Status.INDETERMINATE && result.applicationBannerObserved
                && result.message.contains("cleanup failed"), "entry cleanup failure cannot become successful diagnostic");
    }

    private static RestoreEngine.Result recordedEntry(RecordedTrace trace) {
        trace.engine = new RestoreEngine(trace, null, trace);
        RestoreEngine.Result result = trace.engine.testEntryOnly(stock);
        Thread.interrupted();
        check(trace.closes == 1, "recorded entry closes exactly once");
        check(trace.prepares == 0, "recorded no-flash entry never prepares firmware transfer");
        check(result.packetsSent == 0 && result.packetsAcknowledged == 0 && !result.flashMayHaveStarted,
                "recorded entry never transfers firmware");
        return result;
    }

    private static void capturedTimingTests() {
        check(RestoreEngine.BOOT_TIMEOUT_MS >= 10000, "entry accommodates observed delay with ten-second bound");
        RecordedTrace normal = new RecordedTrace();
        RestoreEngine.Result result = recordedEntry(normal);
        check(result.status == RestoreEngine.Status.SUCCESS && normal.writes == 2
                && result.bootloaderBannerObserved && result.applicationBannerObserved && result.quitSent,
                "captured 5434ms fragmented boot and 387ms application sequence succeeds");
        check(normal.now - normal.quitAt == 387 && normal.entryAt == 1000,
                "initial listen plus captured banner delays have no diagnostic drains");

        RecordedTrace missing = new RecordedTrace(); missing.missingBoot = true;
        result = recordedEntry(missing);
        check(result.status == RestoreEngine.Status.INDETERMINATE && missing.ackRead
                && missing.writes == 1 && !result.bootloaderBannerObserved && !result.quitSent
                && missing.now - missing.entryAt == RestoreEngine.BOOT_TIMEOUT_MS,
                "generic E7 ACK alone reaches bounded timeout without Quit");

        RecordedTrace interrupted = new RecordedTrace(); interrupted.cancelDuringBoot = true;
        result = recordedEntry(interrupted);
        check(result.status == RestoreEngine.Status.CANCELLED && interrupted.writes == 1
                && !result.bootloaderBannerObserved && !result.quitSent,
                "cancellation just before delayed boot banner prevents all further TX");

        RecordedTrace partial = new RecordedTrace(); partial.partialQuit = true;
        result = recordedEntry(partial);
        check(result.status == RestoreEngine.Status.INDETERMINATE && partial.writes == 2
                && !result.quitSent && !result.applicationBannerObserved,
                "partial Quit is not retried or treated as successful exit");

        RecordedTrace cleanup = new RecordedTrace(); cleanup.failClose = true;
        result = recordedEntry(cleanup);
        check(result.status == RestoreEngine.Status.INDETERMINATE && cleanup.writes == 2
                && result.applicationBannerObserved, "captured banners cannot override cleanup failure");
    }

    private static RestoreEngine.Result exitOnly(Fake fake) {
        fake.engine = new RestoreEngine(fake, null, fake);
        RestoreEngine.Result result = fake.engine.exitOnly(stock);
        Thread.interrupted();
        check(fake.opens == 1 && fake.closes == 1, "exit-only cleanup");
        check(fake.prepares == 0, "exit-only never invokes full-transfer preparation");
        check(fake.entryWrites == 0 && fake.dataWrites == 0 && result.packetsSent == 0
                && result.packetsAcknowledged == 0 && !result.flashMayHaveStarted, "exit-only never E7 or data");
        check(fake.writes.size() == 1 && Arrays.equals(fake.writes.get(0), PacketCodec.quitPacket()),
                "exit-only exact single Quit");
        check(fake.readsBeforeFirstWrite == 0, "exit-only performs no pre-Quit read or drain");
        return result;
    }

    private static void exitTests() {
        Fake normal = new Fake(); normal.fragment = true;
        RestoreEngine.Result result = exitOnly(normal);
        check(result.status == RestoreEngine.Status.SUCCESS && result.quitSent && result.applicationBannerObserved
                && !result.bootloaderBannerObserved, "exit-only application banner received after Quit without boot proof");

        Fake ready = new Fake(); ready.initialBoot = true;
        result = exitOnly(ready);
        check(result.status == RestoreEngine.Status.SUCCESS && !result.bootloaderBannerObserved,
                "exit-only does not listen for boot proof before writing Quit");

        Fake noRestart = new Fake(); noRestart.application = false;
        result = exitOnly(noRestart);
        check(result.status == RestoreEngine.Status.INDETERMINATE && result.quitSent && !result.applicationBannerObserved,
                "exit-only opaque response is not success");

        Fake stale = new Fake(); stale.initialApplication = true; stale.application = false;
        result = exitOnly(stale);
        check(result.applicationBannerObserved && result.message.contains("controls still require verification")
                && !result.message.contains("restart observed"),
                "un-timestamped buffered startup is only a received-banner observation, not reset or control proof");

        Fake wrong = new Fake();
        result = new RestoreEngine(wrong, null, wrong).exitOnly(new byte[58568]);
        check(result.status == RestoreEngine.Status.FAILED && wrong.opens == 0 && wrong.writes.isEmpty(),
                "exit-only validates pinned image before ownership");
    }

    private static RestoreEngine.Result guided(Fake fake, RestoreEngine.Listener listener,
                                               RestoreEngine.ExitApproval approval) {
        fake.engine = new RestoreEngine(fake, listener, fake);
        RestoreEngine.Result result = fake.engine.guidedEntryTest(stock, approval);
        Thread.interrupted();
        check(fake.opens == 1 && fake.closes == 1, "guided test cleanup");
        check(fake.prepares == 0, "guided test never invokes full-transfer preparation");
        check(fake.entryWrites == 0 && fake.dataWrites == 0 && result.packetsSent == 0
                && result.packetsAcknowledged == 0 && !result.flashMayHaveStarted,
                "guided test never E7 or firmware data");
        check(fake.writes.size() <= 1, "guided test never retries Quit");
        for (byte[] write : fake.writes)
            check(Arrays.equals(write, PacketCodec.quitPacket()), "guided test only stock Quit allowed");
        check(fake.now <= RestoreEngine.SESSION_TIMEOUT_MS * 1000000L, "guided session deadline bounded");
        return result;
    }

    private static RestoreEngine.ExitApproval approval(final boolean approved) {
        return new RestoreEngine.ExitApproval() { public boolean approved() { return approved; } };
    }

    private static void guidedTests() {
        final Fake delayed = new Fake(); delayed.initialBoot = true; delayed.fragment = true; delayed.delayMs = 12000;
        final long[] waitingAt = {-1};
        final int[] approvalCalls = {0};
        RestoreEngine.Result result = guided(delayed, new RestoreEngine.Listener() {
            public void onEvent(RestoreEngine.Event event) {
                check(event.packetCount == 0 && event.packetIndex == -1, "guided progress contains no firmware packets");
                if (event.phase == RestoreEngine.Phase.WAITING_FOR_EXIT) {
                    check(delayed.now >= 12000L * 1000000L && delayed.writes.isEmpty(),
                            "WAITING_FOR_EXIT only after delayed fresh banner and before any TX");
                    waitingAt[0] = delayed.now;
                }
                if (event.phase == RestoreEngine.Phase.QUITTING)
                    check(waitingAt[0] >= 0 && delayed.now - waitingAt[0] >= 700000000L,
                            "Quit only after approval delay");
            }
        }, new RestoreEngine.ExitApproval() {
            public boolean approved() {
                approvalCalls[0]++;
                check(waitingAt[0] >= 0, "approval queried only after WAITING_FOR_EXIT");
                check(delayed.writes.isEmpty(), "no TX while waiting for approval");
                return delayed.now - waitingAt[0] >= 700000000L;
            }
        });
        check(result.status == RestoreEngine.Status.SUCCESS && result.bootloaderBannerObserved
                && result.quitSent && result.applicationBannerObserved && delayed.writes.size() == 1
                && approvalCalls[0] > 1, "delayed fragmented guided entry and approved single Quit");

        final Fake absent = new Fake();
        result = guided(absent, new RestoreEngine.Listener() {
            public void onEvent(RestoreEngine.Event event) {
                check(event.phase != RestoreEngine.Phase.WAITING_FOR_EXIT, "no approval phase without boot banner");
            }
        }, new RestoreEngine.ExitApproval() {
            public boolean approved() { throw new AssertionError("must not query approval without boot banner"); }
        });
        check(result.status == RestoreEngine.Status.FAILED && !result.bootloaderBannerObserved && absent.writes.isEmpty()
                && absent.now == RestoreEngine.GUIDED_BOOT_TIMEOUT_MS * 1000000L
                && result.message.contains("no controller bytes sent") && result.message.contains("entry not confirmed"),
                "guided missing banner times out without any TX or implied entry");

        Fake noApproval = new Fake(); noApproval.initialBoot = true;
        result = guided(noApproval, null, approval(false));
        check(result.status == RestoreEngine.Status.INDETERMINATE && result.bootloaderBannerObserved && !result.quitSent
                && noApproval.writes.isEmpty() && noApproval.now == RestoreEngine.EXIT_APPROVAL_TIMEOUT_MS * 1000000L,
                "guided approval timeout leaves known entry indeterminate and sends nothing");

        Fake missingApplication = new Fake(); missingApplication.initialBoot = true; missingApplication.application = false;
        result = guided(missingApplication, null, approval(true));
        check(result.status == RestoreEngine.Status.INDETERMINATE && result.quitSent && !result.applicationBannerObserved
                && missingApplication.quitWrites == 1, "guided Quit response alone does not confirm restart");

        Fake partialQuit = new Fake(); partialQuit.initialBoot = true; partialQuit.partialQuitFailure = true;
        result = guided(partialQuit, null, approval(true));
        check(result.status == RestoreEngine.Status.INDETERMINATE && !result.quitSent && partialQuit.quitWrites == 1
                && !result.applicationBannerObserved, "guided partial Quit never retried and remains uncertain");

        Fake cancelBeforeBanner = new Fake(); cancelBeforeBanner.cancelAtMillis = 500;
        result = guided(cancelBeforeBanner, null, approval(true));
        check(result.status == RestoreEngine.Status.CANCELLED && !result.bootloaderBannerObserved
                && cancelBeforeBanner.writes.isEmpty(), "guided cancellation before banner sends nothing");

        Fake cancelApproval = new Fake(); cancelApproval.initialBoot = true; cancelApproval.cancelAtMillis = 500;
        result = guided(cancelApproval, null, approval(false));
        check(result.status == RestoreEngine.Status.INDETERMINATE && result.bootloaderBannerObserved
                && cancelApproval.writes.isEmpty(), "guided cancellation awaiting approval preserves uncertain controller state");

        final Fake cancelBeforeQuit = new Fake(); cancelBeforeQuit.initialBoot = true;
        result = guided(cancelBeforeQuit, new RestoreEngine.Listener() {
            public void onEvent(RestoreEngine.Event event) {
                if (event.phase == RestoreEngine.Phase.QUITTING) cancelBeforeQuit.engine.cancel();
            }
        }, approval(true));
        check(result.status == RestoreEngine.Status.INDETERMINATE && !result.quitSent && cancelBeforeQuit.writes.isEmpty(),
                "guided cancellation after approval still prevents Quit");

        Fake staleApplication = new Fake(); staleApplication.initialBoot = true; staleApplication.initialApplication = true;
        result = guided(staleApplication, null, approval(false));
        check(result.status == RestoreEngine.Status.INDETERMINATE && !result.applicationBannerObserved
                && staleApplication.writes.isEmpty(), "application startup during approval is not post-Quit success");

        Fake staleAtApproval = new Fake(); staleAtApproval.initialBoot = true; staleAtApproval.initialApplication = true;
        result = guided(staleAtApproval, null, approval(true));
        check(result.applicationBannerObserved && staleAtApproval.quitWrites == 1
                && result.message.contains("controls still require verification"),
                "guided immediate approval does not drain input or claim buffered startup proves fresh reset");

        Fake cleanupFailure = new Fake(); cleanupFailure.initialBoot = true; cleanupFailure.failClose = true;
        result = guided(cleanupFailure, null, approval(true));
        check(result.status == RestoreEngine.Status.INDETERMINATE && result.applicationBannerObserved,
                "guided cleanup failure prevents success even after both banners");

        Fake wrong = new Fake();
        RestoreEngine engine = new RestoreEngine(wrong, null, wrong);
        result = engine.guidedEntryTest(new byte[58568], approval(true));
        check(result.status == RestoreEngine.Status.FAILED && wrong.opens == 0 && wrong.writes.isEmpty(),
                "guided wrong firmware rejected before ownership");
        check(engine.testEntryOnly(stock).status == RestoreEngine.Status.FAILED && wrong.opens == 0,
                "guided engine cannot be reused for another mode");
        Fake missingProvider = new Fake();
        result = new RestoreEngine(missingProvider, null, missingProvider).guidedEntryTest(stock, null);
        check(result.status == RestoreEngine.Status.FAILED && missingProvider.opens == 0,
                "guided missing approval provider rejected before ownership");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the preserved stock firmware file");
        stock = Files.readAllBytes(Paths.get(args[0]));
        codec(); scanners(); sessions(); entryTests(); capturedTimingTests(); exitTests(); guidedTests();
        System.out.println("PASS: " + checks + " checks; source-derived packets and simulated transport only, no hardware access");
    }
}
