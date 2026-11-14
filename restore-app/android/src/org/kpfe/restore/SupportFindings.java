package org.kpfe.restore;

/** Facts-only summary: absent/failed capture is never interpreted as a dead controller. */
final class SupportFindings {
    static final class Facts {
        String model = "unknown", vendorPids = "not captured", lastTrace = "not available", lastRestore = "not available";
        boolean standardFit, usbCaptured, rootConfirmed, rootComplete, rootTruncated;
        boolean rootUsbChecked, rootUsbReady, grantMethodResolved, vendorIdentityCaptured;
        int bootCandidates, appCandidates, vendorLogSections, usbSamples, usbOwnerSections;
    }

    private SupportFindings() { }

    static String summarize(Facts facts) {
        StringBuilder out = new StringBuilder();
        out.append("Controller support report\n\n")
            .append("Use case: controls stopped responding after an AYANEO updater attempt; a later attempt may hang.\n")
            .append("This capture did not retry that update, request update mode, open USB/UART transports, claim an interface or write firmware.\n\n")
            .append("Device: ").append(facts.model).append('\n');
        if (!facts.standardFit)
            out.append("This is not a standard FIT model. FIT USB expectations below are reference data, not a diagnosis for this device.\n");
        out.append("\nUSB observation (standard FIT updater IDs):\n");
        if (!facts.usbCaptured) out.append("USB enumeration unavailable; controller presence/mode cannot be inferred.\n");
        else {
            out.append("Bootloader-ID candidates 0483:5750: ").append(facts.bootCandidates)
                .append("; application-ID candidates 1c4f:0002: ").append(facts.appCandidates).append(".\n");
            if (facts.bootCandidates == 1 && facts.appCandidates == 0)
                out.append("A bootloader-ID candidate is enumerated. Its responsiveness and ability to accept firmware were NOT tested.\n");
            else if (facts.appCandidates == 1 && facts.bootCandidates == 0)
                out.append("An application-ID candidate is enumerated. Working buttons and software update entry were NOT tested.\n");
            else if (facts.appCandidates == 0 && facts.bootCandidates == 0)
                out.append("Neither expected USB ID was seen in this snapshot. This does not establish a dead controller or failed bootloader.\n");
            else out.append("Multiple/simultaneous candidates make the USB mode ambiguous.\n");
        }
        out.append("Passive USB samples: ").append(facts.usbSamples)
            .append("; observed matching USB file-descriptor owners: ").append(facts.usbOwnerSections)
            .append(". An open descriptor does not prove an interface claim or a stuck transfer.\n\n")
            .append("Root UID 0 confirmed: ").append(facts.rootConfirmed)
            .append("; root collector completed: ").append(facts.rootComplete)
            .append("; root output truncated: ").append(facts.rootTruncated).append(".\n");
        if (!facts.rootConfirmed || !facts.rootComplete || facts.rootTruncated)
            out.append("Root information is incomplete; missing observations must not be treated as absent hardware.\n");
        out.append("Root USB framework checked: ").append(facts.rootUsbChecked)
            .append("; initialization/enumeration completed: ").append(facts.rootUsbReady)
            .append("; permission-grant method resolved: ").append(facts.grantMethodResolved)
            .append(". No USB permission was granted or transfer attempted by this capture.\n\n")
            .append("AYANEO service-updater identity section: ").append(facts.vendorIdentityCaptured ? "captured" : "unavailable")
            .append("; current updater PIDs: ").append(facts.vendorPids)
            .append("; updater log sections: ").append(facts.vendorLogSections).append(".\n")
            .append("Latest session from OUR app: ").append(facts.lastTrace)
            .append(". This is separate from the AYANEO updater's attempt and may be older.\n\n")
            .append("Latest restore from OUR app: ").append(facts.lastRestore)
            .append(". A later device check does not replace this restore evidence.\n\n")
            .append("Inspect root-capture.jsonl for updater errors, thread waits, USB ownership, root API failures and samples. ")
            .append("Use latest-session.log only when its app/version/time matches the incident. ")
            .append("A passive capture cannot recreate discarded historical logs or prove exactly where an earlier update stopped.\n")
            .append("No automatic retry or recovery recommendation is inferred from these observations.\n");
        return out.toString();
    }
}
