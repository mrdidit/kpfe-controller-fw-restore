package org.kpfe.restore;

/** Fail-closed policy for the FIT entry-only probe; it never treats a probe as a restore. */
final class FitProbePolicy {
    static final String ENTRY_CONFIRMED = "FIT_ENTRY_CONFIRMED";
    static final String BOOTLOADER_PRESENT = "FIT_BOOTLOADER_PRESENT";

    private FitProbePolicy() { }

    /** loaderObserved includes any loader candidate/uncertain USB state at the root boundary. */
    static boolean mayResume(boolean entryAttempted, boolean loaderObserved, boolean normalUsbObserved,
                             boolean uartClosedVerified, boolean subprocessesStopped) {
        return !entryAttempted && !loaderObserved && normalUsbObserved && uartClosedVerified && subprocessesStopped;
    }

    static boolean recoveryHoldRequired(boolean entryAttempted, boolean loaderObserved, boolean paused,
                                        boolean resumed, boolean uartClosedVerified, boolean subprocessesStopped) {
        return entryAttempted || loaderObserved || (paused && !resumed) || (resumed && !paused)
                || !uartClosedVerified || !subprocessesStopped;
    }

    static final class Result {
        final String status, cleanupError;
        final boolean fieldsComplete, softwareEntryAttempted, softwareEntryConfirmed, bootloaderObserved;
        final boolean controlStateChanged, recoveryHoldRequested, firmwareDataSent, usbOpened, quitSent;
        final boolean flashMayHaveStarted, uartClosedVerified, subprocessesStopped;

        Result(String status, boolean fieldsComplete, boolean softwareEntryAttempted, boolean softwareEntryConfirmed,
               boolean bootloaderObserved, boolean controlStateChanged, boolean recoveryHoldRequested,
               boolean firmwareDataSent, boolean usbOpened, boolean quitSent, boolean flashMayHaveStarted,
               boolean uartClosedVerified, boolean subprocessesStopped, String cleanupError) {
            this.status = status; this.fieldsComplete = fieldsComplete;
            this.softwareEntryAttempted = softwareEntryAttempted; this.softwareEntryConfirmed = softwareEntryConfirmed;
            this.bootloaderObserved = bootloaderObserved; this.controlStateChanged = controlStateChanged;
            this.recoveryHoldRequested = recoveryHoldRequested; this.firmwareDataSent = firmwareDataSent;
            this.usbOpened = usbOpened; this.quitSent = quitSent; this.flashMayHaveStarted = flashMayHaveStarted;
            this.uartClosedVerified = uartClosedVerified; this.subprocessesStopped = subprocessesStopped;
            this.cleanupError = cleanupError;
        }
    }

    static boolean structurallyValid(Result result) {
        if (result == null || !result.fieldsComplete || result.status == null || result.cleanupError == null) return false;
        if (result.firmwareDataSent || result.usbOpened || result.quitSent || result.flashMayHaveStarted) return false;
        if (result.softwareEntryConfirmed && (!result.softwareEntryAttempted || !result.bootloaderObserved)) return false;
        if (result.softwareEntryAttempted && !result.controlStateChanged) return false;
        boolean unresolved = result.softwareEntryAttempted || result.bootloaderObserved || result.controlStateChanged
                || !result.uartClosedVerified || !result.subprocessesStopped || !result.cleanupError.isEmpty();
        if (unresolved && !result.recoveryHoldRequested) return false;
        if (ENTRY_CONFIRMED.equals(result.status)) {
            return result.softwareEntryAttempted && result.softwareEntryConfirmed && result.bootloaderObserved
                    && result.uartClosedVerified && result.subprocessesStopped && result.cleanupError.isEmpty();
        }
        if (BOOTLOADER_PRESENT.equals(result.status)) {
            return !result.softwareEntryAttempted && !result.softwareEntryConfirmed && result.bootloaderObserved
                    && result.uartClosedVerified && result.subprocessesStopped && result.cleanupError.isEmpty();
        }
        if ("INDETERMINATE".equals(result.status)) return result.recoveryHoldRequested;
        if ("FAILED".equals(result.status) || "CANCELLED".equals(result.status)) {
            return !result.softwareEntryAttempted && !result.softwareEntryConfirmed
                    && !result.bootloaderObserved && !result.controlStateChanged;
        }
        return false; // Includes SUCCESS/CHECK_OK: neither is a valid probe outcome.
    }

    /** Only a fully observed, zero-exit helper result may release this operation's provisional hold. */
    static boolean holdAfterResult(boolean inheritedHold, boolean helperStarted, boolean helperExitedCleanly, Result result) {
        if (inheritedHold) return true;
        if (!helperStarted) return false;
        if (!helperExitedCleanly || !structurallyValid(result)) return true;
        return result.recoveryHoldRequested || result.softwareEntryAttempted || result.bootloaderObserved
                || result.controlStateChanged || !result.uartClosedVerified || !result.subprocessesStopped
                || !result.cleanupError.isEmpty() || "INDETERMINATE".equals(result.status);
    }
}
