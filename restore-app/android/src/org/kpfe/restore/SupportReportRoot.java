package org.kpfe.restore;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Passive app_process collector. Never opens a tty/USB device or sends controller commands. */
public final class SupportReportRoot {
    private static final String VENDOR_UPDATER = "com.ayaneo.newcontrollerota";
    private static final String[] PACKAGES = {VENDOR_UPDATER, "com.ayaneo.gamewindow", "com.ayaneo.settings", "org.kpfe.restore"};
    private static final String[] PROPERTIES = {"ro.product.model", "ro.product.device", "ro.product.name",
        "ro.product.system_ext.name", "ro.product.system_ext.device", "ro.product.product.name",
        "ro.product.product.device", "ro.product.vendor.name", "ro.product.vendor.device", "ro.hardware",
        "ro.board.platform", "ro.build.fingerprint", "ro.build.version.release", "ro.build.version.sdk"};
    private static final Pattern RELEVANT = Pattern.compile("usb|ttyHS[15]|uart|hid|gamepad|ayaneo|konkr|kr02|firmware|controller|restore|serialport|ch57", Pattern.CASE_INSENSITIVE);
    private static final Pattern THREADTIME = Pattern.compile("^\\d{2}-\\d{2}\\s+[\\d:.]+\\s+(\\d+)\\s+\\d+\\s+.*");
    private static final Pattern SYSTEM_USB = Pattern.compile("Usb(?:Host|Device|User|Permission|Service|Port|Profile|Settings)|usbfs|libusb|USB.*(?:permission|denied|error|fail|busy|disconnect|connect|attach|detach|claim)|(?:permission|denied|error|fail|busy).*USB|com\\.ayaneo\\.newcontrollerota", Pattern.CASE_INSENSITIVE);
    private final long deadline = SystemClock.elapsedRealtime() + 35000;
    private final Set<String> relevantPids = new HashSet<>();
    private final Set<String> vendorPids = new LinkedHashSet<>();
    private final Map<Long, String> fitUsbNodes = new LinkedHashMap<>();
    private final Map<Long, Long> fitUsbObservedAt = new LinkedHashMap<>();
    private Context systemContext;
    private UsbManager rootUsb;

    private SupportReportRoot() { }

    public static void main(String[] args) {
        SupportReportRoot report = new SupportReportRoot();
        try {
            if (args.length != 1 || !args[0].matches("[1-9][0-9]{0,8}")) throw new IOException("Expected application PID only");
            report.relevantPids.add(args[0]);
            emit("root", new JSONObject().put("uid", Os.getuid()).put("readOnly", true).put("transportOpened", false));
            if (Os.getuid() != 0) throw new IOException("Root access was not granted");
            report.frameworkUsbProbe();
            for (String property : new String[] {"ro.product.system_ext.name", "ro.product.product.name", "ro.product.device"})
                report.command("property:" + property, 700, 4096, false, "/system/bin/getprop", property);
            report.vendorIdentity();
            report.vendorProcesses();
            report.vendorLogs();
            report.recentLogcat();
            report.usbSnapshots();
            report.uartOwners();
            for (String property : PROPERTIES)
                if (!property.equals("ro.product.system_ext.name") && !property.equals("ro.product.product.name") && !property.equals("ro.product.device"))
                    report.command("property:" + property, 700, 4096, false, "/system/bin/getprop", property);
            report.command("battery", 1800, 16384, false, "/system/bin/dumpsys", "battery");
            for (String flag : new String[] {"ayaneo/share/is_firmware_on_update_zone", "ayaneo/share/is_firmware_working"})
                report.command("setting:" + flag, 1200, 4096, false, "/system/bin/settings", "get", "system", flag);
            report.readFile("controller-version-cache", new File("/data/system/aya/aya_firmware_local_version.conf"), 64, false);
            for (String name : PACKAGES) if (!VENDOR_UPDATER.equals(name)) report.packageVersion(name);
            report.usb();
            report.command("kernel-usb-tty", 2500, 512 * 1024, true, "/system/bin/dmesg");
            emit("complete", new JSONObject().put("deadlineReached", report.remaining() <= 0)
                    .put("note", "Missing sections and bounded/truncated captures are not proof that hardware is absent."));
        } catch (Throwable error) {
            emitError("collector", error);
        }
        System.out.flush();
        System.exit(0);
    }

    private long remaining() { return Math.max(0, deadline - SystemClock.elapsedRealtime()); }
    private void requireTime() throws IOException { if (remaining() <= 0) throw new IOException("Support collection deadline reached"); }

    /** Checks the root USB adapter's framework prerequisites without granting or opening anything. */
    private void frameworkUsbProbe() {
        JSONObject result = new JSONObject();
        String stage = "root-usb-client-context";
        try {
            requireTime();
            emit("usb-framework-root-start", new JSONObject().put("stage", stage));
            Context context = RootUsbContext.create();
            result.put("mainLooperPrepared", Looper.getMainLooper() != null);
            systemContext = context;
            result.put("systemContextCreated", true).put("systemThread", false);
            stage = "USB-service-and-permission";
            try {
                int permission = context.checkCallingOrSelfPermission("android.permission.MANAGE_USB");
                result.put("manageUsbPermissionResult", permission).put("manageUsbGranted", permission == PackageManager.PERMISSION_GRANTED);
            } catch (Exception e) { result.put("manageUsbPermissionReadError", rootError(e)); }
            UsbManager manager = (UsbManager)context.getSystemService(Context.USB_SERVICE);
            rootUsb = manager;
            result.put("usbManagerAvailable", manager != null);
            if (manager == null) throw new IOException("Root USB manager unavailable");
            stage = "resolve-grantPermission";
            try {
                java.lang.reflect.Method grant = manager.getClass().getMethod("grantPermission", UsbDevice.class, int.class);
                result.put("grantPermissionMethodResolved", grant != null);
            } catch (Exception e) { result.put("grantPermissionMethodResolved", false).put("grantPermissionResolutionError", rootError(e)); }
            result.put("grantPermissionInvoked", false);
            stage = "enumerate-and-hasPermission";
            JSONArray devices = new JSONArray();
            for (UsbDevice device : manager.getDeviceList().values()) {
                requireTime();
                if (devices.length() >= 64) break;
                JSONObject row = new JSONObject().put("name", device.getDeviceName()).put("vendorId", device.getVendorId())
                        .put("productId", device.getProductId()).put("class", device.getDeviceClass())
                        .put("interfaceCount", device.getInterfaceCount());
                try { row.put("permissionAlreadyGranted", manager.hasPermission(device)); }
                catch (Exception e) { row.put("permissionReadError", rootError(e)); }
                devices.put(row);
            }
            result.put("devices", devices).put("completed", true);
        } catch (Throwable e) {
            try { result.put("completed", false).put("failedStage", stage).put("error", rootError(e))
                    .put("errorStack", rootStack(e)); }
            catch (Exception ignored) { }
        }
        try { result.put("transportOpened", false).put("grantPermissionInvoked", false); } catch (Exception ignored) { }
        emit("usb-framework-root", result);
    }

    private void vendorIdentity() {
        packageVersion(VENDOR_UPDATER);
        JSONObject data = new JSONObject();
        try {
            data.put("package", VENDOR_UPDATER);
            try {
                if (systemContext == null) throw new IOException("Root package-manager context unavailable");
                PackageInfo info = systemContext.getPackageManager().getPackageInfo(VENDOR_UPDATER, PackageManager.GET_SIGNING_CERTIFICATES);
                JSONObject identity = new JSONObject().put("versionName", info.versionName).put("versionCode", info.getLongVersionCode());
                if (info.signingInfo != null) {
                    identity.put("currentSignerSha256", certificateHashes(info.signingInfo.getApkContentsSigners()));
                    if (!info.signingInfo.hasMultipleSigners())
                        identity.put("signerHistorySha256", certificateHashes(info.signingInfo.getSigningCertificateHistory()));
                } else identity.put("signerError", "Installed package has no SigningInfo");
                data.put("packageManager", identity);
            } catch (Exception e) { data.put("packageManagerError", rootError(e)); }
            requireTime();
            SupportProcess.Result paths = SupportProcess.run(Math.min(1500, remaining()), 16384,
                    "/system/bin/pm", "path", VENDOR_UPDATER);
            data.put("apkPathsCommand", resultJson(paths));
            JSONArray apks = new JSONArray();
            long hashDeadline = Math.min(deadline, SystemClock.elapsedRealtime() + 5000);
            boolean complete = paths.exitCode == 0 && !paths.timedOut && !paths.truncated && paths.readError.isEmpty();
            for (String line : paths.output.split("\n")) {
                if (!line.startsWith("package:")) continue;
                if (apks.length() >= 8 || SystemClock.elapsedRealtime() >= hashDeadline) { complete = false; break; }
                JSONObject apk = new JSONObject();
                try {
                    File file = new File(line.substring(8).trim()).getCanonicalFile();
                    String path = file.getPath();
                    if (!path.endsWith(".apk") || !(path.startsWith("/data/app/") || path.startsWith("/system/app/")
                            || path.startsWith("/system/priv-app/") || path.startsWith("/product/app/")
                            || path.startsWith("/product/priv-app/") || path.startsWith("/system_ext/app/")
                            || path.startsWith("/system_ext/priv-app/"))) throw new IOException("Unexpected installed APK location");
                    apk.put("path", path);
                    StructStat before = Os.stat(path);
                    if (!OsConstants.S_ISREG(before.st_mode) || before.st_size > 128L * 1024 * 1024)
                        throw new IOException("Installed APK type/size exceeds capture bounds");
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long count = 0;
                    try (FileInputStream input = new FileInputStream(file)) {
                        byte[] buffer = new byte[16384]; int n;
                        while ((n = input.read(buffer)) >= 0) {
                            if (SystemClock.elapsedRealtime() >= hashDeadline || count + n > 128L * 1024 * 1024)
                                throw new IOException("Installed APK hash read limit reached");
                            digest.update(buffer, 0, n); count += n;
                        }
                    }
                    StructStat after = Os.stat(path);
                    apk.put("bytes", count).put("sha256", hex(digest.digest())).put("changedDuringRead",
                            count != before.st_size || before.st_ino != after.st_ino || before.st_size != after.st_size
                                    || before.st_mtim.tv_sec != after.st_mtim.tv_sec || before.st_mtim.tv_nsec != after.st_mtim.tv_nsec);
                } catch (Exception e) { complete = false; apk.put("error", rootError(e)); }
                apks.put(apk);
            }
            data.put("apks", apks).put("hashScanComplete", complete && apks.length() > 0)
                    .put("installedApkFound", apks.length() > 0).put("signatureSource", "Android PackageManager SigningInfo; APK bytes are separately hashed");
        } catch (Exception e) { try { data.put("error", rootError(e)); } catch (Exception ignored) { } }
        emit("vendor-updater-identity", data);
    }

    private static JSONArray certificateHashes(Signature[] certificates) throws Exception {
        JSONArray hashes = new JSONArray();
        if (certificates != null) for (Signature certificate : certificates) {
            if (hashes.length() >= 8) break;
            hashes.put(hex(MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())));
        }
        return hashes;
    }

    private void vendorProcesses() {
        JSONObject result = new JSONObject();
        boolean complete = true;
        int visited = 0, unreadable = 0;
        try {
            requireTime();
            File[] processes = new File("/proc").listFiles();
            if (processes == null) throw new IOException("Process metadata unavailable");
            long scanDeadline = Math.min(deadline, SystemClock.elapsedRealtime() + 1600);
            for (File process : processes) {
                if (!process.getName().matches("[0-9]+")) continue;
                if (++visited > 2048 || SystemClock.elapsedRealtime() >= scanDeadline) { complete = false; break; }
                try {
                    String name = new String(bytes(new File(process, "cmdline"), 1024), StandardCharsets.UTF_8).split("\u0000", 2)[0];
                    for (String known : PACKAGES) if (name.equals(known) || name.startsWith(known + ":")) {
                        relevantPids.add(process.getName());
                        if (known.equals(VENDOR_UPDATER)) vendorPids.add(process.getName());
                    }
                } catch (IOException ignored) { unreadable++; }
            }
        } catch (Exception e) {
            complete = false;
            try { result.put("error", rootError(e)); } catch (Exception ignored) { }
        }
        try {
            result.put("package", VENDOR_UPDATER).put("activePids", new JSONArray(vendorPids)).put("scanComplete", complete && unreadable == 0)
                    .put("scanLimited", !complete).put("maxDetailedPids", 4)
                    .put("processesVisited", visited).put("unreadableProcesses", unreadable).put("observedAtElapsedMillis", SystemClock.elapsedRealtime());
        } catch (Exception ignored) { }
        emit("vendor-updater-processes", result);
        int captured = 0;
        for (String pid : vendorPids) { if (++captured > 4) break; vendorThreads(pid); }
    }

    private void vendorThreads(String pid) {
        JSONObject result = new JSONObject();
        JSONArray threads = new JSONArray();
        boolean limited = false;
        try {
            requireTime();
            result.put("pid", pid).put("observedAtElapsedMillis", SystemClock.elapsedRealtime());
            File[] tasks = new File("/proc/" + pid + "/task").listFiles();
            if (tasks == null) throw new IOException("Updater task metadata unavailable or process exited");
            long scanDeadline = Math.min(deadline, SystemClock.elapsedRealtime() + 700);
            for (File task : tasks) {
                if (!task.getName().matches("[0-9]+")) continue;
                if (threads.length() >= 64 || SystemClock.elapsedRealtime() >= scanDeadline) { limited = true; break; }
                JSONObject thread = new JSONObject().put("tid", task.getName());
                thread.put("name", metadata(new File(task, "comm"))).put("wchan", metadata(new File(task, "wchan")));
                try {
                    String status = new String(bytes(new File(task, "status"), 8192), StandardCharsets.UTF_8);
                    StringBuilder fields = new StringBuilder();
                    for (String line : status.split("\n")) if (line.matches("^(?:Name|State|Tgid|Pid|PPid|Threads|voluntary_ctxt_switches|nonvoluntary_ctxt_switches):.*"))
                        fields.append(line).append('\n');
                    thread.put("status", scrub(fields.toString()));
                } catch (Exception e) { thread.put("statusError", rootError(e)); }
                threads.put(thread);
            }
            result.put("threads", threads).put("limited", limited).put("captured", true)
                    .put("note", "Passive thread state/wait-channel snapshot only; no signal or debugger attachment.");
        } catch (Exception e) {
            try { result.put("threads", threads).put("captured", false).put("error", rootError(e)); } catch (Exception ignored) { }
        }
        emit("vendor-updater-threads", result);
    }

    private void vendorLogs() {
        if (vendorPids.isEmpty()) {
            try { emit("vendor-updater-logcat", new JSONObject().put("available", false).put("reason", "No active updater PID was found; see process-scan completeness and system log sections.")); }
            catch (Exception ignored) { }
            return;
        }
        int captured = 0;
        for (String pid : vendorPids) {
            if (++captured > 4) break;
            try {
                requireTime();
                SupportProcess.Result result = SupportProcess.runTail(Math.min(1800, remaining()), 128 * 1024,
                        "/system/bin/logcat", "-d", "-v", "threadtime", "--pid=" + pid, "-t", "1200");
                emit("vendor-updater-logcat", resultJson(result).put("pid", pid).put("available", result.exitCode == 0)
                        .put("requestedTailLines", 1200).put("byteLimit", 128 * 1024).put("text", scrub(result.output))
                        .put("scope", "All recent messages from this observed updater PID, with identifier redaction; no keyword filter")
                        .put("observedAtElapsedMillis", SystemClock.elapsedRealtime()));
            } catch (Exception e) { emitError("vendor-updater-logcat:" + pid, e); }
        }
    }

    private void usbSnapshots() {
        long started = SystemClock.elapsedRealtime();
        for (int sample = 0; sample < 3; sample++) {
            JSONObject result = new JSONObject();
            try {
                requireTime();
                long pause = started + sample * 1000L - SystemClock.elapsedRealtime();
                if (pause > 0) Thread.sleep(Math.min(pause, remaining()));
                requireTime();
                JSONArray devices = new JSONArray();
                boolean found = false, application = false, complete = true;
                if (rootUsb != null) {
                    result.put("source", "root-framework");
                    try {
                    Map<String, UsbDevice> enumerated = rootUsb.getDeviceList();
                    if (enumerated.size() > 64) complete = false;
                    for (UsbDevice device : enumerated.values()) {
                        if (devices.length() >= 64) break;
                        boolean expected = device.getVendorId() == 0x0483 && device.getProductId() == 0x5750;
                        found |= expected;
                        application |= device.getVendorId() == 0x1c4f && device.getProductId() == 0x0002;
                        JSONObject node = new JSONObject().put("node", device.getDeviceName()).put("vendorId", device.getVendorId())
                                .put("productId", device.getProductId()).put("expectedIdentifier", expected);
                        try { node.put("permissionAlreadyGranted", rootUsb.hasPermission(device)); }
                        catch (Exception e) { node.put("permissionReadError", rootError(e)); }
                        if (expected) registerUsbNode(device.getDeviceName(), node);
                        devices.put(node);
                    }
                    } catch (Exception e) {
                        result.put("frameworkEnumerationError", rootError(e));
                        rootUsb = null; devices = new JSONArray(); found = false; application = false; complete = true;
                    }
                }
                if (rootUsb == null) {
                    result.put("source", "sysfs");
                    File[] nodes = new File("/sys/bus/usb/devices").listFiles();
                    if (nodes == null) throw new IOException("Neither root USB framework nor USB sysfs enumeration is available");
                    for (File node : nodes) {
                        if (!node.getName().matches("[0-9]+-[0-9]+(?:\\.[0-9]+)*")) continue;
                        if (devices.length() >= 64) { complete = false; break; }
                        JSONObject entry = new JSONObject().put("sysfsNode", node.getName());
                        try {
                            int vendor = Integer.parseInt(metadata(new File(node, "idVendor")), 16);
                            int product = Integer.parseInt(metadata(new File(node, "idProduct")), 16);
                            boolean expected = vendor == 0x0483 && product == 0x5750; found |= expected;
                            application |= vendor == 0x1c4f && product == 0x0002;
                            entry.put("vendorId", vendor).put("productId", product).put("expectedIdentifier", expected);
                            String path = String.format(Locale.ROOT, "/dev/bus/usb/%03d/%03d",
                                    Integer.parseInt(metadata(new File(node, "busnum"))), Integer.parseInt(metadata(new File(node, "devnum"))));
                            entry.put("node", path);
                            if (expected) registerUsbNode(path, entry);
                        } catch (Exception e) { complete = false; entry.put("error", rootError(e)); }
                        devices.put(entry);
                    }
                }
                result.put("available", true).put("complete", complete).put("devices", devices).put("expectedIdentifierPresent", found)
                        .put("applicationIdentifierPresent", application);
            } catch (Exception e) {
                try { result.put("available", false).put("error", rootError(e)); } catch (Exception ignored) { }
            }
            try { result.put("sampleIndex", sample).put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                    .put("transportOpened", false).put("note", "Enumeration is not proof of a healthy bootloader, firmware compatibility or interface ownership."); }
            catch (Exception ignored) { }
            emit("usb-snapshot", result);
        }
    }

    private void registerUsbNode(String path, JSONObject output) throws Exception {
        if (!path.matches("/dev/bus/usb/[0-9]{3}/[0-9]{3}")) { output.put("statError", "Unexpected USB node path"); return; }
        try {
            StructStat stat = Os.stat(path);
            output.put("characterDevice", OsConstants.S_ISCHR(stat.st_mode)).put("rdev", stat.st_rdev)
                    .put("mode", Integer.toOctalString(stat.st_mode)).put("uid", stat.st_uid).put("gid", stat.st_gid);
            if (OsConstants.S_ISCHR(stat.st_mode)) {
                fitUsbNodes.put(stat.st_rdev, path); fitUsbObservedAt.put(stat.st_rdev, SystemClock.elapsedRealtime());
            }
        } catch (Exception e) { output.put("statError", rootError(e)); }
    }

    private void command(String section, int timeout, int limit, boolean filter, String... args) {
        try {
            requireTime();
            SupportProcess.Result result = filter ? SupportProcess.runTail(Math.min(timeout, remaining()), limit, args)
                    : SupportProcess.run(Math.min(timeout, remaining()), limit, args);
            String body = filter ? relevantLines(result.output, false) : scrub(result.output);
            emit(section, resultJson(result).put("text", body));
        } catch (Exception e) { emitError(section, e); }
    }

    private void packageVersion(String name) {
        try {
            requireTime();
            SupportProcess.Result result = SupportProcess.run(Math.min(1800, remaining()), 192 * 1024,
                    "/system/bin/dumpsys", "package", name);
            StringBuilder versions = new StringBuilder();
            for (String line : result.output.split("\n"))
                if (line.contains("versionCode=") || line.contains("versionName=")) versions.append(line.trim()).append('\n');
            emit("package:" + name, resultJson(result).put("available", versions.length() != 0).put("versions", versions.toString()));
        } catch (Exception e) { emitError("package:" + name, e); }
    }

    private void readFile(String section, File path, int limit, boolean hex) {
        try {
            requireTime();
            StructStat stat = Os.stat(path.getPath());
            if (!OsConstants.S_ISREG(stat.st_mode)) throw new IOException("Expected a regular metadata file");
            byte[] bytes = bytes(path, limit + 1);
            boolean truncated = bytes.length > limit;
            if (truncated) bytes = Arrays.copyOf(bytes, limit);
            emit(section, new JSONObject().put("path", path.getPath()).put("truncated", truncated)
                    .put(hex ? "hex" : "text", hex ? hex(bytes) : scrub(new String(bytes, StandardCharsets.UTF_8))));
        } catch (Exception e) { emitError(section, e); }
    }

    private void usb() {
        try {
            requireTime();
            File[] nodes = new File("/sys/bus/usb/devices").listFiles();
            if (nodes == null) throw new IOException("USB sysfs is unavailable");
            Arrays.sort(nodes);
            int inspected = 0;
            for (File node : nodes) {
                requireTime();
                String name = node.getName();
                if (!name.matches("(?:usb[0-9]+|[0-9]+-[0-9]+(?:\\.[0-9]+)*(?::[0-9]+\\.[0-9]+)?)")) continue;
                if (++inspected > 64) { emit("usb-limit", new JSONObject().put("maxNodes", 64)); break; }
                JSONObject info = new JSONObject().put("node", name);
                File canonical = node.getCanonicalFile();
                if (!canonical.getPath().startsWith("/sys/devices/")) throw new IOException("Unexpected USB sysfs path");
                info.put("sysfsPath", canonical.getPath());
                for (String attribute : new String[] {"idVendor", "idProduct", "bcdDevice", "bDeviceClass", "bDeviceSubClass",
                        "bDeviceProtocol", "bNumConfigurations", "bConfigurationValue", "bNumInterfaces", "busnum", "devnum",
                        "speed", "version", "manufacturer", "product", "bInterfaceNumber", "bAlternateSetting", "bNumEndpoints",
                        "bInterfaceClass", "bInterfaceSubClass", "bInterfaceProtocol", "interface", "authorized"}) {
                    File file = new File(canonical, attribute);
                    if (file.exists()) info.put(attribute, metadata(file));
                }
                File driver = new File(canonical, "driver");
                if (driver.exists()) info.put("driver", driver.getCanonicalFile().getName());
                JSONArray endpoints = new JSONArray();
                File[] children = canonical.listFiles();
                if (children != null) for (File child : children) {
                    if (!child.getName().matches("ep_[0-9a-fA-F]{2}") || endpoints.length() >= 32) continue;
                    JSONObject endpoint = new JSONObject().put("node", child.getName());
                    for (String key : new String[] {"bEndpointAddress", "bmAttributes", "wMaxPacketSize", "bInterval", "type", "direction", "interval"})
                        if (new File(child, key).exists()) endpoint.put(key, metadata(new File(child, key)));
                    endpoints.put(endpoint);
                }
                info.put("endpoints", endpoints);
                emit("usb-node", info);
                File descriptors = new File(canonical, "descriptors");
                if (descriptors.exists()) readFile("usb-descriptors:" + name, descriptors, 16384, true);
            }
        } catch (Exception e) { emitError("usb", e); }
    }

    private String metadata(File file) {
        try {
            requireTime();
            if (!OsConstants.S_ISREG(Os.stat(file.getPath()).st_mode)) throw new IOException("Not regular metadata");
            return scrub(new String(bytes(file, 512), StandardCharsets.UTF_8).trim());
        } catch (Exception e) { return "unavailable: " + e.getClass().getSimpleName(); }
    }

    private void uartOwners() {
        try {
            List<Long> devices = new ArrayList<>();
            for (String name : new String[] {"ttyHS1", "ttyHS5"}) {
                requireTime();
                try {
                    StructStat stat = Os.stat("/dev/" + name);
                    emit("uart-metadata:" + name, new JSONObject().put("characterDevice", OsConstants.S_ISCHR(stat.st_mode))
                            .put("mode", Integer.toOctalString(stat.st_mode)).put("uid", stat.st_uid).put("gid", stat.st_gid)
                            .put("rdev", stat.st_rdev));
                    if (OsConstants.S_ISCHR(stat.st_mode)) devices.add(stat.st_rdev);
                } catch (Exception e) { emitError("uart-metadata:" + name, e); }
            }
            File[] processes = new File("/proc").listFiles();
            if (processes == null) throw new IOException("Process metadata unavailable");
            Arrays.sort(processes, (left, right) -> Integer.compare(processPriority(left), processPriority(right)));
            int visited = 0, denied = 0, owners = 0, usbOwners = 0, fdErrors = 0;
            long scanDeadline = Math.min(deadline, SystemClock.elapsedRealtime() + 6000);
            boolean limited = false;
            for (File process : processes) {
                if (!process.getName().matches("[0-9]+")) continue;
                requireTime();
                if (++visited > 2048 || SystemClock.elapsedRealtime() >= scanDeadline || owners >= 128) { limited = true; break; }
                String command;
                try { command = new String(bytes(new File(process, "cmdline"), 1024), StandardCharsets.UTF_8).split("\u0000", 2)[0]; }
                catch (IOException e) { denied++; continue; }
                boolean stock = false;
                for (String name : PACKAGES) if (command.equals(name) || command.startsWith(name + ":")) stock = true;
                if (stock) {
                    relevantPids.add(process.getName());
                    emit("stock-process", new JSONObject().put("pid", process.getName()).put("name", command));
                }
                File[] descriptors = new File(process, "fd").listFiles();
                if (descriptors == null) { denied++; continue; }
                int checked = 0;
                for (File descriptor : descriptors) {
                    requireTime();
                    if (++checked > 4096 || SystemClock.elapsedRealtime() >= scanDeadline) { limited = true; break; }
                    try {
                        StructStat stat = Os.stat(descriptor.getPath());
                        if (OsConstants.S_ISCHR(stat.st_mode) && (devices.contains(stat.st_rdev) || fitUsbNodes.containsKey(stat.st_rdev))) {
                            if (++owners > 128) break;
                            JSONObject owner = new JSONObject().put("pid", process.getName()).put("name", scrub(command))
                                    .put("fd", descriptor.getName()).put("rdev", stat.st_rdev).put("observedAtElapsedMillis", SystemClock.elapsedRealtime());
                            if (fitUsbNodes.containsKey(stat.st_rdev)) {
                                usbOwners++;
                                emit("usb-owner", owner.put("node", fitUsbNodes.get(stat.st_rdev))
                                        .put("nodeObservedAtElapsedMillis", fitUsbObservedAt.get(stat.st_rdev))
                                        .put("matchedPreviouslyObservedUsbNode", true).put("interfaceClaimProven", false));
                            } else emit("uart-owner", owner);
                        }
                    } catch (Exception ignored) { fdErrors++; /* Process/descriptor may disappear during this snapshot. */ }
                }
            }
            emit("owner-scan", new JSONObject().put("processesVisited", visited).put("unreadableProcessSections", denied)
                    .put("unreadableDescriptors", fdErrors).put("usbOwnersCaptured", usbOwners).put("targetUsbNodes", new JSONArray(fitUsbNodes.values()))
                    .put("ownerLimit", 128).put("processLimit", 2048).put("scanLimited", limited).put("snapshotAtomic", false)
                    .put("note", "An open descriptor does not prove exclusive interface ownership; missing owners in a partial scan do not prove the interface is free."));
        } catch (Exception e) { emitError("uart-owners", e); }
    }

    private int processPriority(File process) {
        return vendorPids.contains(process.getName()) ? 0 : relevantPids.contains(process.getName()) ? 1 : 2;
    }

    private void recentLogcat() {
        try {
            requireTime();
            SupportProcess.Result result = SupportProcess.runTail(Math.min(2500, remaining()), 512 * 1024,
                    "/system/bin/logcat", "-d", "-v", "threadtime", "-t", "2000");
            emit("recent-app-stock-logcat", resultJson(result).put("pidScope", new JSONArray(relevantPids))
                    .put("text", relevantLines(result.output, true)));
            emit("system-usb-logcat", resultJson(result).put("scope", "USB service/permission/error and updater-named messages across all PIDs")
                    .put("requestedTailLines", 2000).put("text", filterLines(result.output, SYSTEM_USB, null)));
        } catch (Exception e) { emitError("recent-app-stock-logcat", e); }
    }

    private String relevantLines(String source, boolean restrictPids) {
        return filterLines(source, RELEVANT, restrictPids ? relevantPids : null);
    }

    static String filterLines(String source, Pattern selected, Set<String> pids) {
        java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
        int length = 0;
        boolean omitted = false;
        for (String line : source.split("\n")) {
            if (selected != null && !selected.matcher(line).find()) continue;
            if (pids != null) {
                Matcher match = THREADTIME.matcher(line);
                if (!match.matches() || !pids.contains(match.group(1))) continue;
            }
            String safe = scrub(line);
            if (safe.length() > 32768) { safe = safe.substring(0, 32768) + " [line truncated]"; omitted = true; }
            lines.addLast(safe); length += safe.length() + 1;
            while (length > 192 * 1024) { length -= lines.removeFirst().length() + 1; omitted = true; }
        }
        StringBuilder out = new StringBuilder();
        if (omitted) out.append("[earlier matching lines or overlong text omitted]\n");
        for (String line : lines) out.append(line).append('\n');
        return out.toString();
    }

    static String scrub(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.matches("(?i).*\\b(imei|meid|imsi|iccid|android_id|serialnumber|serial number|ro\\.serialno|ro\\.boot\\.serialno)\\b.*"))
                out.append("[identifier-bearing line omitted]");
            else out.append(line.replaceAll("(?i)\\bserial\\s*[:=]\\s*[^\\s,;]+", "serial=[omitted]")
                    .replaceAll("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b", "[address omitted]"));
            out.append('\n');
        }
        return out.toString().trim();
    }

    private static byte[] bytes(File file, int limit) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (output.size() < limit) {
                int n = input.read(buffer, 0, Math.min(buffer.length, limit - output.size()));
                if (n < 0) break;
                if (n == 0) continue;
                output.write(buffer, 0, n);
            }
            return output.toByteArray();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    private static JSONObject resultJson(SupportProcess.Result result) throws Exception {
        return new JSONObject().put("exitCode", result.exitCode).put("timedOut", result.timedOut)
                .put("outputTruncated", result.truncated).put("readError", result.readError);
    }
    private static void emit(String section, JSONObject data) {
        try { System.out.println(new JSONObject().put("section", section).put("data", data).toString()); }
        catch (Exception ignored) { System.out.println("{\"section\":\"serialization-error\"}"); }
    }
    private static void emitError(String section, Throwable error) {
        try { emit(section, new JSONObject().put("error", rootError(error))); }
        catch (Exception ignored) { emit(section, new JSONObject()); }
    }
    private static String rootError(Throwable error) {
        for (int i = 0; i < 5 && error.getCause() != null && error.getCause() != error; i++) error = error.getCause();
        return scrub(error.getClass().getSimpleName() + ": " + error.getMessage());
    }
    private static JSONArray rootStack(Throwable error) {
        JSONArray stack = new JSONArray();
        for (int cause = 0; cause < 4 && error != null; cause++) {
            stack.put(error.getClass().getName());
            StackTraceElement[] frames = error.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 16); i++) stack.put(scrub(frames[i].toString()));
            Throwable next = error.getCause();
            if (next == error) break;
            error = next;
        }
        return stack;
    }
}
