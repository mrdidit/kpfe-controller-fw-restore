package org.kpfe.restore;

/** Host-only identity tests. Model hints alone never authorize FIT I/O. */
public final class DeviceProfileTest {
    private static int checks;

    public static void main(String[] args) {
        expect(DeviceProfile.ELITE, "Pocket FIT Elite", "PocketFITElite", null, null);
        expect(DeviceProfile.ELITE, "Pocket FIT Elite", "PocketFITElite", "", "");
        expect(DeviceProfile.ELITE, "Pocket FIT Elite", "PocketFITElite", "generic", "generic");
        // The S2 Pro report contains the same application USB ID as FIT; identity
        // must still reject it, including when a partition contains a FIT label.
        expect(DeviceProfile.UNSUPPORTED, "Pocket S2 Pro", "PocketS2Pro", "AYANEO_PocketS2_Pro", "AYANEO_PocketS2_Pro");
        expect(DeviceProfile.UNSUPPORTED, "Pocket S2 Pro", "PocketS2Pro", "AYANEO_Pocket_FIT", "AYANEO_Pocket_FIT");
        expect(DeviceProfile.UNSUPPORTED, "Pocket S2", "PocketS2", "AYANEO_PocketS2", "AYANEO_PocketS2");
        String[] models = {"Pocket FIT", "KONKR Pocket FIT"};
        String[] fitProducts = {"AYANEO_Pocket_FIT", "KONKR_Pocket_FIT"};
        String[] devices = {"PocketFIT", "vendor-board", "", null};
        for (String model : models) for (String product : fitProducts) for (String device : devices) {
            expect(DeviceProfile.FIT, model, device, product, null);
            expect(DeviceProfile.FIT, model, device, null, product);
            expect(DeviceProfile.FIT, model, device, product, "generic");
            expect(DeviceProfile.FIT, model, device, "generic", product);
            expect(DeviceProfile.UNSUPPORTED, model, "PocketFITElite", product, product);
        }
        for (String model : models) for (String first : fitProducts) for (String second : fitProducts)
            expect(DeviceProfile.FIT, model, "PocketFIT", first, second);

        String[] missingProducts = {null, "", "generic", "unknown", "AYANEO_Pocket_Fit", "ayaneo_pocket_fit", "AYANEO_Pocket_FIT ", "KONKR Pocket FIT"};
        for (String model : models) for (String first : missingProducts) for (String second : missingProducts)
            expect(DeviceProfile.UNSUPPORTED, model, "PocketFIT", first, second);

        String[] conflicting = {"AYANEO_Pocket_FIT_Elite", "KONKR_Pocket_FIT_Elite", "PocketFITElite", "AYANEO_Pocket_AIR2", "AYANEO_Pocket_MICRO", "KONKR_other"};
        for (String model : models) for (String fit : fitProducts) for (String conflict : conflicting) {
            expect(DeviceProfile.UNSUPPORTED, model, "PocketFIT", fit, conflict);
            expect(DeviceProfile.UNSUPPORTED, model, "PocketFIT", conflict, fit);
        }

        for (String fit : fitProducts) {
            expect(DeviceProfile.UNSUPPORTED, "Pocket FIT Elite", "PocketFITElite", fit, null);
            expect(DeviceProfile.UNSUPPORTED, "Pocket FIT Elite", "PocketFITElite", null, fit);
            expect(DeviceProfile.UNSUPPORTED, "Pocket FIT Elite", "PocketFIT", fit, fit);
            expect(DeviceProfile.UNSUPPORTED, "Pocket FIT Elite", "vendor-board", fit, fit);
        }
        String[] rejectedModels = {null, "", "unknown", "Pocket FIT Pro", "AYANEO Pocket FIT", "Pocket FIT ", "pocket fit", "Pocket AIR 2", "Pocket AIR2", "Pocket MICRO", "Pocket MICRO II", "Pocket Ace", "Pocket ACE"};
        for (String model : rejectedModels) for (String device : new String[] {"PocketFIT", "PocketFITElite", "vendor-board", null})
            expect(DeviceProfile.UNSUPPORTED, model, device, "AYANEO_Pocket_FIT", "KONKR_Pocket_FIT");
        expect(DeviceProfile.UNSUPPORTED, "Pocket FIT Elite", "PocketFITElite ", null, null);
        expect(DeviceProfile.UNSUPPORTED, "Pocket FIT Elite", "pocketfitelite", null, null);
        expect(DeviceProfile.UNSUPPORTED, "Pocket FIT Elite", null, null, null);
        check(DeviceProfile.hint("Pocket FIT", "vendor-board") == DeviceProfile.FIT, "FIT model can supply a UI hint");
        expect(DeviceProfile.UNSUPPORTED, "Pocket FIT", "vendor-board", null, null);
        check(DeviceProfile.hint("Pocket FIT", "PocketFITElite") == DeviceProfile.UNSUPPORTED, "mixed device must not even supply FIT hint");
        System.out.println("DeviceProfileTest: " + checks + " checks passed");
    }

    private static void expect(DeviceProfile expected, String model, String device, String first, String second) {
        DeviceProfile actual = DeviceProfile.identify(model, device, first, second);
        check(actual == expected, "identity " + model + " / " + device + " / " + first + " / " + second
                + " expected " + expected + " got " + actual);
    }

    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
