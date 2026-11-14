package org.kpfe.restore;

import android.os.SystemClock;
import android.serialport.SerialPort;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kpfe.restore.core.FitFirmwareImage;
import org.kpfe.restore.core.FitUsbEngine;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate standard Pocket FIT root entry. No arbitrary image, USB target, or UART commands. */
public final class FitRootMain {
    private static final String PACKAGE = "org.kpfe.restore", GAMEWINDOW = "com.ayaneo.gamewindow";
    private static final String UART = "/dev/ttyHS5";
    private static final String PAUSE = "AYA_CONTROLLER_UPDATE_RIGHT_NOW", FINISH = "AYA_CONTROLLER_UPDATE_FINISH";
    private static final String NATIVE_SHA256 = "0a939a29544a76913ad682a57cd572246cc23c57cdff4db5040282f90532e3e6";
    private static final AtomicBoolean CANCELLED = new AtomicBoolean(), CHILD_STILL_RUNNING = new AtomicBoolean();
    private static final CountDownLatch CONTROL_READY = new CountDownLatch(1);
    private static volatile boolean controlStateChanged;
    private static volatile FitUsbAccess activeUsb;

    private FitRootMain() { }

    public static void main(String[] args) {
        int exit = 1;
        try { exit = execute(args); }
        catch (Throwable error) {
            boolean flash = activeUsb != null && activeUsb.flashMayHaveStarted;
            emit("result", "status", flash || controlStateChanged ? "INDETERMINATE" : (CANCELLED.get() ? "CANCELLED" : "FAILED"),
                    "message", explain(error), "flashMayHaveStarted", flash, "controlStateChanged", controlStateChanged,
                    "inputFunctionVerified", false, "androidRebootPerformed", false);
        }
        System.out.flush();
        System.exit(exit);
    }

    private static int execute(String[] args) throws Exception {
        if (args.length != 3 || !("check".equals(args[0]) || "restore".equals(args[0])))
            throw new IOException("Usage: FitRootMain check|restore <private pinned FIT image> <installed native library>");
        if (Os.getuid() != 0) throw new IOException("Root permission is required (UID 0).");
        DeviceIdentity.Snapshot identity = DeviceIdentity.read();
        if (identity.profile != DeviceProfile.FIT) throw new IOException("Standard Pocket FIT identity is required: " + identity.description());
        Command user = command(5000, "/system/bin/am", "get-current-user");
        user.requireSuccess("Read foreground Android user");
        if (!"0".equals(user.output.trim())) throw new IOException("Only Android user 0 is supported.");
        File privateDir = new File("/data/user/0/" + PACKAGE + "/files").getCanonicalFile();
        File imageFile = new File(args[1]).getCanonicalFile();
        if (!imageFile.getPath().startsWith(privateDir.getPath() + File.separator))
            throw new IOException("FIT image must be inside this application's private files directory.");
        StructStat imageStat = Os.lstat(imageFile.getPath());
        if (!OsConstants.S_ISREG(imageStat.st_mode) || imageStat.st_size != FitFirmwareImage.EXPECTED_SIZE)
            throw new IOException("FIT image must be the exact pinned regular file.");
        FitFirmwareImage image = FitFirmwareImage.validate(readBounded(imageFile, FitFirmwareImage.EXPECTED_SIZE));
        Command batteryDump = command(4000, "/system/bin/dumpsys", "battery");
        batteryDump.requireSuccess("Read battery state");
        RootPolicy.Battery battery = RootPolicy.requireBattery(batteryDump.output);
        emit("battery", "level", battery.level, "powered", battery.powered);
        emit("validated", "profile", "FIT", "model", identity.model, "device", identity.device,
                "systemExtProduct", identity.systemExtProduct, "productName", identity.productName, "uid", Os.getuid(),
                "firmwareBytes", image.size(), "sha256", FitFirmwareImage.EXPECTED_SHA256, "targetVersion", "unconfirmed");
        emit("current-firmware-version", "version", "", "available", false,
                "source", "unverified FIT version report", "observedAtMillis", System.currentTimeMillis(),
                "reason", "Installed FIT firmware version is not established by the supplied file or USB identity.",
                "liveQuery", false, "versionComparisonApplicable", false);

        try (RandomAccessFile lockFile = new RandomAccessFile(new File(privateDir, "root-restore.lock"), "rw");
             FileLock lock = lockFile.getChannel().tryLock()) {
            if (lock == null) throw new IOException("Another restore helper is already running.");
            verifyAndLoadLibrary(args[2]);
            FitUsbAccess usb = new FitUsbAccess(image);
            activeUsb = usb;
            FitUsbAccess.Snapshot initial = usb.snapshot(true);
            FitUsbAccess.Device initialDevice;
            EntrySession entry = null;
            if (!initial.boot.isEmpty()) initialDevice = initial.requireBootloader();
            else {
                initialDevice = initial.application.isEmpty() ? null : initial.requireApplication();
                entry = new EntrySession();
                entry.preflight(); // Metadata only; UART is not opened and no broadcast is sent.
            }
            if ("check".equals(args[0])) {
                emit("result", "status", "CHECK_OK", "message", "Pinned Pocket FIT image and root/device checks passed. USB mode: "
                                + initial.mode() + (initialDevice == null ? "; stock HS5 software-entry prerequisites passed" : "")
                                + ". UART/USB were not opened; GameWindow was not changed.",
                        "usbMode", initial.mode(), "usbOpened", false, "uartOpened", false, "gameWindowChanged", false,
                        "flashMayHaveStarted", false, "controlStateChanged", false, "inputFunctionVerified", false);
                return 0;
            }
            startControlReader();
            if (!CONTROL_READY.await(5, TimeUnit.SECONDS)) throw new IOException("App control pipe did not send START; no restore began.");
            checkCancelled();
            return restore(usb, image, initialDevice, entry);
        }
    }

    private static int restore(FitUsbAccess usb, FitFirmwareImage image, FitUsbAccess.Device initial,
                               EntrySession entry) {
        FitUsbEngine.Result result = null;
        String status = "FAILED", message = "FIT restore did not start.", cleanupError = "";
        boolean normalUsbObserved = false, bootloaderObserved = false;
        try {
            emit("progress", "phase", "ENTERING_BOOTLOADER", "message", "Preparing the stock Pocket FIT update path.", "packetIndex", 0, "packetCount", 1374);
            sleepChecked(3000); // Stock activity's initial delay, including already-loader path.
            FitUsbAccess.Snapshot now = usb.snapshot(true);
            if (now.boot.isEmpty()) {
                if (entry == null) throw new IOException("Previously detected bootloader disappeared; no fallback entry command will be sent.");
                if (!now.application.isEmpty()) {
                    FitUsbAccess.Device application = now.requireApplication();
                    if (initial != null) FitUsbAccess.requireSamePhysicalPort(initial, application);
                    else initial = application;
                } else emit("usb-application-absent", "stockUartEntryAvailable", true,
                        "message", "No normal USB controller enumerated. Attempting only the stock HS5 entry command; unique validated USB bootloader is still required before data.");
                entry.sendEntryOnce();
            }
            sleepChecked(3000); // Stock updateOnAZone settling, before USB initDevice.
            FitUsbAccess.Device loader = awaitBootloader(usb, initial);
            bootloaderObserved = true;
            if (entry != null) entry.closeOwnedUart();
            checkCancelled();
            usb.open(loader);
            FitUsbEngine engine = new FitUsbEngine(usb,
                    (stage, index, total, text) -> emit("progress", "phase", String.valueOf(stage), "packetIndex", index,
                            "packetCount", total, "message", text), FitRootMain::sleepChecked, CANCELLED::get);
            result = engine.run(image);
            status = String.valueOf(result.status);
            message = result.message;
            usb.close();
            if (result.status == FitUsbEngine.Status.SUCCESS && result.quitSent) {
                emit("progress", "phase", "WAITING_FOR_APPLICATION", "message", "Waiting for normal FIT USB mode after Quit.",
                        "packetIndex", 1374, "packetCount", 1374);
                normalUsbObserved = awaitApplication(usb, loader);
                if (!normalUsbObserved) {
                    status = "INDETERMINATE";
                    message += " Normal USB 1c4f:0002 was not observed with the bootloader absent.";
                }
            }
            checkCancelled();
        } catch (Exception error) {
            status = usb.flashMayHaveStarted || controlStateChanged ? "INDETERMINATE" : (CANCELLED.get() ? "CANCELLED" : "FAILED");
            message = explain(error) + " No command or firmware packet will be retried.";
        } finally {
            boolean interrupted = Thread.interrupted();
            List<String> failures = new ArrayList<>();
            try { usb.close(); } catch (Exception error) { failures.add(explain(error)); }
            if (entry != null) {
                try { entry.closeOwnedUart(); } catch (Exception error) { failures.add(explain(error)); }
                if (entry.paused) {
                    // A partially programmed/unknown controller must not receive
                    // normal initialization from our cleanup. No shared flags are invented.
                    if (usb.closedVerified && entry.closedVerified && !CHILD_STILL_RUNNING.get()
                            && (!controlStateChanged || normalUsbObserved)) {
                        try { entry.resume(); } catch (Exception error) { failures.add(explain(error)); }
                    } else failures.add("GameWindow FINISH withheld because controller mode or owned I/O closure is uncertain.");
                }
            }
            cleanupError = RootPolicy.boundedDiagnostic(String.join("; ", failures));
            if (!cleanupError.isEmpty()) { status = "INDETERMINATE"; message += "; cleanup: " + cleanupError; }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (CANCELLED.get() && "SUCCESS".equals(status)) {
            status = "INDETERMINATE"; message += "; app control was cancelled before completion.";
        }
        if ("SUCCESS".equals(status)) message += " Normal USB mode returned. Check all controls; physical inputs and firmware version are not verified.";
        emit("result", "status", status, "message", RootPolicy.boundedDiagnostic(message), "mode", "restore", "profile", "FIT",
                "packetsSent", result == null ? 0 : result.packetsSent, "packetsAcknowledged", result == null ? 0 : result.packetsAcknowledged,
                "quitSent", result != null && result.quitSent, "flashMayHaveStarted", usb.flashMayHaveStarted,
                "controlStateChanged", controlStateChanged, "normalUsbObserved", normalUsbObserved,
                "bootloaderObserved", bootloaderObserved, "applicationBannerObserved", false, "inputFunctionVerified", false,
                "cleanupError", cleanupError, "displayReinitialized", false, "androidRebootPerformed", false,
                "versionComparisonApplicable", false, "targetVersion", "unconfirmed");
        return "SUCCESS".equals(status) ? 0 : 1;
    }

    private static FitUsbAccess.Device awaitBootloader(FitUsbAccess usb, FitUsbAccess.Device before) throws IOException, InterruptedException {
        for (int attempt = 0; attempt <= 20; attempt++) {
            checkCancelled();
            FitUsbAccess.Snapshot snapshot = usb.snapshot(attempt == 0 || attempt == 20);
            if (!snapshot.boot.isEmpty()) {
                FitUsbAccess.Device loader = snapshot.requireBootloader();
                if (before != null) FitUsbAccess.requireSamePhysicalPort(before, loader);
                else emit("usb-topology", "before", "", "after", loader.physicalPort, "matched", false,
                        "identityAttested", false, "message", "No initial USB identity was available to bind the transition. Strict FIT identity, unique loader and endpoint checks apply.");
                emit("bootloader-usb-observed", "device", loader.usb.getDeviceName(), "physicalPort", loader.physicalPort);
                return loader;
            }
            if (attempt < 20) sleepChecked(1000);
        }
        throw new IOException("FIT USB bootloader 0483:5750 did not appear. No firmware packet was sent.");
    }

    private static boolean awaitApplication(FitUsbAccess usb, FitUsbAccess.Device before) throws IOException, InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            checkCancelled();
            FitUsbAccess.Snapshot snapshot = usb.snapshot(false);
            if (snapshot.boot.isEmpty() && snapshot.application.size() == 1) {
                FitUsbAccess.Device app = snapshot.requireApplication();
                FitUsbAccess.requireSamePhysicalPort(before, app);
                emit("normal-usb-observed", "device", app.usb.getDeviceName(), "physicalPort", app.physicalPort,
                        "bootloaderAbsent", true, "inputFunctionVerified", false);
                return true;
            }
            sleepChecked(200);
        } while (SystemClock.elapsedRealtime() < deadline);
        usb.snapshot(true);
        return false;
    }

    private static final class EntrySession {
        final List<String> pids = new ArrayList<>();
        long uartDevice;
        SerialPort port;
        EntryReader reader;
        boolean paused, attempted, closedVerified = true, resumed;

        void preflight() throws IOException, ErrnoException, InterruptedException {
            StructStat uart = Os.stat(UART);
            if (!OsConstants.S_ISCHR(uart.st_mode)) throw new IOException("FIT UART is not a character device.");
            uartDevice = uart.st_rdev;
            pids.addAll(gameWindowProcesses());
            if (pids.isEmpty()) throw new IOException("Normal-mode FIT entry requires running GameWindow. An already enumerated USB bootloader does not.");
            requireOwners();
            waitForReceiver();
        }

        void requireOwners() throws IOException {
            if (!gameWindowProcesses().containsAll(pids)) throw new IOException("Original GameWindow process disappeared; no restart will be forced.");
            OwnerSnapshot snapshot = owners(uartDevice);
            snapshot.requireReadable(); snapshot.requireOnlyGameWindow();
            for (Owner owner : snapshot.owners) if (!pids.contains(owner.pid)) throw new IOException("New GameWindow UART owner appeared.");
            emitOwners(snapshot);
        }

        void waitForReceiver() throws IOException, InterruptedException {
            long deadline = SystemClock.elapsedRealtime() + 5000;
            do {
                if (!gameWindowProcesses().containsAll(pids)) throw new IOException("Original GameWindow process disappeared.");
                Command dump = command(Math.min(3000, Math.max(1, deadline - SystemClock.elapsedRealtime())),
                        "/system/bin/dumpsys", "activity", "broadcasts");
                RootPolicy.requireBroadcastDump(dump.exitCode, dump.output);
                if (RootPolicy.hasFinishReceiver(dump.output, pids)) return;
                Thread.sleep(100);
            } while (SystemClock.elapsedRealtime() < deadline);
            throw new IOException("GameWindow's registered FINISH receiver was not observed; entry refused.");
        }

        void sendEntryOnce() throws IOException, InterruptedException, ErrnoException {
            if (attempted) throw new IOException("FIT entry cannot be retried.");
            checkCancelled(); requireOwners(); waitForReceiver();
            paused = true;
            broadcast(PAUSE);
            checkCancelled(); requireOwners();
            long senderStart = SystemClock.elapsedRealtime();
            emit("native-open-attempt", "path", UART, "baud", 115200, "stopBits", 1, "dataBits", 8,
                    "parity", 0, "flowControl", 0, "flagsArgument", 0);
            port = new SerialPort(UART);
            closedVerified = false;
            StructStat opened = Os.fstat(port.descriptor());
            if (!OsConstants.S_ISCHR(opened.st_mode) || opened.st_rdev != uartDevice) throw new IOException("Opened FIT UART identity mismatch.");
            emit("native-open-result", "opened", true, "path", UART, "blockingNativeIo", true,
                    "message", "Java deadlines cannot bound a stalled native open/read/write.");
            reader = new EntryReader(port);
            reader.start();
            checkCancelled();
            byte[] entry = {(byte) 0xe7, 0, 0, 0, 0, 0, 0, 0, 7, 7, (byte) 0xed};
            attempted = true;
            controlStateChanged = true; // Delivery may occur even when write throws.
            emit("tx-attempt", "transport", "uart", "kind", "boot-entry", "length", entry.length, "hex", hex(entry, 0, entry.length));
            port.output().write(entry);
            emit("tx-result", "transport", "uart", "kind", "boot-entry", "requested", entry.length,
                    "completed", true, "kernelByteCountAvailable", false);
            sleepChecked(1);
            long remaining = 500 - (SystemClock.elapsedRealtime() - senderStart);
            if (remaining > 0) reader.firstChunk.await(remaining, TimeUnit.MILLISECONDS);
            checkCancelled();
            emit("entry-response-window", "receivedChunk", reader.firstChunk.getCount() == 0,
                    "elapsedMillis", SystemClock.elapsedRealtime() - senderStart,
                    "message", "USB bootloader enumeration is required before data; a UART reply alone is not proof.");
        }

        void closeOwnedUart() throws IOException {
            if (port == null) return;
            boolean interrupted = Thread.interrupted();
            List<String> failures = new ArrayList<>();
            if (reader != null) reader.closing = true;
            try { port.closeOwned(reader == null ? null : () -> reader.requestStop()); }
            catch (Throwable error) { failures.add(explain(error)); }
            boolean descriptorClosed = false;
            try { Os.fstat(port.descriptor()); }
            catch (ErrnoException error) { descriptorClosed = error.errno == OsConstants.EBADF; }
            if (reader != null) {
                try { reader.join(1000); } catch (InterruptedException error) { interrupted = true; }
            }
            closedVerified = descriptorClosed && (reader == null || !reader.isAlive());
            if (!closedVerified) failures.add("Owned FIT UART closure or reader shutdown was not verified.");
            if (closedVerified) { port = null; reader = null; }
            emit("native-close-result", "path", UART, "closedVerified", closedVerified);
            if (interrupted) Thread.currentThread().interrupt();
            if (!failures.isEmpty()) throw new IOException(String.join("; ", failures));
        }

        void resume() throws IOException, InterruptedException {
            if (resumed || !paused) return;
            if (!closedVerified) throw new IOException("FIT UART closure is uncertain; handoff withheld.");
            requireOwners(); waitForReceiver(); broadcast(FINISH);
            long deadline = SystemClock.elapsedRealtime() + 5000;
            do {
                requireOwners();
                OwnerSnapshot snapshot = owners(uartDevice);
                snapshot.requireReadable(); snapshot.requireOnlyGameWindow();
                if (!snapshot.owners.isEmpty()) {
                    resumed = true;
                    emit("gamewindow-resumed", "originalPids", new JSONArray(pids), "uartOwnerObserved", true,
                            "deliveryVerified", false, "inputFunctionVerified", false);
                    return;
                }
                Thread.sleep(100);
            } while (SystemClock.elapsedRealtime() < deadline);
            throw new IOException("Original GameWindow UART ownership was not observed after FINISH.");
        }
    }

    private static final class EntryReader extends Thread {
        final SerialPort port;
        final CountDownLatch firstChunk = new CountDownLatch(1);
        volatile boolean closing, stop;
        EntryReader(SerialPort port) { super("fit-stock-entry-reader"); this.port = port; setDaemon(true); }
        void requestStop() { stop = true; interrupt(); }
        @Override public void run() {
            try {
                while (!stop) {
                    int available = port.input().available();
                    if (available > 0) {
                        byte[] bytes = new byte[Math.min(available, 4096)];
                        int count = port.input().read(bytes);
                        if (count < 0) throw new IOException("FIT UART returned EOF.");
                        if (count > 0) {
                            emit("rx", "transport", "uart", "kind", "entry-observation", "returned", count,
                                    "hex", hex(bytes, 0, count), "availableSnapshot", available, "responseContentsVerified", false);
                            firstChunk.countDown();
                        }
                    }
                    Thread.sleep(50);
                }
            } catch (IOException | InterruptedException error) {
                if (!closing) emit("entry-reader-error", "message", explain(error));
            } finally { emit("entry-reader-stopped", "closing", closing); }
        }
    }

    private static void verifyAndLoadLibrary(String suppliedPath) throws Exception {
        File file = new File(suppliedPath);
        String canonical = file.getCanonicalPath();
        if (!file.isAbsolute() || !canonical.equals(file.getAbsolutePath())
                || !canonical.matches("/data/app/(?:[^/]+/)?org\\.kpfe\\.restore-[^/]+/lib/arm64/libserial_port\\.so"))
            throw new IOException("Native library must be the canonical installed Controller FW Restore ARM64 library.");
        for (File item = file; !"/".equals(item.getPath()); item = item.getParentFile()) {
            if (item == null) throw new IOException("Invalid installed native library path.");
            StructStat stat = Os.lstat(item.getPath());
            if ((stat.st_uid != 0 && stat.st_uid != 1000) || (stat.st_mode & 0002) != 0
                    || ((stat.st_mode & 0020) != 0 && stat.st_gid != 0 && stat.st_gid != 1000))
                throw new IOException("Installed native library path is writable by an untrusted user/group.");
        }
        StructStat stat = Os.lstat(canonical);
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != 9992) throw new IOException("Stock serial library size/type mismatch.");
        byte[] bytes = readBounded(file, 9992);
        if (!NATIVE_SHA256.equals(hex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 32)))
            throw new IOException("Stock FIT serial library hash mismatch.");
        SerialPort.loadVerifiedLibrary(canonical);
        emit("native-loaded", "path", canonical, "bytes", bytes.length, "sha256", NATIVE_SHA256, "uartOpened", false);
    }

    private static void startControlReader() {
        Thread thread = new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                if (!"START".equals(reader.readLine())) { CANCELLED.set(true); return; }
                CONTROL_READY.countDown();
                String line;
                while ((line = reader.readLine()) != null) if ("CANCEL".equals(line)) { CANCELLED.set(true); return; }
                CANCELLED.set(true);
            } catch (IOException error) { CANCELLED.set(true); }
            finally { CONTROL_READY.countDown(); }
        }, "fit-app-control");
        thread.setDaemon(true); thread.start();
    }

    static void checkCancelled() throws InterruptedException {
        if (CANCELLED.get() || Thread.currentThread().isInterrupted() || CHILD_STILL_RUNNING.get())
            throw new InterruptedException("FIT operation cancelled or subprocess cleanup is uncertain.");
    }

    private static void sleepChecked(long millis) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + millis;
        while (SystemClock.elapsedRealtime() < deadline) {
            checkCancelled();
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining > 0) Thread.sleep(Math.min(50, remaining));
        }
        checkCancelled();
    }

    static void requireNoUsbOwners(String node) throws IOException {
        if (!node.matches("/dev/bus/usb/[0-9]+/[0-9]+")) throw new IOException("Unexpected USB device node.");
        try {
            StructStat stat = Os.stat(node);
            if (!OsConstants.S_ISCHR(stat.st_mode)) throw new IOException("USB node is not a character device.");
            OwnerSnapshot snapshot = owners(stat.st_rdev);
            snapshot.requireReadable(); emitOwners(snapshot);
            if (!snapshot.owners.isEmpty()) throw new IOException("USB bootloader is already owned by another process: " + snapshot.describe());
        } catch (ErrnoException error) { throw new IOException("Cannot inspect USB device ownership.", error); }
    }

    private static void broadcast(String action) throws IOException, InterruptedException {
        Command result = command(5000, "/system/bin/am", "broadcast", "--user", "0", "-p", GAMEWINDOW, "-a", action);
        emit("broadcast", "action", action, "exitCode", result.exitCode, "deliveryVerified", false,
                "message", "Receiver/process metadata does not prove delivery or paused reads.");
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
            if (!unreadable.isEmpty()) throw new IOException("Cannot inspect device ownership: " + String.join(", ", unreadable));
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
        if (processes == null) throw new IOException("Cannot inspect /proc for device owners.");
        String self = Integer.toString(Os.getpid());
        long deadline = SystemClock.elapsedRealtime() + 5000;
        for (File process : processes) {
            if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("Device owner scan timed out; transfer refused.");
            String pid = process.getName();
            if (!pid.matches("[0-9]+") || pid.equals(self)) continue;
            File directory = new File(process, "fd");
            File[] descriptors = directory.listFiles();
            if (descriptors == null) {
                if (process.exists()) result.unreadable.add("pid " + pid + " fd directory");
                continue;
            }
            for (File descriptor : descriptors) {
                if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("Device owner scan timed out; transfer refused.");
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

    static byte[] readBounded(File file, int limit) throws IOException {
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

    static synchronized void emit(String event, Object... pairs) {
        try {
            JSONObject object = new JSONObject();
            object.put("event", event);
            object.put("schema", "kpfe-fit-root-restore/v1");
            object.put("pid", Os.getpid());
            object.put("elapsedRealtimeMs", SystemClock.elapsedRealtime());
            object.put("wallTimeMillis", System.currentTimeMillis());
            for (int i = 0; i < pairs.length; i += 2) object.put((String) pairs[i], pairs[i + 1]);
            System.out.println(object.toString());
            System.out.flush();
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    static String explain(Throwable error) {
        String message = error.getMessage();
        return RootPolicy.boundedDiagnostic(error.getClass().getSimpleName() + (message == null ? "" : ": " + message));
    }

    static String hex(byte[] bytes, int offset, int length) {
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
