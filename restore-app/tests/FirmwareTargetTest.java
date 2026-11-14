package org.kpfe.restore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import org.kpfe.restore.core.FirmwareImage;
import org.kpfe.restore.core.FitFirmwareImage;

/** Tests only the pre-transfer catalog/read layer; no transport exists in these tests. */
public final class FirmwareTargetTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected FIT, Elite v8 and Elite v9 fixture paths");
        byte[][] images = {
            Files.readAllBytes(Paths.get(args[0])),
            Files.readAllBytes(Paths.get(args[1])),
            Files.readAllBytes(Paths.get(args[2]))
        };
        FirmwareTarget[] targets = {
            FirmwareTarget.forSelection(DeviceProfile.FIT, FirmwareTarget.FIT_SUPPLIED),
            FirmwareTarget.forSelection(DeviceProfile.ELITE, 8),
            FirmwareTarget.forSelection(DeviceProfile.ELITE, 9)
        };
        int[] validSelections = {100, 8, 9};
        DeviceProfile[] profiles = {DeviceProfile.FIT, DeviceProfile.ELITE, DeviceProfile.ELITE};
        for (int i = 0; i < targets.length; i++) {
            FirmwareTarget target = targets[i];
            check(target.profile == profiles[i] && target.selection == validSelections[i], "selection remains bound to profile");
            check(target.size == images[i].length, "catalog exact size");
            check(target.sha256.equals(FirmwareImage.sha256(images[i])), "catalog exact SHA-256");
            check(!target.assetName.isEmpty() && !target.id.isEmpty() && !target.label.isEmpty(), "catalog has unambiguous metadata");
            byte[] copied = target.read(new ByteArrayInputStream(images[i]));
            check(Arrays.equals(copied, images[i]) && copied != images[i], "matching input returns isolated validated bytes");
            copied[0] ^= 1;
            check(Arrays.equals(target.read(new ByteArrayInputStream(images[i])), images[i]), "caller mutation does not change catalog or later reads");
            for (int j = 0; j < targets.length; j++) {
                if (i == j) continue;
                byte[] wrong = images[j];
                rejects(() -> target.read(new ByteArrayInputStream(wrong)));
            }
            byte[] changed = images[i].clone(); changed[changed.length / 2] ^= 1;
            rejects(() -> target.read(new ByteArrayInputStream(changed)));
            byte[] shortImage = Arrays.copyOf(images[i], images[i].length - 1);
            rejects(() -> target.read(new ByteArrayInputStream(shortImage)));
            byte[] longImage = Arrays.copyOf(images[i], images[i].length + 1);
            rejects(() -> target.read(new ByteArrayInputStream(longImage)));
            rejects(() -> target.read(null));
        }
        check(FirmwareTarget.FIT_SUPPLIED == 100, "FIT selection is internal ID100");
        check("unconfirmed".equals(targets[0].version), "FIT firmware version is not fabricated from selection ID or folder");
        check(!"100".equals(targets[0].version) && !"13".equals(targets[0].version) && !"2.2".equals(targets[0].version), "unproven target versions are absent");
        check(FitFirmwareImage.ASSET_NAME.equals(targets[0].assetName), "FIT target uses FIT asset");
        check("8".equals(targets[1].version) && "9".equals(targets[2].version), "Elite versions remain explicit");
        check(!targets[0].id.equals(targets[1].id) && !targets[0].id.equals(targets[2].id)
                && !targets[1].id.equals(targets[2].id), "target IDs remain distinct");

        int[] selections = {Integer.MIN_VALUE, -1, 0, 1, 2, 8, 9, 10, 13, 22, 99, 100, 101, Integer.MAX_VALUE};
        for (DeviceProfile profile : new DeviceProfile[] {null, DeviceProfile.UNSUPPORTED, DeviceProfile.ELITE, DeviceProfile.FIT}) {
            for (int selection : selections) {
                boolean allowed = profile == DeviceProfile.ELITE && (selection == 8 || selection == 9)
                        || profile == DeviceProfile.FIT && selection == 100;
                if (allowed) check(FirmwareTarget.forSelection(profile, selection).profile == profile, "valid selection resolves only its own profile");
                else rejects(() -> FirmwareTarget.forSelection(profile, selection));
            }
        }
        System.out.println("FirmwareTargetTest: " + checks + " checks passed");
    }

    private interface Throwing { void run() throws Exception; }
    private static void rejects(Throwing action) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | IOException expected) { rejected = true; }
        check(rejected, "invalid or swapped selection/image must fail before transport creation");
    }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
