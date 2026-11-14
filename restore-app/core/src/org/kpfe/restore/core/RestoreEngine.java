package org.kpfe.restore.core;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-use blocking protocol engine; the adapter owns UART handoff and lifecycle. */
public final class RestoreEngine {
    public static final String BOOT_BANNER = "firmware updata mode runing";
    public static final String APPLICATION_BANNER = "osKernel Start...\r\n";
    public static final int WRITE_TIMEOUT_MS = 2000;
    public static final int RESPONSE_WINDOW_MS = 500;
    public static final int RESPONSE_POLL_MS = 1000;
    public static final int RESPONSE_NOTICE_MS = 5000;
    public static final int RESPONSE_NOTICE_REPEAT_MS = 30000;
    // The stock-native entry capture observed its banner 5,434 ms after E7.
    public static final int BOOT_TIMEOUT_MS = 10000;
    public static final int APPLICATION_TIMEOUT_MS = 10000;
    public static final int GUIDED_BOOT_TIMEOUT_MS = 90000;
    public static final int EXIT_APPROVAL_TIMEOUT_MS = 60000;
    public static final int SESSION_TIMEOUT_MS = 180000;
    private static final int READ_SLICE_MS = 100;

    public interface Transport {
        /**
         * Acquire the app's reader after the stock advisory handoff and configure
         * transport. GameWindow may retain its descriptor; this does not establish
         * exclusive FD ownership or prove receiver delivery. Undo partial open in close.
         */
        void open() throws IOException, InterruptedException;
        /**
         * Full restore only, after the loader banner: stock G4's preparation
         * close/open before the 100 + 500 ms settling waits. Send no bytes and
         * do not drain RX. Native adapters override; nonnative transports may omit.
         */
        default void prepareFirmwareTransfer() throws IOException, InterruptedException { }
        /**
         * Return 0 for no data, -1 for EOF. Honor the timeout; timeoutMillis == 0
         * checks already retained RX without waiting. The adapter's reader remains
         * active while core sleeps between checks. This must not flush/drain input.
         */
        int read(byte[] buffer, int offset, int length, int timeoutMillis) throws IOException, InterruptedException;
        /** Complete this single write, or throw; must never replay a partially sent packet. */
        void write(byte[] bytes, int timeoutMillis) throws IOException, InterruptedException;
        /** Bounded, idempotent cleanup: close UART before restoring stock reader ownership. */
        void close() throws IOException;
    }

    public interface Clock {
        long nanoTime();
        /** Overridden by deterministic host tests; production sleeps monotonically measured time. */
        default void sleepMillis(long millis) throws InterruptedException { Thread.sleep(millis); }
    }
    public interface Listener { void onEvent(Event event); }
    /** Thread-safe, nonblocking approval specific to this session; queried only after WAITING_FOR_EXIT. */
    public interface ExitApproval { boolean approved(); }
    public enum Phase { VALIDATING, OPENING, LISTENING, ENTERING_BOOTLOADER, WAITING_FOR_BOOTLOADER,
        SENDING, WAITING_FOR_RESPONSE, WAITING_FOR_RESPONSE_DELAYED, WAITING_FOR_EXIT,
        QUITTING, WAITING_FOR_APPLICATION, CLEANUP, COMPLETE, STOPPED }
    public enum Status { SUCCESS, FAILED, INDETERMINATE, CANCELLED }

    public static final class Event {
        public final Phase phase;
        public final String message;
        /** Zero-based current index, or -1 outside data transfer. */
        public final int packetIndex;
        public final int packetCount;
        Event(Phase phase, String message, int packetIndex, int packetCount) {
            this.phase = phase; this.message = message;
            this.packetIndex = packetIndex; this.packetCount = packetCount;
        }
    }

    public static final class Result {
        public final Status status;
        public final String message;
        /** Acknowledged counts unverified nonempty RX events, not verified flash writes. */
        public final int packetsSent, packetsAcknowledged;
        public final boolean quitSent, applicationBannerObserved, flashMayHaveStarted, bootloaderBannerObserved;
        Result(Status status, String message, int sent, int acknowledged, boolean quit,
               boolean application, boolean mayHaveStarted, boolean bootloader) {
            this.status = status; this.message = message; packetsSent = sent;
            packetsAcknowledged = acknowledged; quitSent = quit;
            applicationBannerObserved = application; flashMayHaveStarted = mayHaveStarted;
            bootloaderBannerObserved = bootloader;
        }
    }

    private final Transport transport;
    private final Listener listener;
    private final Clock clock;
    private final AtomicBoolean used = new AtomicBoolean();
    private volatile boolean cancelled;
    private boolean firmwareAttempted;
    private int packetIndex = -1, packetCount;
    private long sessionStart;

    public RestoreEngine(Transport transport, Listener listener) {
        this(transport, listener, new Clock() { public long nanoTime() { return System.nanoTime(); } });
    }

    public RestoreEngine(Transport transport, Listener listener, Clock clock) {
        if (transport == null || clock == null) throw new IllegalArgumentException("Missing transport or clock");
        this.transport = transport; this.listener = listener; this.clock = clock;
    }

    public void cancel() { cancelled = true; }

    public Result run(byte[] suppliedFirmware) {
        return execute(suppliedFirmware, false, false);
    }

    /** Entry/exit diagnostic only. This path never builds or sends UpdatePack data. */
    public Result testEntryOnly(byte[] suppliedFirmware) {
        return execute(suppliedFirmware, true, false);
    }

    /** Narrow exit recovery: one stock Quit only; never E7 or any UpdatePack data. */
    public Result exitOnly(byte[] suppliedFirmware) {
        return execute(suppliedFirmware, false, true);
    }

    /**
     * Observe externally guided entry, then await explicit approval for one stock Quit.
     * Never sends E7 or firmware data. This does not establish hardware compatibility.
     */
    public Result guidedEntryTest(byte[] suppliedFirmware, ExitApproval approval) {
        if (!used.compareAndSet(false, true))
            return new Result(Status.FAILED, "This restore session has already been used", 0, 0, false, false, false, false);
        sessionStart = clock.nanoTime();
        packetCount = 0;
        boolean openAttempted = false, bootloader = false, quitAttempted = false, quit = false, application = false;
        Status status = Status.FAILED;
        String message = "Guided entry test did not start";
        try {
            checkCancelled();
            emit(Phase.VALIDATING, "Checking the pinned stock Elite image");
            FirmwareImage.validate(suppliedFirmware);
            if (approval == null) throw new IllegalArgumentException("Missing exit approval provider");
            checkCancelled();
            emit(Phase.OPENING, "Preparing controller transport after stock reader handoff");
            checkCancelled();
            openAttempted = true;
            transport.open();
            checkCancelled();
            emit(Phase.LISTENING, "Waiting up to 90 seconds to receive an externally triggered bootloader banner; no commands sent");
            bootloader = waitForBanner(BOOT_BANNER, GUIDED_BOOT_TIMEOUT_MS);
            if (!bootloader)
                throw new IOException("Bootloader banner not received; no controller bytes sent and entry not confirmed");
            emit(Phase.WAITING_FOR_EXIT, "Bootloader banner received; waiting up to 60 seconds for approval to send one stock exit packet");
            if (!waitForExitApproval(approval))
                throw new IOException("Exit approval timed out; bootloader banner was observed, but no controller bytes were sent and controller state remains uncertain");
            emit(Phase.QUITTING, "Sending the approved stock exit packet once");
            checkCancelled();
            quitAttempted = true;
            send(PacketCodec.quitPacket());
            quit = true;
            emit(Phase.WAITING_FOR_APPLICATION, "Waiting to receive the application startup banner after the exit packet");
            application = waitForBanner(APPLICATION_BANNER, APPLICATION_TIMEOUT_MS);
            status = application ? Status.SUCCESS : Status.INDETERMINATE;
            message = application
                    ? "Bootloader and application banners received; only one stock exit packet sent, no E7 or firmware data; controls still require verification"
                    : "Approved stock exit packet sent, but application startup was not observed; controller state remains uncertain";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            status = bootloader || quitAttempted ? Status.INDETERMINATE : Status.CANCELLED;
            message = bootloader || quitAttempted
                    ? "Stopped after bootloader entry was observed; controller state remains uncertain and no further bytes were sent"
                    : "Stopped without entry confirmation; no controller bytes sent";
        } catch (IOException | RuntimeException error) {
            status = bootloader || quitAttempted ? Status.INDETERMINATE : Status.FAILED;
            message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        } finally {
            if (openAttempted) {
                emitSafely(Phase.CLEANUP, "Closing transport before releasing stock reader ownership");
                try { transport.close(); }
                catch (IOException | RuntimeException cleanupError) {
                    if (status == Status.SUCCESS) status = Status.INDETERMINATE;
                    message += "; transport cleanup failed: " + cleanupError.getMessage();
                }
            }
        }
        emitSafely(status == Status.SUCCESS ? Phase.COMPLETE : Phase.STOPPED, message);
        return new Result(status, message, 0, 0, quit, application, false, bootloader);
    }

    private boolean waitForExitApproval(ExitApproval approval) throws IOException, InterruptedException {
        long start = clock.nanoTime();
        BannerScanner application = new BannerScanner(APPLICATION_BANNER);
        byte[] buffer = new byte[256];
        while (remainingMillis(start, EXIT_APPROVAL_TIMEOUT_MS) > 0) {
            checkCancelled();
            boolean approved = approval.approved();
            checkCancelled();
            if (approved) return true;
            int count = read(buffer, Math.min(READ_SLICE_MS, remainingMillis(start, EXIT_APPROVAL_TIMEOUT_MS)));
            if (count > 0 && application.feed(buffer, 0, count))
                throw new IOException("Application startup observed before exit approval; controller state changed and no exit packet sent");
        }
        return false;
    }

    private Result execute(byte[] suppliedFirmware, boolean entryOnly, boolean exitOnly) {
        if (!used.compareAndSet(false, true))
            return new Result(Status.FAILED, "This restore session has already been used", 0, 0, false, false, false, false);
        sessionStart = clock.nanoTime();
        int sent = 0, acknowledged = 0;
        boolean openAttempted = false, flashMayHaveStarted = false, quit = false, application = false, bootloader = false;
        boolean quitAttempted = false;
        boolean entryAttempted = false;
        Status status = Status.FAILED;
        String message = "Restore did not start";
        try {
            checkCancelled();
            emit(Phase.VALIDATING, "Checking the pinned stock Elite image");
            FirmwareImage image = FirmwareImage.validate(suppliedFirmware);
            packetCount = entryOnly || exitOnly ? 0 : PacketCodec.packetCount(image);
            checkCancelled();
            emit(Phase.OPENING, "Preparing controller transport after stock reader handoff");
            checkCancelled();
            openAttempted = true;
            transport.open();
            checkCancelled();
            if (!exitOnly) {
            emit(Phase.LISTENING, "Listening for a bootloader banner on the acquired transport");
            bootloader = waitForBanner(BOOT_BANNER, 1000);
            if (!bootloader) {
                emit(Phase.ENTERING_BOOTLOADER, "Requesting stock bootloader entry once");
                checkCancelled();
                // Even a partial E7 write can leave the controller's update state uncertain.
                entryAttempted = true;
                send(PacketCodec.bootEntry());
                emit(Phase.WAITING_FOR_BOOTLOADER, "Waiting up to 10 seconds to receive the bootloader banner");
                bootloader = waitForBanner(BOOT_BANNER, BOOT_TIMEOUT_MS);
                if (!bootloader)
                    throw new IOException("Bootloader banner not observed; no firmware packets sent");
            }
            // Settings149 G4 schedules its preparation close/open, waits 100 ms,
            // prepares the image, then waits 500 ms before transfer. Our adapter
            // finishes that reopen synchronously before these two waits.
            if (!entryOnly) {
                checkCancelled();
                transport.prepareFirmwareTransfer();
                checkCancelled();
                sleep(100);
                sleep(500);
            }
            for (packetIndex = 0; packetIndex < packetCount; packetIndex++) {
                checkCancelled();
                emit(Phase.SENDING, "Sending stock firmware packet " + (packetIndex + 1) + " of " + packetCount);
                checkCancelled();
                byte[] packet = PacketCodec.dataPacket(image, packetIndex);
                // Stock CmdSender's 500 ms window includes its close/open/send.
                long responseStarted = clock.nanoTime();
                // A write failure may still have delivered a prefix and triggered erase/programming.
                flashMayHaveStarted = true;
                firmwareAttempted = true;
                send(packet);
                sent++;
                emit(Phase.WAITING_FOR_RESPONSE, "Waiting for an unverified vendor-style response");
                waitForResponse(responseStarted);
                acknowledged++;
            }
            packetIndex = -1;
            }
            emit(Phase.QUITTING, "Sending the stock exit packet once");
            checkCancelled();
            quitAttempted = true;
            send(PacketCodec.quitPacket());
            quit = true;
            emit(Phase.WAITING_FOR_APPLICATION, "Waiting to receive the controller application startup banner");
            application = waitForBanner(APPLICATION_BANNER, APPLICATION_TIMEOUT_MS);
            if (application) {
                status = Status.SUCCESS;
                message = exitOnly ? "Stock exit packet sent and application banner received; no firmware data packets sent; controls still require verification"
                        : entryOnly ? "Bootloader and application banners received; no firmware data packets sent; controls still require verification"
                        : "Application startup banner observed after transfer; flash contents were not read back";
            } else {
                status = Status.INDETERMINATE;
                message = exitOnly ? "Stock exit packet sent, but application startup was not observed; controller state remains uncertain"
                        : entryOnly ? "Bootloader entry observed and exit packet sent, but application startup was not observed"
                        : "Transfer ended, but the application startup banner was not observed; do not retry automatically";
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            status = Status.CANCELLED;
            message = entryAttempted && !flashMayHaveStarted
                    ? "Stopped after a boot-entry attempt; no firmware data packets were sent, but controller state may have changed"
                    : flashMayHaveStarted || bootloader || quitAttempted ? "Stopped; controller state is uncertain and no further packets were sent"
                    : "Stopped before firmware transfer";
        } catch (IOException | RuntimeException error) {
            status = flashMayHaveStarted || bootloader || quitAttempted || entryAttempted
                    ? Status.INDETERMINATE : Status.FAILED;
            message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            if (entryAttempted && !bootloader && !flashMayHaveStarted)
                message += "; no firmware data packets were sent, but bootloader entry may have changed controller state";
        } finally {
            if (openAttempted) {
                emitSafely(Phase.CLEANUP, "Closing transport before releasing stock reader ownership");
                try { transport.close(); }
                catch (IOException | RuntimeException cleanupError) {
                    if (status == Status.SUCCESS) status = Status.INDETERMINATE;
                    message += "; transport cleanup failed: " + cleanupError.getMessage();
                }
            }
        }
        emitSafely(status == Status.SUCCESS ? Phase.COMPLETE : Phase.STOPPED, message);
        return new Result(status, message, sent, acknowledged, quit, application, flashMayHaveStarted, bootloader);
    }

    private void send(byte[] data) throws IOException, InterruptedException {
        checkCancelled();
        transport.write(data, firmwareAttempted ? WRITE_TIMEOUT_MS
                : Math.min(WRITE_TIMEOUT_MS, remainingMillis(sessionStart, SESSION_TIMEOUT_MS)));
    }

    private boolean waitForBanner(String expected, int timeoutMs) throws IOException, InterruptedException {
        // A new matcher excludes bytes already consumed by an earlier matcher.
        // It cannot establish when bytes still buffered by the transport arrived,
        // prove a new reset, or verify physical controls. No RX flush is performed.
        BannerScanner scanner = new BannerScanner(expected);
        long start = clock.nanoTime();
        byte[] buffer = new byte[256];
        while (remainingMillis(start, timeoutMs) > 0) {
            int count = read(buffer, Math.min(READ_SLICE_MS, remainingMillis(start, timeoutMs)));
            if (count > 0 && scanner.feed(buffer, 0, count)) return true;
        }
        return false;
    }

    private void waitForResponse(long start) throws IOException, InterruptedException {
        byte[] buffer = new byte[256];
        while (remainingMillis(start, RESPONSE_WINDOW_MS) > 0) {
            if (read(buffer, Math.min(READ_SLICE_MS, remainingMillis(start, RESPONSE_WINDOW_MS))) > 0) return;
        }
        // Stock retains its transfer callback after CmdSender's 500 ms coroutine
        // timeout, then checks its received flag every second without retry/abort.
        // Our adapter retains RX bytes instead of that flag. ANY nonempty chunk is
        // equivalent evidence here; status, sequence and byte meanings are unknown.
        long nextNotice = RESPONSE_NOTICE_MS;
        while (true) {
            if (read(buffer, 0) > 0) return;
            long elapsed = (clock.nanoTime() - start) / 1000000L;
            if (elapsed >= nextNotice) {
                emitSafely(Phase.WAITING_FOR_RESPONSE_DELAYED,
                        "Still waiting for an unverified response to packet " + (packetIndex + 1)
                                + "; " + elapsed / 1000 + " seconds elapsed; no retry or next packet sent");
                nextNotice = elapsed + RESPONSE_NOTICE_REPEAT_MS;
            }
            sleep(RESPONSE_POLL_MS);
        }
    }

    private void sleep(int millis) throws IOException, InterruptedException {
        checkCancelled();
        clock.sleepMillis(millis);
        checkCancelled();
    }

    private int read(byte[] buffer, int timeoutMs) throws IOException, InterruptedException {
        checkCancelled();
        int budget = firmwareAttempted ? timeoutMs
                : Math.min(timeoutMs, remainingMillis(sessionStart, SESSION_TIMEOUT_MS));
        int count = transport.read(buffer, 0, buffer.length, Math.max(0, budget));
        checkCancelled();
        if (count < 0) throw new IOException("Controller transport closed unexpectedly");
        if (count > buffer.length) throw new IOException("Transport returned an invalid byte count");
        return count;
    }

    private int remainingMillis(long start, int timeoutMs) {
        long remaining = timeoutMs * 1000000L - (clock.nanoTime() - start);
        return remaining <= 0 ? 0 : (int) ((remaining + 999999L) / 1000000L);
    }

    private void checkCancelled() throws InterruptedException, IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException("Restore interrupted");
        if (!firmwareAttempted && remainingMillis(sessionStart, SESSION_TIMEOUT_MS) == 0)
            throw new IOException("Overall restore deadline exceeded; no automatic retry");
    }

    private void emit(Phase phase, String message) {
        if (listener != null) listener.onEvent(new Event(phase, message, packetIndex, packetCount));
    }

    private void emitSafely(Phase phase, String message) {
        try { emit(phase, message); } catch (RuntimeException ignored) { /* Notices/cleanup cannot stop transfer. */ }
    }
}
