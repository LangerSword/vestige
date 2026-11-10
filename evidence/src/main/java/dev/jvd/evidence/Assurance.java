package dev.jvd.evidence;

import dev.jvd.evidence.EvidenceRecord.AssuranceLevel;
import dev.jvd.evidence.EvidenceRecord.CanaryResult;
import dev.jvd.evidence.EvidenceRecord.Method;

/**
 * Assurance-level computation, implementing PLAN §5 exactly (which fixes the
 * report §6.4 contradiction: a run whose controls failed is INVALID and caps
 * at A0 at <em>every</em> level — deviation D5).
 *
 * <ul>
 *   <li>A0 — deletion only; or controls invalid; or canary not NOT_FOUND
 *       (FOUND residue, or NOT_CHECKABLE); or a crypto-shred transaction still
 *       pre-COMMIT (data intentionally recoverable in the vault window).</li>
 *   <li>A1 — overwrite/heap wipe + canary NOT_FOUND in all checkable locations
 *       + positive and negative controls valid.</li>
 *   <li>A2 — crypto-shred COMMITTED + post-commit decrypt attempt fails +
 *       controls valid + canary NOT_FOUND.</li>
 *   <li>A3 — A2 + evidence in a witness-cosigned log + independent
 *       re-validation by a separate process.</li>
 * </ul>
 * Every level still carries "physical media NOT verified" in the record's
 * residualRisk field.
 */
public final class Assurance {

    private Assurance() {}

    public static AssuranceLevel compute(Method method,
                                         boolean controlsValid,
                                         CanaryResult canaryResult,
                                         boolean committed,
                                         boolean witnessed,
                                         boolean independentlyRevalidated) {
        if (!controlsValid) return AssuranceLevel.A0;
        if (canaryResult != CanaryResult.NOT_FOUND) return AssuranceLevel.A0;
        if (method == Method.CRYPTO_SHRED) {
            if (!committed) return AssuranceLevel.A0;
            if (witnessed && independentlyRevalidated) return AssuranceLevel.A3;
            return AssuranceLevel.A2;
        }
        // CLEAR_OVERWRITE / MEMORY_WIPE have no commit state; a completed,
        // control-validated NOT_FOUND run is A1. Witnessing does not raise a
        // non-crypto method to A2/A3: those levels are defined over crypto-shred.
        return AssuranceLevel.A1;
    }
}
