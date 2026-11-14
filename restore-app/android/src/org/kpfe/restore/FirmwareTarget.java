package org.kpfe.restore;

import java.io.IOException;
import java.io.InputStream;
import org.kpfe.restore.core.FirmwareImage;
import org.kpfe.restore.core.FitFirmwareImage;

/** Profile-bound catalog. FIT never enters the Elite image validator or transfer engine. */
final class FirmwareTarget {
    static final int FIT_SUPPLIED = 100; // Internal selection ID, NOT a firmware version.
    final DeviceProfile profile;
    final int selection, size;
    final String id, version, assetName, sha256, label;
    private final FirmwareImage.Descriptor elite;

    private FirmwareTarget(FirmwareImage.Descriptor elite, int selection) {
        this.profile = DeviceProfile.ELITE; this.elite = elite; this.selection = selection;
        this.size = elite.size; this.version = elite.version; this.id = "elite-v" + version;
        this.assetName = elite.assetName; this.sha256 = elite.sha256; this.label = "stock v" + version;
    }

    private FirmwareTarget() {
        this.profile = DeviceProfile.FIT; this.elite = null; this.selection = FIT_SUPPLIED;
        this.size = FitFirmwareImage.EXPECTED_SIZE; this.version = "unconfirmed"; this.id = "fit-supplied";
        this.assetName = FitFirmwareImage.ASSET_NAME; this.sha256 = FitFirmwareImage.EXPECTED_SHA256;
        this.label = "supplied FIT firmware";
    }

    static FirmwareTarget forSelection(DeviceProfile profile, int selection) {
        if (profile == DeviceProfile.ELITE && (selection == 8 || selection == 9))
            return new FirmwareTarget(FirmwareImage.forVersion(Integer.toString(selection)), selection);
        if (profile == DeviceProfile.FIT && selection == FIT_SUPPLIED) return new FirmwareTarget();
        throw new IllegalArgumentException("Firmware selection does not belong to this device.");
    }

    byte[] read(InputStream input) throws IOException {
        return profile == DeviceProfile.FIT ? FitFirmwareImage.read(input).copyBytes()
                : FirmwareImage.read(elite, input).copyBytes();
    }
}
