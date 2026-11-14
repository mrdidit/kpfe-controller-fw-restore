package org.kpfe.restore;

import android.os.Build;
import android.os.SystemClock;
import android.serialport.SerialPort;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kpfe.restore.core.FirmwareImage;
import org.kpfe.restore.core.RestoreEngine;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Root app_process entry. Never install this as an exported Android component. */
public final class RootMain {
    private static final String PACKAGE = "org.kpfe.restore";
    private static final String GAMEWINDOW = "com.ayaneo.gamewindow";
    private static final String UART = "/dev/ttyHS1";
    private static final String CONTROLLER_VERSION_CACHE = "/data/system/aya/aya_firmware_local_version.conf";
    private static final String PAUSE = "AYA_CONTROLLER_UPDATE_RIGHT_NOW";
    private static final String FINISH = "AYA_CONTROLLER_UPDATE_FINISH";
    private static final String NATIVE_SHA256 =
            "0a939a29544a76913ad682a57cd572246cc23c57cdff4db5040282f90532e3e6";
    private static final AtomicBoolean CANCELLED = new AtomicBoolean();
    private static final AtomicBoolean CHILD_STILL_RUNNING = new AtomicBoolean();
    private static final CountDownLatch CONTROL_READY = new CountDownLatch(1);
    private static volatile RestoreEngine engine;
    private static volatile boolean restoreStarted;

    private RootMain() { }

    public static void main(String[] args) {
        int exit = 1;
        try {
            exit = execute(args);
        } catch (Throwable error) {
            emit("result", "status", restoreStarted ? "INDETERMINATE" : (CANCELLED.get() ? "CANCELLED" : "FAILED"),
                    "message", explain(error));
        }
        System.out.flush();
        System.exit(exit);
    }

    private static int execute(String[] args) throws Exception {
        if (args.length != 4 || !("check".equals(args[0]) || "restore".equals(args[0]) || "entry-test".equals(args[0]) || "exit-only".equals(args[0]) || "resume-only".equals(args[0]))) {
            throw new IOException("Usage: RootMain check|restore|entry-test|exit-only|resume-only <app-private stock firmware path> <installed native library path> <target version 8|9>");
        }
        FirmwareImage.Descriptor target = FirmwareImage.forVersion(args[3]);
        if (Os.getuid() != 0) throw new IOException("Root permission was not granted (UID must be 0).");
        if (!"Pocket FIT Elite".equals(Build.MODEL) || !"PocketFITElite".equals(Build.DEVICE)) {
            throw new IOException("Unsupported device: " + Build.MODEL + " / " + Build.DEVICE + ". Pocket FIT Elite is required.");
        }
        Command user = command(5000, "/system/bin/am", "get-current-user");
        user.requireSuccess("Read foreground Android user");
        if (!"0".equals(user.output.trim())) throw new IOException("Only the primary Android user (0) is supported.");
        reportCurrentFirmwareVersion("preflight", target, false);
        Command batteryDump = command(4000, "/system/bin/dumpsys", "battery");
        batteryDump.requireSuccess("Read battery state");
        RootPolicy.Battery battery = RootPolicy.requireBattery(batteryDump.output);
        emit("battery", "level", battery.level, "powered", battery.powered);
        File privateDir = new File("/data/user/0/" + PACKAGE + "/files").getCanonicalFile();
        File firmwareFile = new File(args[1]).getCanonicalFile();
        if (!firmwareFile.getPath().startsWith(privateDir.getPath() + File.separator)) {
            throw new IOException("Firmware must be inside this application's private files directory.");
        }
        FirmwareImage validatedImage = loadFirmware(firmwareFile, target);
        byte[] firmware = validatedImage.copyBytes();
        emit("validated", "model", Build.MODEL, "device", Build.DEVICE, "uid", Os.getuid(),
                "firmwareBytes", firmware.length, "sha256", target.sha256, "targetVersion", target.version);
        verifyAndLoadLibrary(args[2]);

        // This private lock serializes helper processes, including checks. It does not open the UART.
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(privateDir, "root-restore.lock"), "rw");
             FileLock lock = lockFile.getChannel().tryLock()) {
            if (lock == null) throw new IOException("Another restore helper is already running.");
            StructStat uart = Os.stat(UART);
            if (!OsConstants.S_ISCHR(uart.st_mode)) throw new IOException("UART path is not a character device.");
            OwnerSnapshot snapshot = owners(uart.st_rdev);
            emitOwners(snapshot);
            List<String> originalPids = gameWindowProcesses();
            if (originalPids.isEmpty()) throw new IOException("GameWindow must already be running. Open the stock AYANEO menu and check again.");
            if ("check".equals(args[0])) {
                snapshot.requireReadable();
                snapshot.requireOnlyGameWindow();
                emit("result", "status", "CHECK_OK", "message",
                        "Root, device, firmware and stock native library checks passed. UART was not opened; GameWindow was not changed.",
                        "uartOpened", false, "gameWindowChanged", false,
                        "gameWindowRunning", !gameWindowProcesses().isEmpty());
                return 0;
            }
            snapshot.requireReadable();
            snapshot.requireOnlyGameWindow();
            final SerialTransport transport = new SerialTransport(uart.st_rdev, args[0], originalPids, validatedImage);
            if (!"resume-only".equals(args[0])) {
                engine = new RestoreEngine(transport, event -> emit("progress", "phase", String.valueOf(event.phase),
                        "message", event.message, "packetIndex", event.packetIndex, "packetCount", event.packetCount));
            }
            startCancellationReader();
            if (!CONTROL_READY.await(5, TimeUnit.SECONDS)) throw new IOException("App control pipe did not send START; restore was not started.");
            checkCancelled();
            if ("resume-only".equals(args[0])) {
                restoreStarted = true;
                transport.resumeGameWindowOnly();
                transport.reinitializeDisplay();
                emit("result", "status", "SUCCESS", "mode", args[0], "message",
                        "Stock display reinitialization completed. Check buttons and sticks; physical input functionality is not verified.",
                        "uartOpened", false, "packetsSent", 0, "packetsAcknowledged", 0, "quitSent", false,
                        "applicationBannerObserved", false, "bootloaderBannerObserved", false,
                        "flashMayHaveStarted", false, "inputFunctionVerified", false, "cleanupError", "",
                        "displayReinitialized", true, "androidRebootPerformed", false);
                return 0;
            }
            RestoreEngine.Result result;
            restoreStarted = true;
            try {
                result = "exit-only".equals(args[0]) ? engine.exitOnly(firmware)
                        : ("entry-test".equals(args[0]) ? engine.testEntryOnly(firmware) : engine.run(firmware));
            }
            finally { transport.close(); }
            String status = String.valueOf(result.status);
            String message = result.message;
            if ("SUCCESS".equals(status) && result.applicationBannerObserved
                    && transport.ownedClosedVerified && transport.cleanupError.isEmpty() && !CANCELLED.get()) {
                try { transport.reinitializeDisplay(); }
                catch (Exception error) { status = "INDETERMINATE"; message += "; stock display reinitialization failed: " + explain(error); }
            } else if ("SUCCESS".equals(status)) {
                status = "INDETERMINATE";
                message += "; stock display reinitialization was not eligible or was cancelled";
            }
            emit("result", "status", status, "message", message,
                    "packetsSent", result.packetsSent, "packetsAcknowledged", result.packetsAcknowledged,
                    "quitSent", result.quitSent, "applicationBannerObserved", result.applicationBannerObserved,
                    "bootloaderBannerObserved", result.bootloaderBannerObserved,
                    "mode", args[0], "targetVersion", target.version,
                    "flashMayHaveStarted", result.flashMayHaveStarted,
                    "cleanupError", transport.cleanupError,
                    "displayReinitialized", transport.displayReinitialized, "androidRebootPerformed", false,
                    "reportedFirmwareVersion", transport.lastVersionReport.version,
                    "reportedFirmwareVersionAvailable", transport.lastVersionReport.available,
                    "versionComparisonApplicable", "restore".equals(args[0]),
                    "versionMismatch", transport.versionMismatch, "mismatchReason", transport.mismatchReason,
                    "inputFunctionVerified", false);
            return "SUCCESS".equals(status) && transport.cleanupError.isEmpty() ? 0 : 1;
        }
    }

    private static void verifyAndLoadLibrary(String suppliedPath) throws Exception {
        File file = new File(suppliedPath);
        String canonical = file.getCanonicalPath();
        if (!file.isAbsolute() || !canonical.equals(file.getAbsolutePath())
                || !canonical.matches("/data/app/(?:[^/]+/)?org\\.kpfe\\.restore-[^/]+/lib/arm64/libserial_port\\.so")) {
            throw new IOException("Native library must be the canonical installed Controller FW Restore ARM64 library.");
        }
        // Android installs this extracted library under system ownership. Reject an
        // application-writable file or ancestor even if its current hash matches.
        for (File item = file; !"/".equals(item.getPath()); item = item.getParentFile()) {
            if (item == null) throw new IOException("Invalid installed native library path.");
            StructStat stat = Os.lstat(item.getPath());
            if ((stat.st_uid != 0 && stat.st_uid != 1000) || (stat.st_mode & 0002) != 0
                    || ((stat.st_mode & 0020) != 0 && stat.st_gid != 0 && stat.st_gid != 1000)) {
                throw new IOException("Installed native library path is not protected from application writes.");
            }
        }
        StructStat stat = Os.lstat(canonical);
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != 9992) throw new IOException("Stock native library size/type mismatch.");
        byte[] bytes = readBounded(file, 9992);
        if (bytes.length != 9992 || !NATIVE_SHA256.equals(hex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 32))) {
            throw new IOException("Stock native library hash does not match the verified Settings149 library.");
        }
        emit("native-library", "path", canonical, "bytes", bytes.length, "sha256", NATIVE_SHA256);
        SerialPort.loadVerifiedLibrary(canonical);
        emit("native-loaded", "uartOpened", false,
                "message", "Pinned stock JNI loaded. Native I/O is blocking; kernel stalls cannot be bounded by Java deadlines.");
    }

    private static FirmwareImage loadFirmware(File file, FirmwareImage.Descriptor target) throws Exception {
        StructStat before = Os.lstat(file.getPath());
        if (!OsConstants.S_ISREG(before.st_mode) || before.st_size != target.size) {
            throw new IOException("Stock v" + target.version + " firmware must be a regular file of exactly " + target.size + " bytes.");
        }
        byte[] data = readBounded(file, target.size);
        // All later transfer bytes come from this validated snapshot, never from a reopened file.
        return FirmwareImage.validate(target, data);
    }

    /** Reads the same passive cache as Settings149 About; never opens UART or requests an update. */
    private static ControllerVersionReport reportCurrentFirmwareVersion(String stage,
            FirmwareImage.Descriptor target, boolean compareToTarget) throws InterruptedException {
        long modifiedAtMillis = 0;
        String updating = "unknown";
        String working = "unknown";
        ControllerVersionReport report;
        try {
            Command updateFlag = command(4000, "/system/bin/settings", "get", "system", "ayaneo/share/is_firmware_on_update_zone");
            Command workingFlag = command(4000, "/system/bin/settings", "get", "system", "ayaneo/share/is_firmware_working");
            updateFlag.requireSuccess("Read stock update-zone flag for version report");
            workingFlag.requireSuccess("Read stock working flag for version report");
            updating = updateFlag.output.trim();
            working = workingFlag.output.trim();
            StructStat before = Os.lstat(CONTROLLER_VERSION_CACHE);
            if (!OsConstants.S_ISREG(before.st_mode) || before.st_size < 1 || before.st_size > 32) {
                throw new IOException("Stock controller version cache is missing or malformed.");
            }
            byte[] bytes = readBounded(new File(CONTROLLER_VERSION_CACHE), 32);
            StructStat after = Os.lstat(CONTROLLER_VERSION_CACHE);
            if (before.st_dev != after.st_dev || before.st_ino != after.st_ino || before.st_size != after.st_size
                    || before.st_mtim.tv_sec != after.st_mtim.tv_sec || before.st_mtim.tv_nsec != after.st_mtim.tv_nsec
                    || bytes.length != after.st_size) {
                throw new IOException("Stock controller version cache changed while reading.");
            }
            modifiedAtMillis = after.st_mtim.tv_sec * 1000L + after.st_mtim.tv_nsec / 1000000L;
            report = ControllerVersionReport.fromCache(new String(bytes, StandardCharsets.US_ASCII), updating, working);
        } catch (IOException | ErrnoException error) {
            report = ControllerVersionReport.unavailable("Cannot read AYANEO's controller report: " + explain(error));
        }
        emit("current-firmware-version", "version", report.version, "available", report.available,
                "source", "AYANEO controller report", "sourcePath", CONTROLLER_VERSION_CACHE,
                "observedAtMillis", System.currentTimeMillis(), "sourceModifiedAtMillis", modifiedAtMillis,
                "liveQuery", false, "cacheFreshnessVerified", false, "stage", stage,
                "updateZone", updating, "working", working, "reason", report.reason,
                "targetVersion", target.version, "versionComparisonApplicable", compareToTarget,
                "versionMismatch", compareToTarget && report.available && !target.version.equals(report.version),
                "mismatchBasis", "AYANEO cached controller report; freshness is not independently verified");
        return report;
    }

    private static void startCancellationReader() {
        Thread watcher = new Thread(() -> {
            try {
                BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                if (!"START".equals(input.readLine())) {
                    requestCancellation("App control pipe did not start a restore session.");
                    return;
                }
                CONTROL_READY.countDown();
                String line;
                while ((line = input.readLine()) != null) {
                    if ("CANCEL".equals(line)) { requestCancellation("Cancellation requested."); return; }
                }
                requestCancellation("App control pipe closed; stopping at the next safe protocol boundary.");
            } catch (IOException error) {
                requestCancellation("App control pipe failed; stopping at the next safe protocol boundary.");
            } finally {
                CONTROL_READY.countDown();
            }
        }, "restore-cancel-input");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void requestCancellation(String message) {
        CANCELLED.set(true);
        RestoreEngine current = engine;
        if (current != null) current.cancel();
        emit("cancellation", "message", message);
    }

    private static void checkCancelled() throws InterruptedException {
        if (CANCELLED.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Restore cancelled.");
    }

    private static final class SerialTransport implements RestoreEngine.Transport {
        private final long uartDevice;
        private final String commandMode;
        private final List<String> originalPids;
        private final StockCommandGuard commands;
        private final FirmwareImage.Descriptor target;
        private SerialPort port;
        private Receiver receiver;
        private boolean paused;
        private boolean closed;
        private boolean finishCompleted;
        private boolean ownedClosedVerified = true;
        private boolean displayReinitialized;
        private int generation;
        private String cleanupError = "";
        private ControllerVersionReport lastVersionReport = ControllerVersionReport.unavailable("Post-reinitialization report not read.");
        private boolean versionMismatch;
        private String mismatchReason = "";

        SerialTransport(long uartDevice, String commandMode, List<String> originalPids, FirmwareImage validatedImage) throws IOException {
            this.uartDevice = uartDevice;
            this.commandMode = commandMode;
            this.originalPids = new ArrayList<>(originalPids);
            this.commands = new StockCommandGuard(commandMode, validatedImage);
            this.target = validatedImage.descriptor();
        }

        @Override public void open() throws IOException, InterruptedException {
            if ("resume-only".equals(commandMode)) throw new IOException("Resume-only cannot open the UART.");
            checkCancelled();
            requireRetainedGameWindow();
            checkOwners();
            waitForFinishReceiver();
            paused = true; // Even a failed shell return may have delivered the broadcast.
            broadcast(PAUSE);
            checkCancelled();
            if ("entry-test".equals(commandMode) || "restore".equals(commandMode)) {
                emit("stock-entry-delay", "milliseconds", 30);
                Thread.sleep(30);
            }
            openNative("listen", -1);
            emit("uart", "message", "Stock native serial opened; original GameWindow retained. Broadcast delivery and reader cancellation are not independently verified.",
                    "blockingNativeIo", true, "readPollMillis", 10, "gameWindowPids", new JSONArray(originalPids));
        }

        private void requireRetainedGameWindow() throws IOException {
            if (!gameWindowProcesses().containsAll(originalPids)) {
                throw new IOException("Original GameWindow process disappeared. This helper will not force-stop or restart it.");
            }
        }

        private void checkOwners() throws IOException {
            requireRetainedGameWindow();
            OwnerSnapshot snapshot = owners(uartDevice);
            snapshot.requireReadable();
            snapshot.requireOnlyGameWindow();
            for (Owner owner : snapshot.owners) {
                if (!originalPids.contains(owner.pid)) throw new IOException("A new GameWindow process owns the UART; transfer refused.");
            }
        }

        private void openNative(String commandKind, int packetIndex) throws IOException, InterruptedException {
            checkCancelled();
            if (port != null || receiver != null || !ownedClosedVerified) throw new IOException("Previous owned UART closure is uncertain.");
            checkOwners();
            emit("native-open-attempt", "generation", generation + 1, "path", UART, "baud", 115200,
                    "stopBits", 1, "dataBits", 8, "parity", 0, "flowControl", 0, "flagsArgument", 0);
            // This is the unmodified blocking vendor JNI call. A Java timeout cannot
            // guarantee interruption if the driver itself stalls during native I/O.
            port = new SerialPort();
            ownedClosedVerified = false;
            generation++;
            try {
                StructStat actual = Os.fstat(port.descriptor());
                if (!OsConstants.S_ISCHR(actual.st_mode) || actual.st_rdev != uartDevice) throw new IOException("Opened UART identity mismatch.");
                emit("native-open-result", "generation", generation, "opened", true,
                        "fdFlags", Os.fcntlInt(port.descriptor(), OsConstants.F_GETFL, 0), "termiosReadbackAvailable", false);
            } catch (ErrnoException error) { throw new IOException("Cannot inspect opened UART.", error); }
            receiver = new Receiver(port, generation, commandMode, commandKind, packetIndex);
            receiver.start();
        }

        @Override public int read(byte[] bytes, int offset, int length, int timeoutMillis)
                throws IOException, InterruptedException {
            checkCancelled();
            Receiver current = receiver;
            if (current == null || port == null) throw new IOException("UART is not open.");
            return current.take(bytes, offset, length, timeoutMillis);
        }

        @Override public void prepareFirmwareTransfer() throws IOException, InterruptedException {
            checkCancelled();
            if (!"restore".equals(commandMode) || closed || port == null || receiver == null) {
                throw new IOException("Firmware preparation requires an open restore-mode transport.");
            }
            // Stock performs a separate preparation reopen before its transfer delays.
            // This generation only listens: no command is claimed or transmitted here.
            closeNative();
            checkCancelled();
            openNative("firmware-prepare", -1);
            checkCancelled();
            emit("stock-firmware-prepare", "generation", generation, "uartReopened", true,
                    "commandTransmitted", false, "preparationOpenSynchronous", true);
        }

        @Override public void write(byte[] bytes, int timeoutMillis) throws IOException, InterruptedException {
            checkCancelled();
            if (closed || port == null) throw new IOException("UART is not open.");
            String kind = commands.claim(bytes);
            // Only decode metadata after the independent guard validates framing.
            int packetIndex = "firmware-data".equals(kind)
                    ? (bytes[4] & 255) | ((bytes[5] & 255) << 8) : -1;
            // Stock CmdSender closes/reopens the native port per send. The prior
            // reader's in-memory queue is retired explicitly, never drained from UART.
            closeNative();
            checkCancelled();
            openNative(kind, packetIndex);
            checkCancelled();
            Receiver writingReceiver = receiver;
            if (!"firmware-data".equals(kind)) emit("tx-attempt", "mode", commandMode, "generation", generation,
                    "kind", kind, "packetIndex", packetIndex, "length", bytes.length, "hex", hex(bytes, 0, bytes.length));
            else emit("tx-attempt", "mode", commandMode, "generation", generation,
                    "kind", kind, "packetIndex", packetIndex, "length", bytes.length);
            boolean returned = false;
            try {
                // Host markers bracket this call; they do not timestamp wire delivery.
                writingReceiver.writePhase = "writing";
                port.output().write(bytes); // One stream call: no replay, flush or drain.
                returned = true;
            } finally {
                writingReceiver.writePhase = returned ? "returned" : "threw";
                emit("tx-result", "mode", commandMode, "generation", generation,
                        "kind", kind, "packetIndex", packetIndex, "requested", bytes.length,
                        "streamWriteReturnedNormally", returned, "physicalDeliveryVerified", false,
                        "acceptedByteCount", returned ? "complete stream write" : "unknown",
                        "requestedTimeoutMillis", timeoutMillis, "kernelBlockingDeadlineGuaranteed", false);
            }
            Thread.sleep(1); // Exact Settings149 handler delay after its stream write.
        }

        private void closeNative() throws IOException {
            SerialPort closingPort = port;
            Receiver closingReceiver = receiver;
            if (closingPort == null && closingReceiver == null) return;
            List<String> failures = new ArrayList<>();
            boolean interrupted = Thread.interrupted();
            boolean descriptorClosed = closingPort == null;
            if (closingReceiver != null) closingReceiver.closing = true;
            if (closingPort != null) {
                emit("native-close-attempt", "generation", generation);
                try { closingPort.closeOwned(closingReceiver == null ? null : () -> closingReceiver.requestStop()); }
                catch (Throwable error) { failures.add("Native close: " + explain(error)); }
                try { Os.fstat(closingPort.descriptor()); failures.add("Owned UART descriptor is still valid."); }
                catch (ErrnoException error) {
                    descriptorClosed = error.errno == OsConstants.EBADF;
                    if (!descriptorClosed) failures.add("Cannot verify owned descriptor closure: errno " + error.errno);
                }
            }
            if (closingReceiver != null) {
                try { closingReceiver.join(1000); }
                catch (InterruptedException error) { interrupted = true; failures.add("Reader join interrupted."); }
                if (closingReceiver.isAlive()) failures.add("Owned UART reader has not stopped.");
                else closingReceiver.retire();
            }
            ownedClosedVerified = descriptorClosed && (closingReceiver == null || !closingReceiver.isAlive());
            if (ownedClosedVerified) { port = null; receiver = null; }
            emit("native-close-result", "generation", generation, "closedVerified", ownedClosedVerified);
            if (interrupted) Thread.currentThread().interrupt();
            if (!failures.isEmpty()) throw new IOException(String.join("; ", failures));
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            boolean interrupted = Thread.interrupted();
            List<String> failures = new ArrayList<>();
            try { closeNative(); }
            catch (Exception error) { failures.add(explain(error)); }
            if (paused && ownedClosedVerified && !CHILD_STILL_RUNNING.get()) {
                try {
                    requireRetainedGameWindow();
                    waitForFinishReceiver();
                    broadcast(FINISH);
                    waitForGameWindowOwner();
                    finishCompleted = true;
                } catch (Exception error) { failures.add(explain(error)); }
            } else if (paused) failures.add("UART/child closure uncertain; FINISH was withheld.");
            if (interrupted) Thread.currentThread().interrupt();
            cleanupError = RootPolicy.boundedDiagnostic(String.join("; ", failures));
            emit("cleanup", "ok", failures.isEmpty(), "ownedFdClosedVerified", ownedClosedVerified,
                    "finishCompleted", finishCompleted, "message", cleanupError.isEmpty()
                            ? "Owned UART closed and original GameWindow FINISH receiver/ownership observed. Physical inputs are not verified."
                            : cleanupError);
            if (!failures.isEmpty()) throw new IOException(cleanupError);
        }

        private void waitForFinishReceiver() throws IOException, InterruptedException {
            long deadline = SystemClock.elapsedRealtime() + 5000;
            do {
                requireRetainedGameWindow();
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                Command receivers = command(Math.min(remaining, 3000), "/system/bin/dumpsys", "activity", "broadcasts");
                RootPolicy.requireBroadcastDump(receivers.exitCode, receivers.output);
                if (RootPolicy.hasFinishReceiver(receivers.output, originalPids)) {
                    emit("gamewindow-ready", "receiverRegistered", true, "inputFunctionVerified", false);
                    return;
                }
                Thread.sleep(100);
            } while (SystemClock.elapsedRealtime() < deadline);
            throw new IOException("Original GameWindow FINISH receiver was not observed.");
        }

        private void waitForGameWindowOwner() throws IOException, InterruptedException {
            long deadline = SystemClock.elapsedRealtime() + 5000;
            do {
                checkOwners();
                OwnerSnapshot snapshot = owners(uartDevice);
                snapshot.requireReadable();
                snapshot.requireOnlyGameWindow();
                if (!snapshot.owners.isEmpty()) {
                    emitOwners(snapshot);
                    emit("gamewindow-resumed", "uartOwnerObserved", true, "inputFunctionVerified", false);
                    return;
                }
                Thread.sleep(100);
            } while (SystemClock.elapsedRealtime() < deadline);
            throw new IOException("Original GameWindow UART ownership was not observed after FINISH.");
        }

        private void resumeGameWindowOnly() throws IOException, InterruptedException {
            checkCancelled();
            checkOwners();
            waitForFinishReceiver();
            checkCancelled();
            broadcast(FINISH);
            waitForGameWindowOwner();
            finishCompleted = true;
        }

        private void reinitializeDisplay() throws IOException, InterruptedException {
            checkCancelled();
            if (!ownedClosedVerified || !cleanupError.isEmpty() || !finishCompleted || CHILD_STILL_RUNNING.get()) {
                throw new IOException("Stock display reinitialization requires verified UART closure and completed GameWindow handoff.");
            }
            requireRetainedGameWindow();
            checkOwners();
            emit("progress", "phase", "REINITIALIZING", "message", "Turning the display off for five seconds, then waking it for stock controller initialization. Android will not reboot.",
                    "packetIndex", -1, "packetCount", 0);
            boolean sleepAttempted = false;
            Exception failure = null;
            boolean interrupted = false;
            try {
                checkCancelled();
                sleepAttempted = true;
                command(5000, "/system/bin/input", "keyevent", "KEYCODE_SLEEP").requireSuccess("Sleep display for stock reinitialization");
                Command sleeping = command(4000, "/system/bin/dumpsys", "power");
                sleeping.requireSuccess("Observe display sleep state");
                if (!sleeping.output.contains("mWakefulness=Asleep") && !sleeping.output.contains("mWakefulness=Dozing")) {
                    throw new IOException("Display did not enter sleep/doze after the sleep request.");
                }
                emit("stock-display-sleep", "observed", true, "sleepMillis", 5000);
                // Once sleep is attempted, cancellation must not leave the screen off.
                interrupted |= sleepUninterruptibly(5000);
            } catch (Exception error) { failure = error; }
            finally {
                interrupted |= Thread.interrupted();
                if (sleepAttempted) {
                    try { command(5000, "/system/bin/input", "keyevent", "KEYCODE_WAKEUP").requireSuccess("Wake display after stock reinitialization"); }
                    catch (Exception error) { if (failure == null) failure = error; }
                }
            }
            if (failure == null) {
                interrupted |= sleepUninterruptibly(10000);
                try {
                    Command power = command(4000, "/system/bin/dumpsys", "power");
                    power.requireSuccess("Read display wake state");
                    if (!power.output.contains("mWakefulness=Awake") || !power.output.contains("mWakefulnessChanging=false")) {
                        throw new IOException("Stable awake display was not observed after reinitialization.");
                    }
                    Command updating = command(4000, "/system/bin/settings", "get", "system", "ayaneo/share/is_firmware_on_update_zone");
                    Command working = command(4000, "/system/bin/settings", "get", "system", "ayaneo/share/is_firmware_working");
                    updating.requireSuccess("Read stock update-zone flag"); working.requireSuccess("Read stock working flag");
                    requireRetainedGameWindow();
                    waitForGameWindowOwner();
                    emit("stock-reinitialization", "awake", true, "updateZone", updating.output.trim(),
                            "working", working.output.trim(), "gameWindowPids", new JSONArray(originalPids),
                            "androidRebootPerformed", false, "inputFunctionVerified", false);
                    if (!"0".equals(updating.output.trim()) || !"1".equals(working.output.trim())) {
                        throw new IOException("Stock controller flags did not return to update-zone=0 and working=1.");
                    }
                    displayReinitialized = true;
                    lastVersionReport = reportCurrentFirmwareVersion("after-stock-reinitialization", target, "restore".equals(commandMode));
                    versionMismatch = "restore".equals(commandMode) && lastVersionReport.available
                            && !target.version.equals(lastVersionReport.version);
                    if (versionMismatch) mismatchReason = "AYANEO reports v" + lastVersionReport.version
                            + " after transfer to v" + target.version + ". The report is cached; freshness is not independently verified.";
                } catch (Exception error) { failure = error; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (failure != null) throw new IOException("Display reinitialization: " + explain(failure), failure);
            checkCancelled();
        }
    }

    /** Preserve wake completion even when cancellation arrives while the screen is asleep. */
    private static boolean sleepUninterruptibly(long millis) {
        boolean interrupted = false;
        long deadline = SystemClock.elapsedRealtime() + millis;
        while (SystemClock.elapsedRealtime() < deadline) {
            try { Thread.sleep(Math.max(1, deadline - SystemClock.elapsedRealtime())); }
            catch (InterruptedException error) { interrupted = true; }
        }
        return interrupted;
    }

    /** Stock10ms available/read loop with a bounded in-memory queue for timed core reads. */
    private static final class Receiver extends Thread {
        private static final int RX_LOG_CHUNK_BYTES = 512;
        private final SerialPort port;
        private final int generation;
        private final String mode;
        private final String commandKind;
        private final int packetIndex;
        private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        private long readBatch;
        private int queuedBytes;
        private int headOffset;
        private volatile String writePhase;
        private volatile boolean stopping;
        private volatile boolean closing;
        private IOException failure;

        Receiver(SerialPort port, int generation, String mode, String commandKind, int packetIndex) {
            super("stock-uart-reader-" + generation);
            this.port = port; this.generation = generation; this.mode = mode;
            this.commandKind = commandKind; this.packetIndex = packetIndex;
            this.writePhase = "listen".equals(commandKind) || "firmware-prepare".equals(commandKind)
                    ? "listening" : "before-attempt";
            setDaemon(true);
        }
        void requestStop() { stopping = true; interrupt(); }
        @Override public void run() {
            try {
                while (!stopping) {
                    int available = port.input().available();
                    if (available > 0) {
                        byte[] bytes = new byte[Math.min(available, 4096)];
                        String phaseAtReadStart = writePhase;
                        long readStartedAt = SystemClock.elapsedRealtime();
                        int count = port.input().read(bytes);
                        if (count < 0) throw new IOException("Stock UART stream reached EOF.");
                        if (count > 0) {
                            long batch = ++readBatch;
                            // Chunk diagnostics only. The queue retains the full actual read.
                            for (int offset = 0; offset < count; offset += RX_LOG_CHUNK_BYTES) {
                                int chunkLength = Math.min(RX_LOG_CHUNK_BYTES, count - offset);
                                emit("rx", "mode", mode, "generation", generation,
                                        "commandKind", commandKind, "packetIndex", packetIndex,
                                        "readBatch", batch, "offset", offset, "totalLength", count,
                                        "length", chunkLength, "hex", hex(bytes, offset, chunkLength),
                                        "readStartedAtElapsedRealtimeMs", readStartedAt,
                                        "writePhaseAtReadStart", phaseAtReadStart, "queuedAgeVerified", false);
                            }
                            synchronized (this) {
                                if (queuedBytes + count > 65536) throw new IOException("Stock UART receive queue exceeded65536 bytes.");
                                queue.add(Arrays.copyOf(bytes, count)); queuedBytes += count; notifyAll();
                            }
                        }
                    }
                    Thread.sleep(10);
                }
            } catch (Exception error) {
                synchronized (this) {
                    if (!closing && !stopping) failure = new IOException("Stock receive loop failed: " + explain(error), error);
                    notifyAll();
                }
            } finally { emit("reader-stopped", "generation", generation); }
        }
        synchronized int take(byte[] bytes, int offset, int length, int timeoutMillis) throws IOException, InterruptedException {
            if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            long deadline = SystemClock.elapsedRealtime() + Math.max(0, timeoutMillis);
            while (queue.isEmpty()) {
                checkCancelled();
                if (failure != null) throw failure;
                if (stopping) throw new IOException("Stock UART reader stopped.");
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) return 0;
                wait(Math.min(remaining, 100));
            }
            if (failure != null) throw failure;
            byte[] first = queue.getFirst();
            int count = Math.min(length, first.length - headOffset);
            System.arraycopy(first, headOffset, bytes, offset, count);
            queuedBytes -= count; headOffset += count;
            if (headOffset == first.length) { queue.removeFirst(); headOffset = 0; }
            return count;
        }
        synchronized void retire() {
            emit("retired-rx-generation", "generation", generation, "bufferedBytesNotReused", queuedBytes,
                    "kernelReadOrFlushPerformed", false, "freshnessEstablished", false);
            queue.clear(); queuedBytes = 0; headOffset = 0;
        }
    }

    private static void broadcast(String action) throws IOException, InterruptedException {
        Command result = command(5000, "/system/bin/am", "broadcast", "--user", "0", "-p", GAMEWINDOW, "-a", action);
        // GameWindow registers a NOT_EXPORTED receiver. This is advisory and cannot prove delivery.
        emit("broadcast", "action", action, "exitCode", result.exitCode,
                "deliveryVerified", false, "message", "Broadcast attempted; retained process/fd metadata does not prove reader cancellation or delivery.");
        result.requireSuccess("GameWindow update notification");
    }

    private static final class Owner {
        final String pid;
        final String process;
        final String descriptor;
        Owner(String pid, String process, String descriptor) { this.pid = pid; this.process = process; this.descriptor = descriptor; }
        boolean isGameWindow() { return GAMEWINDOW.equals(process) || process.startsWith(GAMEWINDOW + ":"); }
    }

    private static final class OwnerSnapshot {
        final List<Owner> owners = new ArrayList<>();
        final List<String> unreadable = new ArrayList<>();
        void requireReadable() throws IOException {
            if (!unreadable.isEmpty()) throw new IOException("Cannot inspect UART ownership: " + String.join(", ", unreadable));
        }
        void requireOnlyGameWindow() throws IOException {
            for (Owner owner : owners) if (!owner.isGameWindow()) {
                throw new IOException("UART is in use by " + describe() + ". Close Settings/updater or other serial tools first.");
            }
        }
        String describe() {
            List<String> names = new ArrayList<>();
            for (Owner owner : owners) names.add(owner.process + " (pid " + owner.pid + ", fd " + owner.descriptor + ")");
            return String.join(", ", names);
        }
    }

    private static OwnerSnapshot owners(long device) throws IOException {
        OwnerSnapshot result = new OwnerSnapshot();
        File[] processes = new File("/proc").listFiles();
        if (processes == null) throw new IOException("Cannot inspect /proc for UART owners.");
        String self = Integer.toString(Os.getpid());
        long deadline = SystemClock.elapsedRealtime() + 5000;
        for (File process : processes) {
            if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("UART owner scan timed out; transfer refused.");
            String pid = process.getName();
            if (!pid.matches("[0-9]+") || pid.equals(self)) continue;
            File directory = new File(process, "fd");
            File[] descriptors = directory.listFiles();
            if (descriptors == null) {
                if (process.exists()) result.unreadable.add("pid " + pid + " fd directory");
                continue;
            }
            for (File descriptor : descriptors) {
                if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("UART owner scan timed out; transfer refused.");
                try {
                    StructStat stat = Os.stat(descriptor.getPath());
                    if (OsConstants.S_ISCHR(stat.st_mode) && stat.st_rdev == device) {
                        result.owners.add(new Owner(pid, processName(process), descriptor.getName()));
                    }
                } catch (ErrnoException error) {
                    if (error.errno != OsConstants.ENOENT && process.exists()) {
                        result.unreadable.add("pid " + pid + " fd " + descriptor.getName() + ": errno " + error.errno);
                    }
                }
            }
        }
        return result;
    }

    private static List<String> gameWindowProcesses() throws IOException {
        List<String> found = new ArrayList<>();
        File[] processes = new File("/proc").listFiles();
        if (processes == null) throw new IOException("Cannot inspect running GameWindow processes.");
        for (File process : processes) {
            if (!process.getName().matches("[0-9]+")) continue;
            String name = processName(process);
            if (GAMEWINDOW.equals(name) || name.startsWith(GAMEWINDOW + ":")) found.add(process.getName());
        }
        return found;
    }

    private static String processName(File process) {
        try {
            byte[] data = readBounded(new File(process, "cmdline"), 16384);
            int end = 0;
            while (end < data.length && data[end] != 0) end++;
            return new String(data, 0, end, StandardCharsets.UTF_8);
        } catch (IOException error) { return "unknown"; }
    }

    private static byte[] readBounded(File file, int limit) throws IOException {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[4096];
            int count;
            while ((count = input.read(block)) != -1) {
                if (output.size() + count > limit) throw new IOException("Input exceeds limit: " + file.getName());
                output.write(block, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static final class Command {
        final int exitCode;
        final String output;
        Command(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
        void requireSuccess(String operation) throws IOException {
            String lower = output.toLowerCase(java.util.Locale.ROOT);
            if (exitCode != 0 || lower.contains("securityexception") || lower.contains("permission denial")
                    || lower.contains("error:") || lower.contains("exception occurred")) {
                throw new IOException(RootPolicy.commandFailure(operation, exitCode, output));
            }
        }
    }

    private static Command command(long timeoutMillis, String... args) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(Arrays.asList(args)).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicBoolean truncated = new AtomicBoolean();
        AtomicBoolean readError = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] block = new byte[4096];
                int count;
                while ((count = stream.read(block)) != -1) {
                    synchronized (output) {
                        if (output.size() + count <= 2 * 1024 * 1024) output.write(block, 0, count);
                        else truncated.set(true);
                    }
                }
            } catch (IOException error) { readError.set(true); }
        }, "root-command-output");
        reader.setDaemon(true);
        reader.start();
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        try {
            int exit;
            while (true) {
                try { exit = process.exitValue(); break; }
                catch (IllegalThreadStateException running) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("Command timed out: " + args[0] + " " + args[1]);
                    Thread.sleep(25);
                }
            }
            reader.join(1000);
            if (reader.isAlive() || readError.get() || truncated.get()) throw new IOException("Could not capture complete command output: " + args[0]);
            return new Command(exit, new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            process.destroy();
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            boolean interrupted = Thread.interrupted();
            try {
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    if (!process.waitFor(750, TimeUnit.MILLISECONDS)) CHILD_STILL_RUNNING.set(true);
                }
            } catch (InterruptedException error) {
                interrupted = true;
                process.destroyForcibly();
                CHILD_STILL_RUNNING.set(true);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static void emitOwners(OwnerSnapshot snapshot) {
        JSONArray owners = new JSONArray();
        for (Owner owner : snapshot.owners) {
            JSONObject item = new JSONObject();
            try { item.put("pid", owner.pid); item.put("process", owner.process); item.put("fd", owner.descriptor); }
            catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
            owners.put(item);
        }
        emit("owners", "owners", owners, "unreadable", new JSONArray(snapshot.unreadable));
    }

    private static synchronized void emit(String event, Object... pairs) {
        try {
            JSONObject object = new JSONObject();
            object.put("event", event);
            object.put("schema", "kpfe-root-restore/v1");
            object.put("pid", Os.getpid());
            object.put("elapsedRealtimeMs", SystemClock.elapsedRealtime());
            object.put("wallTimeMillis", System.currentTimeMillis());
            for (int i = 0; i < pairs.length; i += 2) object.put((String) pairs[i], pairs[i + 1]);
            System.out.println(object.toString());
            System.out.flush();
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String explain(Throwable error) {
        String message = error.getMessage();
        return RootPolicy.boundedDiagnostic(error.getClass().getSimpleName() + (message == null ? "" : ": " + message));
    }

    private static String hex(byte[] bytes, int offset, int length) {
        final char[] alphabet = "0123456789abcdef".toCharArray();
        char[] output = new char[length * 2];
        for (int i = 0; i < length; i++) {
            int value = bytes[offset + i] & 255;
            output[i * 2] = alphabet[value >>> 4];
            output[i * 2 + 1] = alphabet[value & 15];
        }
        return new String(output);
    }
}
