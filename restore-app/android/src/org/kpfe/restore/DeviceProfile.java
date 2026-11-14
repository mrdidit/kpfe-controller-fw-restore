package org.kpfe.restore;

/** Exact host identities recovered from the vendor updater; never a user override. */
enum DeviceProfile {
    ELITE, FIT, UNSUPPORTED;

    // This is a UI hint only. Both the service and FIT root helper must call identify.
    static DeviceProfile hint(String model, String device) {
        if ("Pocket FIT Elite".equals(model) && "PocketFITElite".equals(device)) return ELITE;
        if (("Pocket FIT".equals(model) || "KONKR Pocket FIT".equals(model))
                && !"PocketFITElite".equals(device)) return FIT;
        return UNSUPPORTED;
    }

    static DeviceProfile identify(String model, String device, String systemExtProduct, String productName) {
        DeviceProfile profile = hint(model, device);
        if (profile == ELITE)
            return isFitProduct(systemExtProduct) || isFitProduct(productName) ? UNSUPPORTED : ELITE;
        if (profile != FIT || conflictingProduct(systemExtProduct) || conflictingProduct(productName))
            return UNSUPPORTED;
        // The APK recognizes these exact values in ro.product.system_ext.name /
        // ro.product.product.name. Do not invent a Build.DEVICE value for FIT.
        return isFitProduct(systemExtProduct) || isFitProduct(productName) ? FIT : UNSUPPORTED;
    }

    private static boolean isFitProduct(String value) {
        return "AYANEO_Pocket_FIT".equals(value) || "KONKR_Pocket_FIT".equals(value);
    }

    private static boolean conflictingProduct(String value) {
        return value != null && !isFitProduct(value)
                && (value.startsWith("AYANEO_") || value.startsWith("KONKR_") || value.contains("Elite"));
    }
}
