package dev.jvd.evidence;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceTest {

    private static EvidenceRecord sample() {
        EvidenceRecord r = new EvidenceRecord();
        r.timestampUtc = Instant.parse("2026-11-10T00:00:00Z");
        r.subjectHash = EvidenceRecord.subjectHash("salt", "/home/user/secret.txt");
        r.method = EvidenceRecord.Method.CRYPTO_SHRED;
        r.verificationStatus = EvidenceRecord.VerificationStatus.COMPLETED;
        r.canaryResult = EvidenceRecord.CanaryResult.NOT_FOUND;
        r.controlsValid = true;
        r.verificationDetail = "post-commit decrypt attempt failed; controls valid";
        r.validationDecision = EvidenceRecord.ValidationDecision.PENDING;
        r.residualRisk = "physical media NOT verified; quote \" and \n newline in detail";
        r.assuranceLevel = EvidenceRecord.AssuranceLevel.A2;
        return r;
    }

    @Test
    void jsonRoundtrip() {
        EvidenceRecord r = sample();
        EvidenceRecord back = EvidenceRecord.parse(r.toJson());
        assertEquals(r.eventId, back.eventId);
        assertEquals(r.timestampUtc, back.timestampUtc);
        assertEquals(r.subjectHash, back.subjectHash);
        assertEquals(r.method, back.method);
        assertEquals(r.canaryResult, back.canaryResult);
        assertTrue(back.controlsValid);
        assertEquals(r.residualRisk, back.residualRisk);
        assertEquals(r.assuranceLevel, back.assuranceLevel);
        assertEquals(r.toJson(), back.toJson(), "canonical form must be stable across roundtrip");
    }

    @Test
    void signatureVerifiesAndTamperFails() {
        KeyPair kp = Signer.generateKeyPair();
        EvidenceRecord r = sample();
        Signer.sign(r, kp.getPrivate());
        assertTrue(Signer.verify(r, kp.getPublic()));
        EvidenceRecord parsed = EvidenceRecord.parse(r.toSignedJson());
        assertTrue(Signer.verify(parsed, kp.getPublic()));

        EvidenceRecord tampered = EvidenceRecord.parse(r.toSignedJson());
        tampered.verificationDetail = "edited after signing";
        assertFalse(Signer.verify(tampered, kp.getPublic()));

        KeyPair other = Signer.generateKeyPair();
        assertFalse(Signer.verify(r, other.getPublic()));
    }

    @Test
    void verificationAndValidationAreSeparateFields() {
        EvidenceRecord r = sample();
        String json = r.toJson();
        assertTrue(json.contains("\"verification\":{"), json);
        assertTrue(json.contains("\"validation\":{"), json);
        assertTrue(json.contains("\"decision\":\"PENDING\""), json);
    }

    @Test
    void assuranceMapping() {
        var M = EvidenceRecord.Method.CRYPTO_SHRED;
        var NR = EvidenceRecord.CanaryResult.NOT_FOUND;
        // failed controls -> A0 (PLAN section 5 / deviation D5)
        assertEquals(EvidenceRecord.AssuranceLevel.A0,
                Assurance.compute(M, false, NR, true, true, true));
        // canary found -> A0
        assertEquals(EvidenceRecord.AssuranceLevel.A0,
                Assurance.compute(M, true, EvidenceRecord.CanaryResult.FOUND, true, true, true));
        // crypto-shred pre-COMMIT -> A0 (vault window is recoverable by design)
        assertEquals(EvidenceRecord.AssuranceLevel.A0,
                Assurance.compute(M, true, NR, false, false, false));
        // A2: committed crypto-shred, controls valid, NOT_FOUND
        assertEquals(EvidenceRecord.AssuranceLevel.A2,
                Assurance.compute(M, true, NR, true, false, false));
        // A3: A2 + witnessed + independently revalidated
        assertEquals(EvidenceRecord.AssuranceLevel.A3,
                Assurance.compute(M, true, NR, true, true, true));
        // witnessed but not revalidated stays A2
        assertEquals(EvidenceRecord.AssuranceLevel.A2,
                Assurance.compute(M, true, NR, true, true, false));
        // A1: overwrite with valid controls and NOT_FOUND
        assertEquals(EvidenceRecord.AssuranceLevel.A1,
                Assurance.compute(EvidenceRecord.Method.CLEAR_OVERWRITE, true, NR, false, false, false));
        // NOT_CHECKABLE caps at A0
        assertEquals(EvidenceRecord.AssuranceLevel.A0,
                Assurance.compute(EvidenceRecord.Method.CLEAR_OVERWRITE, true,
                        EvidenceRecord.CanaryResult.NOT_CHECKABLE, false, false, false));
    }
}
