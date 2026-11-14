package org.kpfe.restore;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kpfe.restore.core.FitFirmwareImage;
import org.kpfe.restore.core.FitUsbEngine;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Root-only, fixed FIT USB identities. Construction and enumeration do not open USB. */
final class FitUsbAccess implements FitUsbEngine.Transport {
    static final int BOOT_VID = 0x0483, BOOT_PID = 0x5750;
    static final int APP_VID = 0x1c4f, APP_PID = 0x0002;
    private final UsbManager manager;
    private final Method grantPermission;
    private final byte[] image;
    private UsbDeviceConnection connection;
    private UsbInterface claimedInterface;
    private UsbEndpoint in, out;
    private int nextIndex;
    private boolean awaitingResponse, quitClaimed, failed, openedOnce;
    boolean flashMayHaveStarted;
    boolean closedVerified = true;

    static final class Device {
        final UsbDevice usb;
        final String physicalPort;
        final String descriptor;
        Device(UsbDevice usb) {
            this.usb = usb;
            physicalPort = physicalPort(usb.getDeviceName());
            descriptor = fingerprint(usb);
        }
        JSONObject json() throws org.json.JSONException {
            JSONObject item = new JSONObject();
            item.put("name", usb.getDeviceName()); item.put("deviceId", usb.getDeviceId());
            item.put("vendorId", usb.getVendorId()); item.put("productId", usb.getProductId());
            item.put("physicalPort", physicalPort); item.put("descriptors", descriptor);
            return item;
        }
    }

    static final class Snapshot {
        final List<Device> boot = new ArrayList<>(), application = new ArrayList<>();
        Device requireBootloader() throws IOException {
            if (boot.size() != 1 || !application.isEmpty())
                throw new IOException("Require one FIT bootloader 0483:5750 and no simultaneous normal controller.");
            validateEndpoints(boot.get(0).usb);
            requirePhysicalPort(boot.get(0));
            return boot.get(0);
        }
        Device requireApplication() throws IOException {
            if (application.size() != 1 || !boot.isEmpty())
                throw new IOException("Require one normal FIT USB controller 1c4f:0002, or one recognized bootloader.");
            requirePhysicalPort(application.get(0));
            return application.get(0);
        }
        String mode() { return boot.size() == 1 && application.isEmpty() ? "bootloader"
                : (application.size() == 1 && boot.isEmpty() ? "application"
                : (application.isEmpty() && boot.isEmpty() ? "not-enumerated" : "unknown-or-ambiguous")); }
    }

    @SuppressWarnings("deprecation")
    FitUsbAccess(FitFirmwareImage selected) throws IOException {
        image = selected.copyBytes();
        try {
            // Keep this bootstrap aligned with SupportReportRoot.frameworkUsbProbe.
            // A thread-local Looper alone does not initialize Android's main Looper.
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Method systemMain = threadClass.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            Object thread = systemMain.invoke(null);
            Method getContext = threadClass.getDeclaredMethod("getSystemContext");
            getContext.setAccessible(true);
            Context context = (Context) getContext.invoke(thread);
            if (context == null) throw new IOException("Android system context is unavailable.");
            manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (manager == null) throw new IOException("Android USB service is unavailable.");
            // Resolve this before an entry command. Calling it is restore-only,
            // after a matching device exists; no permission bypass is attempted.
            grantPermission = manager.getClass().getMethod("grantPermission", UsbDevice.class, int.class);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IOException("Root Android USB initialization failed before entry: " + FitRootMain.explain(error), error);
        }
        FitRootMain.emit("usb-service-ready", "mainLooperPrepared", Looper.getMainLooper() != null,
                "systemContextCreated", true, "grantPermissionMethodResolved", true,
                "usbOpened", false, "permissionChanged", false);
    }

    Snapshot snapshot(boolean log) throws IOException {
        Snapshot result = new Snapshot();
        try {
            for (UsbDevice device : manager.getDeviceList().values()) {
                if (device.getVendorId() == BOOT_VID && device.getProductId() == BOOT_PID)
                    result.boot.add(new Device(device));
                else if (device.getVendorId() == APP_VID && device.getProductId() == APP_PID)
                    result.application.add(new Device(device));
            }
            if (result.boot.size() > 1 || result.application.size() > 1)
                throw new IOException("Multiple matching USB controllers found; disconnect external controllers before restoring.");
            if (log) {
                JSONArray devices = new JSONArray();
                for (Device device : result.boot) devices.put(device.json());
                for (Device device : result.application) devices.put(device.json());
                FitRootMain.emit("usb-devices", "mode", result.mode(), "devices", devices,
                        "identityAttested", false, "message", "USB IDs/descriptors identify a candidate; they do not attest MCU identity.");
            }
            return result;
        } catch (org.json.JSONException | RuntimeException error) {
            throw new IOException("Cannot enumerate FIT USB metadata: " + FitRootMain.explain(error), error);
        }
    }

    void open(Device expected) throws IOException, InterruptedException {
        if (openedOnce) throw new IOException("FIT USB session may only be opened once; no automatic reopen/retry.");
        FitRootMain.checkCancelled();
        Device current = snapshot(false).requireBootloader();
        requireSame(expected, current);
        validateEndpoints(current.usb);
        FitRootMain.requireNoUsbOwners(current.usb.getDeviceName());
        try {
            if (!manager.hasPermission(current.usb)) {
                FitRootMain.emit("usb-permission-attempt", "uid", 0, "device", current.usb.getDeviceName());
                grantPermission.invoke(manager, current.usb, 0);
            }
            if (!manager.hasPermission(current.usb)) throw new IOException("Root USB permission was not granted.");
            requireSame(current, snapshot(false).requireBootloader());
            FitRootMain.checkCancelled();
            openedOnce = true;
            connection = manager.openDevice(current.usb);
            if (connection == null) throw new IOException("Android could not open the FIT bootloader USB device.");
            closedVerified = false;
            UsbInterface iface = current.usb.getInterface(0);
            if (!connection.claimInterface(iface, true)) throw new IOException("Could not claim FIT USB interface 0.");
            claimedInterface = iface;
            in = iface.getEndpoint(0); out = iface.getEndpoint(1);
            requireSame(current, snapshot(false).requireBootloader());
            FitRootMain.emit("usb-open", "device", current.usb.getDeviceName(), "physicalPort", current.physicalPort,
                    "interfaceId", iface.getId(), "inAddress", in.getAddress(), "outAddress", out.getAddress(),
                    "inType", in.getType(), "outType", out.getType(), "claimed", true);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IOException("FIT USB open/permission failed: " + FitRootMain.explain(error), error);
        }
    }

    @Override public int write(byte[] packet, int timeoutMillis) throws IOException, InterruptedException {
        FitRootMain.checkCancelled();
        if (connection == null || claimedInterface == null || failed || awaitingResponse || timeoutMillis != 500)
            throw new IOException("FIT USB transport is not ready for a new packet.");
        int index = claimPacket(packet); // Consume before native I/O; failure cannot replay it.
        awaitingResponse = true;
        if (packet[3] == 2) flashMayHaveStarted = true;
        FitRootMain.emit("tx-attempt", "transport", "usb", "packetIndex", index,
                "kind", packet[3] == 2 ? "firmware-data" : "quit", "length", packet.length,
                "hex", FitRootMain.hex(packet, 0, packet.length), "timeoutMillis", timeoutMillis);
        try {
            int count = connection.bulkTransfer(out, packet, packet.length, timeoutMillis);
            FitRootMain.emit("tx-result", "transport", "usb", "packetIndex", index,
                    "requested", packet.length, "returned", count);
            if (count != packet.length) { failed = true; throw new IOException("Incomplete USB OUT: " + count + "/" + packet.length + "; no retry."); }
            return count;
        } catch (RuntimeException error) { failed = true; throw new IOException("FIT USB OUT failed.", error); }
    }

    @Override public int read(byte[] buffer, int timeoutMillis) throws IOException, InterruptedException {
        FitRootMain.checkCancelled();
        if (connection == null || failed || !awaitingResponse || buffer.length != 64 || timeoutMillis != 500)
            throw new IOException("FIT USB response read rejected outside the expected transaction.");
        try {
            int count = connection.bulkTransfer(in, buffer, buffer.length, timeoutMillis);
            FitRootMain.emit("rx", "transport", "usb", "packetIndex", quitClaimed ? 1374 : nextIndex - 1,
                    "returned", count, "hex", count > 0 && count <= buffer.length ? FitRootMain.hex(buffer, 0, count) : "",
                    "responseContentsVerified", false);
            if (count > buffer.length) { failed = true; throw new IOException("Impossible FIT USB IN count."); }
            if (count > 0) awaitingResponse = false;
            return count;
        } catch (RuntimeException error) { failed = true; throw new IOException("FIT USB IN failed.", error); }
    }

    private int claimPacket(byte[] packet) throws IOException {
        if (packet == null || packet.length != 64 || quitClaimed) throw new IOException("FIT USB command guard rejected packet.");
        if (packet[3] == 3) {
            if (nextIndex != 1374) throw new IOException("FIT USB guard rejected early Quit.");
            for (int i = 0; i < 64; i++) if (packet[i] != (i == 3 ? 3 : 0)) throw new IOException("FIT USB guard rejected modified Quit.");
            quitClaimed = true;
            return nextIndex;
        }
        if (packet[3] != 2 || (packet[2] & 255) != 58 || nextIndex >= 1374
                || ((packet[4] & 255) | ((packet[5] & 255) << 8)) != nextIndex)
            throw new IOException("FIT USB guard rejected type, size or sequence.");
        int sum = 0;
        for (int i = 0; i < 58; i++) {
            byte expected = image[nextIndex * 58 + i];
            if (packet[i + 6] != expected) throw new IOException("FIT USB guard rejected image payload.");
            sum += expected & 255;
        }
        if (((packet[0] & 255) | ((packet[1] & 255) << 8)) != (sum & 65535)) throw new IOException("FIT USB guard rejected checksum.");
        return nextIndex++;
    }

    void close() throws IOException {
        UsbDeviceConnection closing = connection;
        if (closing == null) return;
        String failure = "";
        try {
            if (claimedInterface != null) {
                boolean released = closing.releaseInterface(claimedInterface);
                FitRootMain.emit("usb-release", "released", released, "message", "A device reset/disconnect can make release return false.");
            }
        } catch (RuntimeException error) { failure = FitRootMain.explain(error); }
        finally {
            try { closing.close(); closedVerified = closing.getFileDescriptor() < 0; }
            catch (RuntimeException error) { closedVerified = false; failure = FitRootMain.explain(error); }
            connection = null; claimedInterface = null; in = null; out = null;
        }
        FitRootMain.emit("usb-close", "closedVerified", closedVerified, "message", failure);
        if (!closedVerified || !failure.isEmpty()) throw new IOException("FIT USB cleanup uncertain: " + failure);
    }

    static void requireSamePhysicalPort(Device before, Device after) throws IOException {
        requirePhysicalPort(before);
        requirePhysicalPort(after);
        if (!before.physicalPort.equals(after.physicalPort))
            throw new IOException("Controller USB physical port changed or became unverifiable during transition.");
        FitRootMain.emit("usb-topology", "before", before.physicalPort, "after", after.physicalPort,
                "matched", true, "identityAttested", false);
    }

    private static void requirePhysicalPort(Device device) throws IOException {
        if (device.physicalPort.isEmpty()) throw new IOException("Cannot establish the controller's USB physical port from sysfs; transfer refused.");
    }

    private static void requireSame(Device expected, Device current) throws IOException {
        if (!expected.usb.getDeviceName().equals(current.usb.getDeviceName())
                || expected.usb.getDeviceId() != current.usb.getDeviceId()
                || !expected.descriptor.equals(current.descriptor)) throw new IOException("USB device changed before claim.");
        requireSamePhysicalPort(expected, current);
    }

    private static void validateEndpoints(UsbDevice device) throws IOException {
        if (device.getVendorId() != BOOT_VID || device.getProductId() != BOOT_PID || device.getInterfaceCount() < 1)
            throw new IOException("Unexpected FIT USB bootloader identity.");
        UsbInterface iface = device.getInterface(0);
        if (iface.getId() != 0 || iface.getEndpointCount() != 2 || iface.getAlternateSetting() != 0)
            throw new IOException("Unexpected FIT USB interface/alternate setting/endpoints.");
        for (int i = 0; i < 2; i++) {
            UsbEndpoint endpoint = iface.getEndpoint(i);
            int direction = i == 0 ? UsbConstants.USB_DIR_IN : UsbConstants.USB_DIR_OUT;
            int type = endpoint.getType();
            // USBDEVFS_BULK supports single-shot interrupt endpoints too; the
            // vendor invokes bulkTransfer for these indexed DATA endpoints.
            if (endpoint.getDirection() != direction || endpoint.getEndpointNumber() == 0
                    || (type != UsbConstants.USB_ENDPOINT_XFER_BULK && type != UsbConstants.USB_ENDPOINT_XFER_INT)
                    || endpoint.getMaxPacketSize() < 64)
                throw new IOException("Unexpected FIT USB endpoint direction/type/packet size.");
        }
    }

    private static String fingerprint(UsbDevice device) {
        StringBuilder text = new StringBuilder();
        text.append(device.getVendorId()).append(':').append(device.getProductId());
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            text.append('/').append(iface.getId()).append(',').append(iface.getAlternateSetting()).append(',').append(iface.getInterfaceClass());
            for (int j = 0; j < iface.getEndpointCount(); j++) {
                UsbEndpoint ep = iface.getEndpoint(j);
                text.append(';').append(ep.getAddress()).append(',').append(ep.getType()).append(',').append(ep.getMaxPacketSize());
            }
        }
        return text.toString();
    }

    private static String physicalPort(String node) {
        if (!node.matches("/dev/bus/usb/[0-9]+/[0-9]+")) return "";
        String[] pieces = node.split("/");
        File[] candidates = new File("/sys/bus/usb/devices").listFiles();
        if (candidates == null) return "";
        try {
            int bus = Integer.parseInt(pieces[4]), dev = Integer.parseInt(pieces[5]);
            String found = "";
            for (File candidate : candidates) {
                File busFile = new File(candidate, "busnum"), devFile = new File(candidate, "devnum");
                if (!busFile.isFile() || !devFile.isFile()) continue;
                try {
                    int actualBus = Integer.parseInt(new String(FitRootMain.readBounded(busFile, 32), java.nio.charset.StandardCharsets.US_ASCII).trim());
                    int actualDev = Integer.parseInt(new String(FitRootMain.readBounded(devFile, 32), java.nio.charset.StandardCharsets.US_ASCII).trim());
                    if (actualBus == bus && actualDev == dev) {
                        if (!found.isEmpty()) return "";
                        found = candidate.getCanonicalPath();
                    }
                } catch (IOException | NumberFormatException ignored) { }
            }
            return found;
        } catch (NumberFormatException error) { return ""; }
    }
}
