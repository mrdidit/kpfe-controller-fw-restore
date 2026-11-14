package org.kpfe.restore.core;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** FIT USB transfer only. Caller owns device identification, entry, interface and cleanup. */
public final class FitUsbEngine {
    public static final int PACKET_BYTES = 64;
    public static final int PAYLOAD_BYTES = 58;
    public static final int DATA_PACKET_COUNT = 1374;
    public static final int USB_TIMEOUT_MILLIS = 500;
    public static final int MAX_RESPONSE_READS = 30;
    public static final int RESPONSE_RETRY_DELAY_MILLIS = 200;

    public enum Status { SUCCESS, FAILED, INDETERMINATE, CANCELLED }
    public enum Stage { SENDING_DATA, WAITING_FOR_RESPONSE, RESPONSE_RECEIVED, SENDING_QUIT, COMPLETE }

    public interface Transport {
        int write(byte[] packet, int timeoutMillis) throws IOException, InterruptedException;
        int read(byte[] buffer, int timeoutMillis) throws IOException, InterruptedException;
    }

    public interface Clock { void sleep(long millis) throws InterruptedException; }
    public interface Cancellation { boolean isCancelled(); }
    public interface Listener { void onProgress(Stage stage, int index, int total, String message); }

    public static final class Result {
        public final Status status;
        public final int packetsSent;
        public final int packetsAcknowledged;
        public final boolean quitSent;
        public final boolean flashMayHaveStarted;
        public final String message;

        private Result(Status status, Session session, String message) {
            this.status = status;
            this.packetsSent = session.sent;
            this.packetsAcknowledged = session.acknowledged;
            this.quitSent = session.quitSent;
            this.flashMayHaveStarted = session.flashMayHaveStarted;
            this.message = message;
        }
    }

    private final Transport transport;
    private final Listener listener;
    private final Clock clock;
    private final Cancellation cancellation;
    private final AtomicBoolean used = new AtomicBoolean();

    public FitUsbEngine(Transport transport, Listener listener, Clock clock, Cancellation cancellation) {
        if (transport == null || clock == null || cancellation == null)
            throw new IllegalArgumentException("FIT transport, clock and cancellation source are required");
        this.transport = transport;
        this.listener = listener;
        this.clock = clock;
        this.cancellation = cancellation;
    }

    /** One session per engine; no packet, Quit, or whole-session retry is performed here. */
    public Result run(FitFirmwareImage image) {
        Session session = new Session();
        if (!used.compareAndSet(false, true))
            return new Result(Status.FAILED, session, "This FIT transfer engine has already been used; no further I/O performed.");
        if (image == null)
            return new Result(Status.FAILED, session, "A validated Pocket FIT image is required; no I/O performed.");
        Guard guard = new Guard(image);
        try {
            for (int index = 0; index < DATA_PACKET_COUNT; index++) {
                checkCancelled();
                byte[] packet = dataPacket(image, index);
                guard.claim(packet);
                progress(Stage.SENDING_DATA, index, "Sending Pocket FIT data packet once.");
                checkCancelled();
                // Even an exception or short return may follow a partially delivered write.
                session.flashMayHaveStarted = true;
                requireFullWrite(packet);
                session.sent++;
                awaitResponse(index);
                session.acknowledged++;
            }
            checkCancelled();
            byte[] quit = new byte[PACKET_BYTES];
            quit[3] = 3;
            guard.claim(quit);
            progress(Stage.SENDING_QUIT, DATA_PACKET_COUNT, "Sending stock Quit after all data responses.");
            checkCancelled();
            requireFullWrite(quit);
            session.quitSent = true;
            awaitResponse(DATA_PACKET_COUNT);
            progress(Stage.COMPLETE, DATA_PACKET_COUNT, "Transfer sequence completed; response contents and installed firmware are not verified.");
            return new Result(Status.SUCCESS, session,
                    "All Pocket FIT data packets and Quit completed with positive USB responses. Response contents, installed firmware and controls are not verified.");
        } catch (Cancelled error) {
            return new Result(session.flashMayHaveStarted ? Status.INDETERMINATE : Status.CANCELLED, session,
                    error.getMessage() + " No further packet or Quit was sent.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Result(session.flashMayHaveStarted ? Status.INDETERMINATE : Status.CANCELLED, session,
                    "FIT transfer was interrupted. No packet was retried and no further Quit was sent.");
        } catch (IOException | RuntimeException error) {
            return new Result(session.flashMayHaveStarted ? Status.INDETERMINATE : Status.FAILED, session,
                    "FIT transfer stopped: " + error.getClass().getSimpleName() + ": " + error.getMessage()
                            + ". No packet was retried and no further Quit was sent.");
        }
    }

    private void requireFullWrite(byte[] packet) throws IOException, InterruptedException {
        int written = transport.write(packet, USB_TIMEOUT_MILLIS);
        if (written != PACKET_BYTES)
            throw new IOException("USB OUT returned " + written + " of " + PACKET_BYTES + " bytes; delivery is uncertain");
    }

    private void awaitResponse(int index) throws IOException, InterruptedException, Cancelled {
        progress(Stage.WAITING_FOR_RESPONSE, index, "Waiting for a stock USB response; response contents are not validated.");
        byte[] response = new byte[PACKET_BYTES];
        for (int attempt = 0; attempt < MAX_RESPONSE_READS; attempt++) {
            checkCancelled();
            int received = transport.read(response, USB_TIMEOUT_MILLIS);
            if (received > response.length) throw new IOException("USB IN returned an impossible byte count: " + received);
            if (received > 0) {
                progress(Stage.RESPONSE_RECEIVED, index, "Positive USB response received (" + received + " bytes); sequence/status are unverified.");
                return;
            }
            // Stock delays after every failed IN, including the 30th failure,
            // before checking the loop limit. No OUT is repeated during this wait.
            checkCancelled();
            clock.sleep(RESPONSE_RETRY_DELAY_MILLIS);
        }
        throw new IOException("No positive USB response after " + MAX_RESPONSE_READS + " reads");
    }

    private void checkCancelled() throws Cancelled, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("FIT transfer thread interrupted");
        if (cancellation.isCancelled()) throw new Cancelled();
    }

    private void progress(Stage stage, int index, String message) {
        if (listener != null) listener.onProgress(stage, index, DATA_PACKET_COUNT, message);
    }

    private static byte[] dataPacket(FitFirmwareImage image, int index) {
        byte[] packet = new byte[PACKET_BYTES];
        int offset = index * PAYLOAD_BYTES;
        int count = Math.min(PAYLOAD_BYTES, image.size() - offset);
        int checksum = 0;
        for (int i = 0; i < count; i++) {
            byte value = image.valueAt(offset + i);
            packet[6 + i] = value;
            checksum += value & 255;
        }
        packet[0] = (byte) checksum;
        packet[1] = (byte) (checksum >>> 8);
        packet[2] = (byte) count;
        packet[3] = 2;
        packet[4] = (byte) index;
        packet[5] = (byte) (index >>> 8);
        return packet;
    }

    /** Independent sequence/payload verification before any transport write. */
    private static final class Guard {
        private final FitFirmwareImage image;
        private int nextIndex;
        private boolean quitClaimed;

        Guard(FitFirmwareImage image) { this.image = image; }

        void claim(byte[] packet) throws IOException {
            if (quitClaimed || packet == null || packet.length != PACKET_BYTES)
                throw new IOException("FIT command guard rejected packet size or a command after Quit");
            if (packet[3] == 3) {
                if (nextIndex != DATA_PACKET_COUNT) throw new IOException("FIT command guard rejected early Quit");
                for (int i = 0; i < packet.length; i++)
                    if (i != 3 && packet[i] != 0) throw new IOException("FIT command guard rejected modified Quit");
                quitClaimed = true;
                return;
            }
            int claimedIndex = (packet[4] & 255) | ((packet[5] & 255) << 8);
            if (packet[3] != 2 || nextIndex >= DATA_PACKET_COUNT || claimedIndex != nextIndex)
                throw new IOException("FIT command guard rejected packet type or sequence");
            int offset = nextIndex * PAYLOAD_BYTES;
            int expectedLength = Math.min(PAYLOAD_BYTES, image.size() - offset);
            if ((packet[2] & 255) != expectedLength) throw new IOException("FIT command guard rejected payload length");
            int sum = 0;
            for (int i = 0; i < PAYLOAD_BYTES; i++) {
                byte expected = i < expectedLength ? image.valueAt(offset + i) : 0;
                if (packet[6 + i] != expected) throw new IOException("FIT command guard rejected payload or padding");
                if (i < expectedLength) sum += expected & 255;
            }
            if (((packet[0] & 255) | ((packet[1] & 255) << 8)) != (sum & 65535))
                throw new IOException("FIT command guard rejected checksum");
            nextIndex++;
        }
    }

    private static final class Session {
        int sent;
        int acknowledged;
        boolean quitSent;
        boolean flashMayHaveStarted;
    }

    private static final class Cancelled extends Exception {
        private static final long serialVersionUID = 1L;
        Cancelled() { super("FIT transfer was cancelled."); }
    }
}
