package org.kpfe.restore;

/** Host-only safety invariants for a probe that can change controller mode but cannot flash. */
public final class FitProbePolicyTest {
    private static int checks;
    private static void expect(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static boolean bit(int mask, int index) { return (mask & (1 << index)) != 0; }
    private static FitProbePolicy.Result result(String status, boolean complete, int bits, String cleanup) {
        return new FitProbePolicy.Result(status, complete, bit(bits, 0), bit(bits, 1), bit(bits, 2),
                bit(bits, 3), bit(bits, 4), bit(bits, 5), bit(bits, 6), bit(bits, 7), bit(bits, 8),
                bit(bits, 9), bit(bits, 10), cleanup);
    }

    public static void main(String[] args) {
        int closed = (1 << 9) | (1 << 10);
        FitProbePolicy.Result safeFailure = result("FAILED", true, closed, "");
        expect(FitProbePolicy.structurallyValid(safeFailure));
        expect(!FitProbePolicy.holdAfterResult(false, false, false, null)); // No helper began.
        expect(!FitProbePolicy.holdAfterResult(false, true, true, safeFailure));
        expect(FitProbePolicy.holdAfterResult(false, true, false, safeFailure)); // Nonzero/unobserved exit.
        expect(FitProbePolicy.holdAfterResult(true, false, false, null)); // Prior hold always survives.
        expect(FitProbePolicy.holdAfterResult(false, true, true, null)); // Lost result is not proof of no action.
        FitProbePolicy.Result entered = result("FIT_ENTRY_CONFIRMED", true, closed | 31, "");
        FitProbePolicy.Result present = result("FIT_BOOTLOADER_PRESENT", true, closed | 4 | 16, "");
        expect(FitProbePolicy.structurallyValid(entered));
        expect(FitProbePolicy.structurallyValid(present));
        expect(FitProbePolicy.holdAfterResult(false, true, true, entered));
        expect(FitProbePolicy.holdAfterResult(false, true, true, present));
        expect(!FitProbePolicy.structurallyValid(result("FIT_ENTRY_CONFIRMED", true, closed | 4 | 16, ""))); // A pre-existing loader is not software entry.
        expect(!FitProbePolicy.structurallyValid(result("FIT_BOOTLOADER_PRESENT", true, closed | 31, ""))); // Entry must not be relabeled as pre-existing.
        expect(FitProbePolicy.structurallyValid(result("INDETERMINATE", true, closed | 1 | 8 | 16, ""))); // Partial E7, no loader.
        expect(FitProbePolicy.structurallyValid(result("INDETERMINATE", true, closed | 16, "FINISH withheld"))); // Pause/unknown USB.
        expect(!FitProbePolicy.structurallyValid(result("FIT_ENTRY_CONFIRMED", true, closed | 31, "close failed")));
        expect(!FitProbePolicy.mayResume(false, false, false, true, true)); // No USB identity is not normal-mode proof.
        expect(FitProbePolicy.mayResume(false, false, true, true, true));
        for (int bits = 0; bits < 32; bits++) {
            boolean allowed = FitProbePolicy.mayResume(bit(bits, 0), bit(bits, 1), bit(bits, 2), bit(bits, 3), bit(bits, 4));
            if (allowed) expect(!bit(bits, 0) && !bit(bits, 1) && bit(bits, 2) && bit(bits, 3) && bit(bits, 4));
            if (bit(bits, 0) || bit(bits, 1) || !bit(bits, 2) || !bit(bits, 3) || !bit(bits, 4)) expect(!allowed);
        }
        for (int bits = 0; bits < 64; bits++) {
            boolean hold = FitProbePolicy.recoveryHoldRequired(bit(bits, 0), bit(bits, 1), bit(bits, 2), bit(bits, 3), bit(bits, 4), bit(bits, 5));
            if (bit(bits, 0) || bit(bits, 1) || (bit(bits, 2) && !bit(bits, 3)) || !bit(bits, 4) || !bit(bits, 5)) expect(hold);
            if (!hold) expect(!bit(bits, 0) && !bit(bits, 1) && (!bit(bits, 2) || bit(bits, 3)) && bit(bits, 4) && bit(bits, 5));
        }
        for (String status : new String[] {"FIT_ENTRY_CONFIRMED", "FIT_BOOTLOADER_PRESENT", "FAILED", "CANCELLED", "INDETERMINATE", "SUCCESS", "CHECK_OK", "unknown", null}) {
            for (int bits = 0; bits < 2048; bits++) {
                for (String cleanup : new String[] {"", "cleanup uncertain", null}) {
                    FitProbePolicy.Result item = result(status, true, bits, cleanup);
                    boolean valid = FitProbePolicy.structurallyValid(item);
                    if (bit(bits, 5) || bit(bits, 6) || bit(bits, 7) || bit(bits, 8) || cleanup == null) expect(!valid);
                    if ("SUCCESS".equals(status) || "CHECK_OK".equals(status) || "unknown".equals(status) || status == null) expect(!valid);
                    expect(FitProbePolicy.holdAfterResult(true, true, true, item));
                    expect(FitProbePolicy.holdAfterResult(false, true, false, item));
                    if (!FitProbePolicy.holdAfterResult(false, true, true, item)) {
                        expect(valid && !bit(bits, 0) && !bit(bits, 1) && !bit(bits, 2) && !bit(bits, 3)
                                && !bit(bits, 4) && !bit(bits, 5) && !bit(bits, 6) && !bit(bits, 7) && !bit(bits, 8)
                                && bit(bits, 9) && bit(bits, 10) && "".equals(cleanup));
                    }
                    FitProbePolicy.Result incomplete = result(status, false, bits, cleanup);
                    expect(!FitProbePolicy.structurallyValid(incomplete));
                    expect(FitProbePolicy.holdAfterResult(false, true, true, incomplete));
                }
            }
        }
        System.out.println("FitProbePolicyTest: " + checks + " checks passed; handoff, result consistency and persistent-hold invariants");
    }
}
