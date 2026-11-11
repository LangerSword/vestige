package dev.jvd.gui;

import dev.jvd.evidence.EvidenceRecord;
import dev.jvd.evidence.Signer;
import dev.jvd.log.Checkpoint;
import dev.jvd.log.PersistentLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The headless test suite of the desktop app.
 *
 * <p>It exercises the service layer ({@link DestroyRunner},
 * {@link EvidenceVerifier}, {@link ProofView}) and never touches Swing, so it
 * runs in CI with no display. The GUI's own classes are view code over these
 * results; the behaviours that matter are asserted here.
 */
class DestroyRunnerTest {

    @TempDir Path tmp;

    private AppPaths paths() {
        return new AppPaths(tmp.resolve("work"), tmp.resolve("log"));
    }

    @Test
    void destroyReallyRemovesTheFileAndProducesAVerifiableRecord() throws Exception {
        Path source = tmp.resolve("victim.txt");
        Files.writeString(source, "data that must not survive");

        SessionKey key = SessionKey.generate();
        DestroyRunner runner = new DestroyRunner(paths(), key);
        List<StageEvent> events = new ArrayList<>();

        DestroyResult result = runner.destroy(source, events::add);

        // 1. The file is really gone.
        assertFalse(Files.exists(source), "the source file must really be deleted");
        assertTrue(result.destroyed(), "the run must report success: " + result.errorMessage());
        assertEquals(DestroyResult.Status.DESTROYED, result.status());

        // 2. A real evidence record was produced on disk.
        assertNotNull(result.evidenceRecordPath());
        assertTrue(Files.exists(result.evidenceRecordPath()), "an evidence record file must exist");
        EvidenceRecord rec = EvidenceRecord.parse(
                Files.readString(result.evidenceRecordPath(), StandardCharsets.UTF_8));
        assertEquals(EvidenceRecord.Method.CRYPTO_SHRED, rec.method);
        assertEquals(EvidenceRecord.VerificationStatus.COMPLETED, rec.verificationStatus,
                "the post-commit decrypt check must have failed for the record to say COMPLETED");

        // 3. The record's signature verifies against the log's public key.
        LogKeyStore store = new LogKeyStore(paths().logDir());
        assertNotNull(store.logPublicKey(), "the log public key must exist");
        assertTrue(Signer.verify(rec, store.logPublicKey()), "the record signature must verify");

        // 4. The record reached the shared log and the checkpoint covers it.
        PersistentLog pl = PersistentLog.open(paths().logDir());
        assertEquals(1, pl.log().size(), "the record must be appended to the log");
        Checkpoint cp = pl.loadCheckpoint();
        assertNotNull(cp, "a checkpoint must be written");
        assertEquals(1, cp.size);
        assertTrue(cp.verifyLogSignature(store.logPublicKey()));
        assertNotNull(cp.witnessSignature, "the checkpoint must be witness-cosigned");
        assertTrue(cp.verifyWitnessSignature(store.witnessPublicKey()));

        // 5. The stages are real and in order.
        assertFalse(events.isEmpty());
        List<String> headlines = events.stream().map(StageEvent::headline).toList();
        assertTrue(headlines.contains("2. Making a protected copy"), headlines.toString());
        assertTrue(headlines.contains("3. Destroying the original"), headlines.toString());
        assertTrue(headlines.contains("5. Making it permanent"), headlines.toString());
        assertTrue(headlines.contains("9. Done"), headlines.toString());

        // 6. The proof view is friendly: it states the limitation and shows the
        //    signature as valid rather than as a base64 blob.
        ProofView proof = result.proof();
        assertNotNull(proof);
        String text = proof.asText();
        assertTrue(text.contains("Present and valid"), text);
        assertFalse(text.contains("signature\":\"") , "no raw signature blob in the proof view");
        assertTrue(result.limitation().contains("cannot be checked"), result.limitation());
    }

    @Test
    void theKeptCopyCannotBeReadAfterDestruction() throws Exception {
        Path source = tmp.resolve("secret.bin");
        byte[] content = new byte[4096];
        for (int i = 0; i < content.length; i++) content[i] = (byte) i;
        Files.write(source, content);

        DestroyRunner runner = new DestroyRunner(paths(), SessionKey.generate());
        DestroyResult result = runner.destroy(source, e -> {});

        assertTrue(result.destroyed());

        // The transaction keeps the shredded ciphertext as artifact.enc, but
        // the key that opens it was destroyed at COMMIT.
        Path work = result.evidenceRecordPath().getParent();
        Path artifact = work.resolve("artifact.enc");
        assertTrue(Files.exists(artifact), "the shredded ciphertext is kept as an artefact");
        assertFalse(Files.exists(work.resolve("vault.key")), "the wrapped key must be destroyed");

        // The original plaintext must not be recoverable from the artefact.
        byte[] kept = Files.readAllBytes(artifact);
        assertFalse(java.util.Arrays.equals(kept, content), "the kept bytes must not be the plaintext");
    }

    @Test
    void destroyingTheSameFileTwiceIsSafeAndReportsClearly() throws Exception {
        Path source = tmp.resolve("once.txt");
        Files.writeString(source, "hello");
        DestroyRunner runner = new DestroyRunner(paths(), SessionKey.generate());
        assertTrue(runner.destroy(source, e -> {}).destroyed());

        // Second attempt: the file is gone, so the app must say so plainly
        // rather than crash.
        DestroyResult second = runner.destroy(source, e -> {});
        assertFalse(second.destroyed());
        assertEquals(DestroyResult.Status.FAILED, second.status());
        assertNotNull(second.errorMessage());
        assertTrue(second.errorMessage().toLowerCase().contains("could not find"),
                second.errorMessage());
    }

    @Test
    void aDirectoryIsRejectedBeforeAnythingIsDestroyed() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("a-folder"));
        Path inside = dir.resolve("keep.txt");
        Files.writeString(inside, "keep me");

        DestroyRunner runner = new DestroyRunner(paths(), SessionKey.generate());
        DestroyResult result = runner.destroy(dir, e -> {});

        assertFalse(result.destroyed());
        assertTrue(Files.exists(inside), "a rejected run must not touch anything");
        assertTrue(result.errorMessage().toLowerCase().contains("folder"),
                result.errorMessage());
    }

    @Test
    void aMissingFileIsReportedNotThrown() throws Exception {
        DestroyRunner runner = new DestroyRunner(paths(), SessionKey.generate());
        DestroyResult result = runner.destroy(tmp.resolve("nope.txt"), e -> {});
        assertFalse(result.destroyed());
        assertEquals(DestroyResult.Status.FAILED, result.status());
        assertNotNull(result.errorMessage());
    }

    @Test
    void theOfflineVerifierAcceptsAHistoryItHasNotBeenTamperedWith() throws Exception {
        Path source = tmp.resolve("ok.txt");
        Files.writeString(source, "data");
        DestroyRunner runner = new DestroyRunner(paths(), SessionKey.generate());
        assertTrue(runner.destroy(source, e -> {}).destroyed());

        EvidenceVerifier verifier = new EvidenceVerifier(paths().logDir());
        LogKeyStore store = new LogKeyStore(paths().logDir());
        VerifyResult result = verifier.verify(store.logPublicKey(), store.witnessPublicKey());

        assertTrue(result.ok(), result.headline() + " / " + result.explanation());
        assertTrue(result.headline().contains("Everything checks out"), result.headline());
        assertTrue(result.explanation().contains("second, independent signature"),
                "the witness co-signature should be reported: " + result.explanation());
    }

    @Test
    void theOfflineVerifierDetectsAnAlteredRecord() throws Exception {
        Path source = tmp.resolve("tamper.txt");
        Files.writeString(source, "data");
        DestroyRunner runner = new DestroyRunner(paths(), SessionKey.generate());
        assertTrue(runner.destroy(source, e -> {}).destroyed());

        // Tamper with the stored record: change the subject hash so the
        // signature no longer matches. The leaf file is the log's own storage.
        Path leaves = paths().logDir().resolve(PersistentLog.LEAVES_FILE);
        String line = Files.readString(leaves, StandardCharsets.UTF_8).strip();
        EvidenceRecord rec = EvidenceRecord.parse(
                new String(java.util.Base64.getDecoder().decode(line), StandardCharsets.UTF_8));
        rec.subjectHash = "0000000000000000000000000000000000000000000000000000000000000000";
        String tampered = rec.toSignedJson();
        Files.writeString(leaves,
                java.util.Base64.getEncoder().encodeToString(tampered.getBytes(StandardCharsets.UTF_8)) + "\n",
                StandardCharsets.UTF_8);

        EvidenceVerifier verifier = new EvidenceVerifier(paths().logDir());
        VerifyResult result = verifier.verify(new LogKeyStore(paths().logDir()).logPublicKey());

        assertFalse(result.ok(), "tampering must be detected");
        assertTrue(result.headline().contains("Something is wrong"), result.headline());
    }

    @Test
    void theVerifierSaysSoWhenThereIsNothingToCheck() {
        EvidenceVerifier verifier = new EvidenceVerifier(tmp.resolve("empty-log"));
        VerifyResult result = verifier.verify(null);
        assertFalse(result.ok());
        assertTrue(result.headline().contains("nothing to check"), result.headline());
    }

    @Test
    void theSessionKeyIsNeverShownToTheUser() {
        SessionKey key = SessionKey.generate();
        assertEquals(SessionKey.KEY_BYTES, key.copy().length);
        String masked = key.masked();
        assertFalse(masked.contains("0"), "the masked key must not leak bytes: " + masked);
        // Two keys must differ: a fresh key per session, not a constant.
        assertFalse(java.util.Arrays.equals(key.copy(), SessionKey.generate().copy()));
    }

    @Test
    void theProofViewShowsTheHonestLimitation() {
        EvidenceRecord rec = new EvidenceRecord();
        rec.subjectHash = "abc";
        ProofView view = ProofView.of(rec, false);
        String text = view.asText();
        assertTrue(text.contains("Missing - this record was never signed"), text);
        assertTrue(text.contains("What we could not check"), text);
    }
}
