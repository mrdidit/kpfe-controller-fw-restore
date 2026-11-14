package org.kpfe.restore;

import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Read-only identity capture, repeated in the service and the independent root helper. */
final class DeviceIdentity {
    private DeviceIdentity() { }

    static final class Snapshot {
        final String model, device, systemExtProduct, productName;
        final DeviceProfile profile;
        Snapshot(String model, String device, String systemExtProduct, String productName) {
            this.model = model; this.device = device;
            this.systemExtProduct = systemExtProduct; this.productName = productName;
            profile = DeviceProfile.identify(model, device, systemExtProduct, productName);
        }
        String description() { return model + " / " + device + " / " + systemExtProduct + " / " + productName; }
    }

    static DeviceProfile hint() { return DeviceProfile.hint(Build.MODEL, Build.DEVICE); }

    static Snapshot read() throws IOException, InterruptedException {
        return new Snapshot(Build.MODEL, Build.DEVICE,
                property("ro.product.system_ext.name"), property("ro.product.product.name"));
    }

    private static String property(String name) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) throw new IOException("Timed out reading device identity.");
            if (process.exitValue() != 0) throw new IOException("Cannot read device identity: " + name);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int count;
            while ((count = process.getInputStream().read(buffer)) != -1) {
                if (output.size() + count > 1024) throw new IOException("Unexpectedly long device identity.");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        } finally {
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
