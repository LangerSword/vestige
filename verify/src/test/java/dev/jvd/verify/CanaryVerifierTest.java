package dev.jvd.verify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CanaryVerifierTest {

    @Test
    void nowipeTargetIsFoundWithValidControls() {
        CanaryVerifier.VerificationOutcome out = new CanaryVerifier().run("G1", "NOWIPE");
        System.out.println(out.detail());
        assertTrue(out.positiveControlOk(), out.detail());
        assertTrue(out.negativeControlOk(), out.detail());
        assertTrue(out.controlsValid(), out.detail());
        assertEquals(CanaryVerifier.Status.VALID, out.status());
        // Deterministic: an un-wiped canary dropped in a sleeping child's heap MUST be found.
        assertEquals(CanaryVerifier.Result.FOUND, out.canaryResult(), out.detail());
    }

    @Test
    void nowipeTargetIsFoundUnderZgc() {
        // Regression (Assumption 17): ZGC maps its heap via /memfd:java_heap,
        // which the original ProcScanner skipped as "file-backed", so every
        // ZGC run — including this un-wiped control — came back INVALID.
        CanaryVerifier.VerificationOutcome out = new CanaryVerifier().run("ZGC", "NOWIPE");
        System.out.println(out.detail());
        assertTrue(out.positiveControlOk(), out.detail());
        assertTrue(out.negativeControlOk(), out.detail());
        assertTrue(out.controlsValid(), out.detail());
        assertEquals(CanaryVerifier.Status.VALID, out.status());
        assertEquals(CanaryVerifier.Result.FOUND, out.canaryResult(), out.detail());
    }

    @Test
    void wipeTargetRunHasValidControls() {
        CanaryVerifier.VerificationOutcome out = new CanaryVerifier().run("G1", "WIPE");
        System.out.println(out.detail());
        assertTrue(out.controlsValid(), out.detail());
        assertEquals(CanaryVerifier.Status.VALID, out.status());
        // NOTE: canaryResult is intentionally NOT asserted for a heap byte[]:
        // SPIKES H2 showed stale GC copies may legitimately remain (FOUND is an
        // honest outcome for best-effort heap wiping under G1). What must hold
        // is that the controls validate the run.
        assertNotEquals(CanaryVerifier.Result.NOT_CHECKABLE, out.canaryResult(), out.detail());
    }

    @Test
    void sabotagedPositiveControlMakesRunInvalid() {
        CanaryVerifier v = new CanaryVerifier();
        v.sabotagePositiveControl = true;
        CanaryVerifier.VerificationOutcome out = v.run("G1", "WIPE");
        System.out.println(out.detail());
        assertFalse(out.positiveControlOk(), out.detail());
        assertFalse(out.controlsValid());
        assertEquals(CanaryVerifier.Status.INVALID, out.status());
    }
}
