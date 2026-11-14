package org.kpfe.restore;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** One passive, private ZIP. Invoke off the UI thread while the app reserves collection state. */
public final class SupportReport {
    private static final AtomicBoolean COLLECTING = new AtomicBoolean();
    private SupportReport() { }

    public static File collect(Context context, File latestTrace) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("Collect diagnostics on a background thread");
        synchronized (AppState.class) {
            if (AppState.busy) throw new IOException("Wait for the controller operation to finish before collecting diagnostics");
        }
        if (!COLLECTING.compareAndSet(false, true)) throw new IOException("A support report is already being collected");
        Context app = context.getApplicationContext();
        long started = SystemClock.elapsedRealtime();
        long deadline = started + 55000;
        File temporary = null;
        SupportFindings.Facts facts = new SupportFindings.Facts();
        facts.model = Build.MODEL;
        facts.standardFit = DeviceIdentity.hint() == DeviceProfile.FIT;
        try {
            File directory = new File(app.getFilesDir(), "support-reports");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private support-report directory");
            temporary = File.createTempFile("kpfe-support-", ".tmp", directory);
            JSONArray outcomes = new JSONArray();
            try (FileOutputStream file = new FileOutputStream(temporary); SupportArchive zip = new SupportArchive(file)) {
                add(zip, "README.txt", "Passive support capture. No USB/tty transport was opened, no controller commands were sent, and no settings were changed.\n"
                        + "Missing data, root denial and time/output limits are recorded explicitly. USB metadata is a snapshot, not a controller identification guarantee.\n"
                        + "Android IMEI, device serial, USB serial strings and unrestricted package inventories are not requested.\n"
                        + "The latest restore trace is retained even after a later check (up to16MiB); other capture output is bounded.\n", 8192);
                capture(zip, outcomes, "app.json", () -> appInfo(app, deadline).toString(2));
                capture(zip, outcomes, "device.json", () -> identity(app).toString(2));
                capture(zip, outcomes, "usb-framework.json", () -> {
                    JSONObject snapshot = usb(app);
                    facts.usbCaptured = true;
                    JSONArray devices = snapshot.getJSONArray("devices");
                    for (int i = 0; i < devices.length(); i++) {
                        JSONObject device = devices.getJSONObject(i);
                        if (device.optInt("vendorId") == 0x0483 && device.optInt("productId") == 0x5750) facts.bootCandidates++;
                        if (device.optInt("vendorId") == 0x1c4f && device.optInt("productId") == 0x0002) facts.appCandidates++;
                    }
                    return snapshot.toString(2);
                });
                capture(zip, outcomes, "app-state.json", () -> preferences(app).toString(2));
                String log;
                synchronized (AppState.class) { log = AppState.log.toString(); }
                outcomes.put(new JSONObject().put("section", "recent-session.log").put("captured", true)
                        .put("truncated", add(zip, "recent-session.log", log, 128 * 1024)));
                captureTraces(zip, outcomes, app, latestTrace);
                for (int i = 0; i < outcomes.length(); i++) {
                    JSONObject entry = outcomes.getJSONObject(i);
                    if ("latest-session.log".equals(entry.optString("section")) && entry.optBoolean("captured"))
                        facts.lastTrace = entry.optString("sourceName") + " (modified epoch ms " + entry.optLong("sourceModifiedAtMillis") + ")";
                    if (entry.optBoolean("captured") && ("latest-restore.log".equals(entry.optString("section"))
                            || ("latest-session.log".equals(entry.optString("section")) && entry.optString("sourceName").startsWith("session-restore-"))))
                        facts.lastRestore = entry.optString("section") + ": " + entry.optString("sourceName");
                }
                try {
                    long remaining = Math.max(1, Math.min(42000, deadline - SystemClock.elapsedRealtime() - 2500));
                    SupportProcess.Result result = root(app, remaining);
                    add(zip, "root-capture.jsonl", result.output, 2 * 1024 * 1024);
                    observeRoot(facts, result);
                    outcomes.put(new JSONObject().put("section", "root-capture").put("exitCode", result.exitCode)
                            .put("timedOut", result.timedOut).put("outputTruncated", result.truncated).put("readError", result.readError)
                            .put("note", "Root availability is recorded by the root section's UID; a shell exit alone does not establish it."));
                } catch (Exception e) { outcomes.put(failure("root-capture", e)); }
                add(zip, "findings.txt", SupportFindings.summarize(facts), 32768);
                add(zip, "capture-summary.json", new JSONObject().put("schemaVersion", 1).put("startedAtEpochMillis", System.currentTimeMillis() - (SystemClock.elapsedRealtime() - started))
                        .put("elapsedMillis", SystemClock.elapsedRealtime() - started).put("deadlineReached", SystemClock.elapsedRealtime() >= deadline)
                        .put("readOnly", true).put("sections", outcomes).toString(2), 65536);
                zip.finish();
                file.getFD().sync();
            }
            File result = new File(directory, temporary.getName().replace(".tmp", ".zip"));
            if (!temporary.renameTo(result)) throw new IOException("Could not finalize support ZIP");
            temporary = null;
            return result;
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException)e;
            throw new IOException("Support report could not be finalized: " + e.getClass().getSimpleName(), e);
        } finally {
            if (temporary != null) temporary.delete();
            COLLECTING.set(false);
        }
    }

    private interface Section { String get() throws Exception; }
    private static void capture(SupportArchive zip, JSONArray outcomes, String name, Section action) throws Exception {
        try {
            boolean truncated = add(zip, name, action.get(), 256 * 1024);
            outcomes.put(new JSONObject().put("section", name).put("captured", true).put("truncated", truncated));
        } catch (Exception e) { outcomes.put(failure(name, e)); }
    }
    private static boolean add(SupportArchive zip, String name, String value, int limit) throws IOException {
        return zip.add(name, new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)), limit);
    }
    private static JSONObject failure(String section, Exception error) throws Exception {
        return new JSONObject().put("section", section).put("captured", false)
                .put("error", SupportReportRoot.scrub(error.getClass().getSimpleName() + ": " + error.getMessage()));
    }

    private static JSONObject appInfo(Context app, long deadline) throws Exception {
        PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        JSONObject data = new JSONObject().put("package", app.getPackageName()).put("versionName", info.versionName)
                .put("versionCode", info.getLongVersionCode()).put("pid", Process.myPid());
        try (InputStream input = new FileInputStream(app.getApplicationInfo().sourceDir)) {
            data.put("installedBaseApk", hash(input, 32 * 1024 * 1024, deadline));
        }
        JSONArray assets = new JSONArray();
        FirmwareTarget[] catalog = {
            FirmwareTarget.forSelection(DeviceProfile.ELITE, 8),
            FirmwareTarget.forSelection(DeviceProfile.ELITE, 9),
            FirmwareTarget.forSelection(DeviceProfile.FIT, FirmwareTarget.FIT_SUPPLIED)
        };
        String[] names = app.getAssets().list("");
        if (names != null) for (String name : names) {
            if (!name.matches("[A-Za-z0-9_.-]+\\.bin") || assets.length() >= 16) continue;
            try (InputStream input = app.getAssets().open(name)) {
                JSONObject actual = hash(input, 4 * 1024 * 1024, deadline);
                JSONObject asset = new JSONObject().put("asset", name).put("identity", actual);
                for (FirmwareTarget target : catalog) if (target.assetName.equals(name))
                    asset.put("catalogId", target.id).put("profile", target.profile.name()).put("expectedBytes", target.size)
                            .put("expectedSha256", target.sha256).put("matchesPin", target.size == actual.getInt("bytes")
                                    && target.sha256.equals(actual.getString("sha256")));
                assets.put(asset);
            } catch (Exception e) { assets.put(failure(name, e)); }
        }
        data.put("bundledFirmware", assets);
        return data;
    }

    private static JSONObject hash(InputStream input, int limit, long deadline) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int size = 0, n;
        while ((n = input.read(buffer)) >= 0) {
            if (SystemClock.elapsedRealtime() >= deadline || n > limit - size) throw new IOException("Identity read limit reached");
            digest.update(buffer, 0, n); size += n;
        }
        StringBuilder text = new StringBuilder(64);
        for (byte value : digest.digest()) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return new JSONObject().put("bytes", size).put("sha256", text.toString());
    }

    private static JSONObject identity(Context app) throws Exception {
        JSONObject data = new JSONObject().put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND)
                .put("model", Build.MODEL).put("device", Build.DEVICE).put("product", Build.PRODUCT)
                .put("hardware", Build.HARDWARE).put("board", Build.BOARD).put("fingerprint", Build.FINGERPRINT)
                .put("api", Build.VERSION.SDK_INT).put("androidRelease", Build.VERSION.RELEASE)
                .put("supportedAbis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)));
        Intent battery = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) data.put("battery", new JSONObject().put("level", battery.getIntExtra("level", -1))
                .put("scale", battery.getIntExtra("scale", -1)).put("plugged", battery.getIntExtra("plugged", 0))
                .put("status", battery.getIntExtra("status", -1)));
        else data.put("batteryError", "Battery state unavailable");
        return data;
    }

    private static JSONObject preferences(Context app) throws Exception {
        SharedPreferences saved = app.getSharedPreferences("restore", Context.MODE_PRIVATE);
        Map<String, ?> values = saved.getAll();
        JSONObject data = new JSONObject();
        for (String key : new String[] {"uiVersionCode", "busy", "holdScreenForRecovery", "selectedFirmwareVersion", "selectedFirmwareId",
                "selectedDeviceProfile", "status", "title", "message", "lastTraceComplete", "lastTraceFailure"}) {
            Object value = values.get(key);
            if (value instanceof Boolean || value instanceof Integer || value instanceof Long) data.put(key, value);
            else if (value instanceof String) {
                String text = (String)value;
                data.put(key, SupportReportRoot.scrub(text.substring(0, Math.min(4096, text.length()))));
            }
        }
        synchronized (AppState.class) {
            data.put("currentFirmwareVersion", AppState.currentFirmwareVersion).put("currentFirmwareVersionSource", AppState.currentFirmwareVersionSource)
                    .put("currentFirmwareVersionObservedAtMillis", AppState.currentFirmwareVersionObservedAtMillis)
                    .put("mode", AppState.mode).put("ready", AppState.ready).put("checkedFirmwareVersion", AppState.checkedFirmwareVersion)
                    .put("currentSelectedFirmware", AppState.selectedFirmwareVersion).put("collectingReport", AppState.collectingReport);
        }
        return data;
    }

    private static JSONObject usb(Context app) throws Exception {
        UsbManager manager = (UsbManager)app.getSystemService(Context.USB_SERVICE);
        if (manager == null) throw new IOException("USB service unavailable");
        JSONArray nodes = new JSONArray();
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (nodes.length() >= 64) break;
            JSONObject node = new JSONObject().put("name", device.getDeviceName()).put("vendorId", device.getVendorId())
                    .put("productId", device.getProductId()).put("class", device.getDeviceClass())
                    .put("subclass", device.getDeviceSubclass()).put("protocol", device.getDeviceProtocol())
                    .put("permissionAlreadyGranted", manager.hasPermission(device));
            JSONArray interfaces = new JSONArray();
            for (int i = 0; i < Math.min(32, device.getInterfaceCount()); i++) {
                UsbInterface iface = device.getInterface(i);
                JSONObject row = new JSONObject().put("id", iface.getId()).put("alternateSetting", iface.getAlternateSetting())
                        .put("class", iface.getInterfaceClass()).put("subclass", iface.getInterfaceSubclass()).put("protocol", iface.getInterfaceProtocol());
                JSONArray endpoints = new JSONArray();
                for (int j = 0; j < Math.min(32, iface.getEndpointCount()); j++) {
                    UsbEndpoint ep = iface.getEndpoint(j);
                    endpoints.put(new JSONObject().put("address", ep.getAddress()).put("attributes", ep.getAttributes())
                            .put("direction", ep.getDirection()).put("type", ep.getType()).put("maxPacketSize", ep.getMaxPacketSize()).put("interval", ep.getInterval()));
                }
                interfaces.put(row.put("endpoints", endpoints));
            }
            nodes.put(node.put("interfaces", interfaces));
        }
        return new JSONObject().put("deviceCount", nodes.length()).put("devices", nodes).put("transportOpened", false)
                .put("note", "No USB permission requested, connection opened, interface claimed, control transfer or endpoint transfer.");
    }

    private static void captureTraces(SupportArchive zip, JSONArray outcomes, Context app, File supplied) throws Exception {
        if (supplied == null) {
            String path = app.getSharedPreferences("restore", 0).getString("lastTracePath", "");
            if (path != null && !path.isEmpty()) supplied = new File(path);
        }
        File directory = new File(app.getFilesDir(), "session-traces").getCanonicalFile();
        File restore = null;
        File[] candidates = directory.listFiles();
        if (candidates != null) for (File candidate : candidates) {
            if (!candidate.getName().startsWith("session-restore-") || !candidate.getName().endsWith(".log")) continue;
            try {
                File canonical = candidate.getCanonicalFile();
                if (!directory.equals(canonical.getParentFile()) || !canonical.isFile()) continue;
                if (restore == null || canonical.lastModified() > restore.lastModified()) restore = canonical;
            } catch (IOException ignored) { }
        }
        boolean same = supplied != null && restore != null && supplied.getCanonicalFile().equals(restore);
        // Reserve space for both root evidence and the archive summary. A later
        // check must not hide the preceding transfer, nor exhaust the total limit.
        captureTrace(zip, outcomes, directory, supplied, "latest-session.log",
                restore != null && !same ? 256 * 1024 : SupportArchive.MAX_ENTRY);
        if (restore != null && !same) captureTrace(zip, outcomes, directory, restore, "latest-restore.log", SupportArchive.MAX_ENTRY);
    }

    private static void captureTrace(SupportArchive zip, JSONArray outcomes, File directory, File supplied,
                                     String entryName, int limit) throws Exception {
        try {
            if (supplied == null) throw new IOException("No saved session trace");
            File trace = supplied.getCanonicalFile();
            if (!directory.equals(trace.getParentFile())
                    || !OsConstants.S_ISREG(Os.stat(trace.getPath()).st_mode)) throw new IOException("Trace is not a private regular session file");
            long before = trace.length(), modified = trace.lastModified();
            boolean truncated;
            try (InputStream input = new FileInputStream(trace)) { truncated = zip.add(entryName, input, limit); }
            outcomes.put(new JSONObject().put("section", entryName).put("captured", true).put("sourceName", trace.getName())
                    .put("sourceBytes", before).put("sourceModifiedAtMillis", modified).put("truncated", truncated)
                    .put("changedDuringCopy", before != trace.length() || modified != trace.lastModified()));
        } catch (Exception e) { outcomes.put(failure(entryName, e)); }
    }

    private static SupportProcess.Result root(Context app, long timeout) throws IOException, InterruptedException {
        String command = "exec /system/bin/app_process -cp " + quote(app.getApplicationInfo().sourceDir)
                + " / org.kpfe.restore.SupportReportRoot " + Process.myPid();
        String[] candidates = {"/debug_ramdisk/su", "/sbin/su", "/system/bin/su", "/system/xbin/su", "su"};
        for (String su : candidates) {
            if (su.startsWith("/") && !new File(su).canExecute()) continue;
            // A denied or timed-out prompt is recorded once; do not repeatedly prompt through other su paths.
            return SupportProcess.run(timeout, 2 * 1024 * 1024, su, "-c", command);
        }
        throw new IOException("No usable root executable was found");
    }

    private static void observeRoot(SupportFindings.Facts facts, SupportProcess.Result result) {
        facts.rootTruncated = result.truncated;
        for (String line : result.output.split("\n")) {
            try {
                JSONObject event = new JSONObject(line);
                JSONObject data = event.optJSONObject("data");
                if (data == null) continue;
                String section = event.optString("section");
                if (section.equals("root")) facts.rootConfirmed = data.optInt("uid", -1) == 0;
                else if (section.equals("complete")) facts.rootComplete = !data.optBoolean("deadlineReached", true);
                else if (section.equals("usb-framework-root")) {
                    facts.rootUsbChecked = true;
                    facts.rootUsbReady = data.optBoolean("completed", false);
                    facts.grantMethodResolved = data.optBoolean("grantPermissionMethodResolved", false);
                } else if (section.equals("vendor-updater-identity")) facts.vendorIdentityCaptured =
                        data.optBoolean("installedApkFound", false) || data.optJSONObject("packageManager") != null;
                else if (section.equals("vendor-updater-processes")) {
                    JSONArray pids = data.optJSONArray("activePids");
                    facts.vendorPids = pids == null ? "unavailable" : pids.toString();
                    if (!data.optBoolean("scanComplete", false)) facts.vendorPids += " (scan incomplete)";
                } else if (section.equals("vendor-updater-logcat") && data.optBoolean("available", false)) facts.vendorLogSections++;
                else if (section.equals("usb-snapshot") && data.optBoolean("available", false)) facts.usbSamples++;
                else if (section.equals("usb-owner")) facts.usbOwnerSections++;
            } catch (Exception ignored) { /* Root manager/ART messages and incomplete lines remain in the raw capture. */ }
        }
        if (result.timedOut || result.exitCode != 0 || !result.readError.isEmpty()) facts.rootComplete = false;
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
