package org.kpfe.restore;

/** A missing observation must never become a claim that the controller is dead or ready. */
public final class SupportFindingsTest {
    private static int checks;
    public static void main(String[] args) {
        SupportFindings.Facts facts = new SupportFindings.Facts();
        String text = SupportFindings.summarize(facts);
        require(text.contains("USB enumeration unavailable"));
        require(!text.contains("Neither expected USB ID was seen"));
        require(text.contains("Root information is incomplete"));
        require(text.contains("not a standard FIT model"));
        facts.standardFit = true;
        facts.usbCaptured = true;
        text = SupportFindings.summarize(facts);
        require(text.contains("Neither expected USB ID was seen"));
        require(text.contains("does not establish a dead controller"));
        facts.bootCandidates = 1;
        text = SupportFindings.summarize(facts);
        require(text.contains("bootloader-ID candidate is enumerated"));
        require(text.contains("ability to accept firmware were NOT tested"));
        facts.bootCandidates = 0;
        facts.appCandidates = 1;
        text = SupportFindings.summarize(facts);
        require(text.contains("software update entry were NOT tested"));
        facts.bootCandidates = 1;
        require(SupportFindings.summarize(facts).contains("USB mode ambiguous"));
        facts.appCandidates = 0;
        facts.bootCandidates = 2;
        require(SupportFindings.summarize(facts).contains("USB mode ambiguous"));
        facts.rootConfirmed = true;
        facts.rootComplete = true;
        require(!SupportFindings.summarize(facts).contains("Root information is incomplete"));
        facts.rootTruncated = true;
        text = SupportFindings.summarize(facts);
        require(text.contains("Root information is incomplete"));
        require(text.contains("separate from the AYANEO updater's attempt"));
        require(text.contains("cannot recreate discarded historical logs"));
        require(text.contains("No automatic retry"));
        System.out.println("SupportFindingsTest: " + checks + " checks passed");
    }
    private static void require(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Support findings check " + checks);
    }
}
