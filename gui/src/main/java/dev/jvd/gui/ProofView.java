package dev.jvd.gui;

import dev.jvd.evidence.EvidenceRecord;

/**
 * The readable rendering of a signed destruction record.
 *
 * <p>The GUI shows this instead of the raw JSON, because a base64 signature
 * is not something a person can check by eye. What the reader gets instead is
 * the substance of the record plus a plain statement about the signature:
 * present and valid, present but broken, or missing. The raw signature bytes
 * stay in the record file on disk for anyone who wants to verify them with
 * the command-line tools.
 *
 * <p>The view is built from {@link EvidenceRecord} fields only. It never
 * invents a conclusion: if the signature does not verify, the view says so.
 */
public final class ProofView {

    private final java.util.List<ProofLine> lines;

    private ProofView(java.util.List<ProofLine> lines) {
        this.lines = java.util.List.copyOf(lines);
    }

    /**
     * Builds the friendly view of a record.
     *
     * @param record         the parsed evidence record
     * @param signatureValid whether the record's signature verifies against the
     *                       log's public key; the caller performs the check so
     *                       this class stays free of key handling
     */
    public static ProofView of(EvidenceRecord record, boolean signatureValid) {
        java.util.List<ProofLine> out = new java.util.ArrayList<>();
        out.add(new ProofLine("Record ID", record.eventId));
        out.add(new ProofLine("When it happened", Friendly.timestamp(record.timestampUtc)));
        out.add(new ProofLine("What was destroyed", record.subjectHash));
        out.add(new ProofLine("How it was destroyed", Friendly.methodName(record.method)));
        out.add(new ProofLine("Did the check pass?",
                record.verificationStatus == EvidenceRecord.VerificationStatus.COMPLETED
                        ? "Yes - the app confirmed the data can no longer be read"
                        : "No - the app could not confirm the destruction"));
        out.add(new ProofLine("Memory residue check", Friendly.canaryName(record.canaryResult)));
        out.add(new ProofLine("Control tests", record.controlsValid
                ? "Passed - the check itself was trustworthy"
                : "Not part of this run"));
        out.add(new ProofLine("Signed off by a person", Friendly.decisionName(record.validationDecision)));
        out.add(new ProofLine("Signature", signatureStatement(record, signatureValid)));
        out.add(new ProofLine("How much we can vouch for", Friendly.assuranceName(record.assuranceLevel)));
        out.add(new ProofLine("What we could not check", Friendly.residualRisk(record.residualRisk)));
        return new ProofView(out);
    }

    private static String signatureStatement(EvidenceRecord record, boolean signatureValid) {
        if (record.signature == null) {
            return "Missing - this record was never signed";
        }
        if (signatureValid) {
            return "Present and valid - nobody has changed this record since it was signed";
        }
        return "Present but NOT valid - this record was changed after it was signed";
    }

    /** The lines of the proof view, in display order. */
    public java.util.List<ProofLine> lines() {
        return lines;
    }

    /** The proof view as the two-column text block shown on screen. */
    public String asText() {
        StringBuilder sb = new StringBuilder();
        for (ProofLine line : lines) {
            sb.append(Friendly.pair(line.label(), line.value())).append('\n');
        }
        return sb.toString();
    }
}
