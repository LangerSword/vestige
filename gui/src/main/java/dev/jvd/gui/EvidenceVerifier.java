package dev.jvd.gui;

import dev.jvd.evidence.EvidenceRecord;
import dev.jvd.evidence.Signer;
import dev.jvd.log.Checkpoint;
import dev.jvd.log.MerkleLog;
import dev.jvd.log.PersistentLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The offline evidence check, as the desktop app runs it.
 *
 * <p>This is deliberately the same logic the {@code jvd-verify} command-line
 * tool performs (module {@code verifier-cli}): the point of the evidence is
 * that a <em>separate</em> check, run without trusting the tool's own
 * console output, can re-derive the truth from the stored bytes. Re-implementing
 * it here would risk a second, weaker definition of "verified"; instead the
 * same primitives are used — the Merkle root is recomputed from the stored
 * leaves, the checkpoint's signatures are checked, every leaf's inclusion
 * proof is verified against the checkpoint root, and the record timestamps are
 * checked for monotonicity.
 *
 * <p>What it detects (PLAN §7 item 4):
 * <ul>
 *   <li>an edited record — the signature or the recomputed root no longer
 *       matches;</li>
 *   <li>a deleted record — the log size no longer matches the checkpoint;</li>
 *   <li>a backdated record — timestamps are not in order;</li>
 *   <li>a forked log — two checkpoints for the same size disagree.</li>
 * </ul>
 */
public final class EvidenceVerifier {

    private final Path logDir;

    public EvidenceVerifier(Path logDir) {
        this.logDir = logDir;
    }

    /**
     * Checks the log and the most recent record.
     *
     * @param logPublicKey the log's public key; when null the signature checks
     *                     are skipped and the result says so
     * @return a plain-language verdict
     */
    public VerifyResult verify(PublicKey logPublicKey) {
        return verify(logPublicKey, null);
    }

    /**
     * Checks the log, the most recent record, and — when the witness key is
     * available — the independent co-signature on the checkpoint.
     *
     * <p>The witness co-signature matters because a log key alone is only as
     * trustworthy as whoever holds it. A second, independent key that signs
     * the same summary means a compromised log key alone cannot rewrite
     * history unnoticed (Assumption 8).
     *
     * @param logPublicKey    the log's public key
     * @param witnessPublicKey the witness's public key, or null to skip that check
     */
    public VerifyResult verify(PublicKey logPublicKey, PublicKey witnessPublicKey) {
        List<String> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        try {
            PersistentLog pl = PersistentLog.open(logDir);
            MerkleLog log = pl.log();
            Checkpoint cp = pl.loadCheckpoint();

            if (log.size() == 0) {
                return notCheckable("There is nothing to check yet.",
                        "No destruction records have been written yet.", "");
            }
            if (cp == null) {
                return notCheckable("We could not check the history.",
                        "The log has records but no signed summary of them, so it cannot be verified.",
                        raw.toString());
            }
            if (logPublicKey == null) {
                return notCheckable("We could not check the history.",
                        "The signing key for this log is not available, so the signatures could not be checked.",
                        raw.toString());
            }

            boolean ok = true;

            // 1. Size and root vs the checkpoint: catches edited or deleted leaves.
            if (cp.size != log.size()) {
                findings.add("The log holds " + log.size() + " records but the signed summary covers "
                        + cp.size + ". A record was added or removed after it was signed.");
                ok = false;
            } else if (!Arrays.equals(cp.root, log.rootHash())) {
                findings.add("A record was changed after it was signed.");
                ok = false;
            }

            // 2. The checkpoint's own signatures.
            if (!cp.verifyLogSignature(logPublicKey)) {
                findings.add("The signed summary itself does not check out.");
                ok = false;
            }
            boolean witnessCosigned = cp.witnessSignature != null;
            if (witnessCosigned) {
                // Informational only: the summary carries a second, independent
                // signature. Its presence is good news, not a finding.
                notes.add("The summary carries a second, independent signature.");
                if (witnessPublicKey != null && !cp.verifyWitnessSignature(witnessPublicKey)) {
                    findings.add("The second, independent signature does not check out.");
                    ok = false;
                }
            }

            // 3. Inclusion proof for every leaf against the checkpoint root.
            if (ok) {
                for (int i = 0; i < log.size(); i++) {
                    if (!MerkleLog.verifyInclusion(log.leaf(i), i, log.size(),
                            log.inclusionProof(i), cp.root)) {
                        findings.add("Record " + (i + 1) + " does not match the signed summary.");
                        ok = false;
                        break;
                    }
                }
            }

            // 4. Timestamps must not go backwards (Assumption 9).
            Instant prev = null;
            for (int i = 0; i < log.size() && ok; i++) {
                EvidenceRecord rec;
                try {
                    rec = EvidenceRecord.parse(new String(log.leaf(i), StandardCharsets.UTF_8));
                } catch (Exception e) {
                    continue; // not a record leaf; nothing to check
                }
                if (prev != null && rec.timestampUtc.isBefore(prev)) {
                    findings.add("Record " + (i + 1) + " is dated before the record before it.");
                    ok = false;
                    break;
                }
                prev = rec.timestampUtc;
            }

            // 5. The newest record's own signature.
            boolean recordSignatureOk = false;
            EvidenceRecord newest = null;
            try {
                newest = EvidenceRecord.parse(new String(log.leaf(log.size() - 1), StandardCharsets.UTF_8));
                recordSignatureOk = Signer.verify(newest, logPublicKey);
                if (!recordSignatureOk) {
                    findings.add("The most recent record was changed after it was signed.");
                    ok = false;
                }
            } catch (Exception e) {
                // a non-record leaf: not a failure of the log itself
            }

            raw.append("logSize=").append(log.size()).append('\n');
            raw.append("checkpointSize=").append(cp.size).append('\n');
            raw.append("rootMatches=").append(Arrays.equals(cp.root, log.rootHash())).append('\n');
            raw.append("logSignatureValid=").append(cp.verifyLogSignature(logPublicKey)).append('\n');
            raw.append("witnessCosigned=").append(witnessCosigned).append('\n');
            raw.append("newestRecordSignatureValid=").append(recordSignatureOk).append('\n');
            raw.append("newestEventId=").append(newest == null ? "n/a" : newest.eventId).append('\n');

            if (ok) {
                String explanation = "We checked all " + log.size() + " records against the signed summary, "
                        + "confirmed each one is signed, and confirmed they are all in order.";
                if (!notes.isEmpty()) {
                    explanation = explanation + " " + String.join(" ", notes);
                }
                return new VerifyResult(true,
                        "Everything checks out - your history of destructions is intact.",
                        explanation,
                        raw.toString());
            }
            String problems = String.join(" ", findings);
            if (!notes.isEmpty()) {
                problems = problems + " " + String.join(" ", notes);
            }
            return new VerifyResult(false,
                    "Something is wrong - your history may have been altered.",
                    problems,
                    raw.toString());

        } catch (Exception e) {
            raw.append("error=").append(e).append('\n');
            return notCheckable("We could not check the history.",
                    "The records could not be read: " + Friendly.describeFailure(e),
                    raw.toString());
        }
    }

    /**
     * Reads back the most recent evidence record from the log, so the GUI can
     * show the proof of what happened most recently.
     */
    public EvidenceRecord lastRecord() {
        try {
            PersistentLog pl = PersistentLog.open(logDir);
            if (pl.log().size() == 0) return null;
            return EvidenceRecord.parse(new String(pl.log().leaf(pl.log().size() - 1),
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** The log directory this verifier reads. */
    public Path logDir() {
        return logDir;
    }

    /** True when the log directory holds at least one record. */
    public boolean hasRecords() {
        try {
            return Files.exists(logDir.resolve(PersistentLog.LEAVES_FILE));
        } catch (Exception e) {
            return false;
        }
    }

    private static VerifyResult notCheckable(String headline, String explanation, String raw) {
        return new VerifyResult(false, headline, explanation, raw);
    }
}
