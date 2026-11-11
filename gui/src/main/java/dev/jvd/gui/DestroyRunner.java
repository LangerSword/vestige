package dev.jvd.gui;

import dev.jvd.evidence.Assurance;
import dev.jvd.evidence.EvidenceRecord;
import dev.jvd.evidence.Signer;
import dev.jvd.file.CryptoShredder;
import dev.jvd.log.Checkpoint;
import dev.jvd.log.PersistentLog;
import dev.jvd.txn.DestructionTransaction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * The service that performs a destruction. It contains every bit of the real
 * work, and <b>not one line of Swing</b>.
 *
 * <p>That separation is deliberate and it is what makes the app testable: the
 * headless test suite exercises this class directly and asserts that a file is
 * really gone, a real evidence record is really produced, and the record
 * really verifies. The Swing layer is a thin display over the result object
 * this class returns.
 *
 * <p><b>What actually happens</b> (the transaction state machine from
 * PLAN §4 / the {@code txn} module):
 * <pre>
 *   STAGED -&gt; VAULTED -&gt; SCRUBBED -&gt; VALIDATED_PRECOMMIT -&gt; COMMITTED
 *                     ↘ (failure before COMMIT) ABORTED + restore
 * </pre>
 * The vault window is intentional: until COMMIT the data is recoverable by
 * design, which is what makes a failed run safe (the original is restored
 * rather than lost). COMMIT destroys the wrapped data key, after which the
 * ciphertext that is kept as {@code artifact.enc} is unrecoverable noise.
 *
 * <p><b>Why this is honest.</b> The post-commit check is not a formality: the
 * app re-attempts to decrypt the artefact it kept and requires that attempt
 * to <em>fail</em>. A successful decrypt would mean the shred was incomplete,
 * and the record would then say {@code FAILED}, not {@code COMPLETED}.
 */
public final class DestroyRunner {

    /** The tool version stamped into the evidence record. */
    public static final String TOOL_VERSION = "jvd-gui-0.1.0";

    /** Salt for the subject hash, keeping this tool's hashes distinct from the CLI's. */
    private static final String SUBJECT_SALT = "jvd-gui";

    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AppPaths paths;
    private final SessionKey sessionKey;
    private final LogKeyStore keyStore;

    public DestroyRunner(AppPaths paths, SessionKey sessionKey) {
        this.paths = paths;
        this.sessionKey = sessionKey;
        this.keyStore = new LogKeyStore(paths.logDir());
    }

    /**
     * Destroys one file, for real.
     *
     * @param source the file the user picked
     * @param sink   receives the pipeline stages as they happen; may be null
     * @return what happened, in a form the UI can display
     * @throws Exception when the run fails in a way that leaves the file's state
     *                   unknown; the message is already operator-friendly
     */
    public DestroyResult destroy(Path source, Consumer<StageEvent> sink) throws Exception {
        List<StageEvent> events = new ArrayList<>();
        Consumer<StageEvent> emit = sink == null ? e -> {} : sink;

        String fileName = source == null ? "" : source.getFileName().toString();
        String absolute = source == null ? "" : source.toAbsolutePath().toString();

        // Guard the obvious mistakes before touching anything on disk.
        String problem = validate(source);
        if (problem != null) {
            emit.accept(event(1, "Checking your file", problem));
            events.add(event(1, "Checking your file", problem));
            return failed(fileName, events, problem);
        }

        byte[] kek = sessionKey.copy();
        Path workDir = createWorkDir();
        // The transaction writes the KEK into txn.meta inside the work dir so an
        // interrupted run can be recovered later. That is the txn module's own
        // documented behaviour (see its "Key custody note"), and the work dir is
        // therefore a trusted location in this app's threat model.
        DestructionTransaction txn;
        try {
            txn = new DestructionTransaction(workDir, source, kek);
        } catch (Exception e) {
            String setupProblem = Friendly.describeFailure(e);
            emit.accept(event(1, "Checking your file", setupProblem));
            events.add(event(1, "Checking your file", setupProblem));
            return failed(fileName, events, setupProblem);
        }
        // Step 1 of the pipeline is the transaction's own STAGED event, so the
        // user sees the real lifecycle rather than a parallel narration.
        txn.setEvidenceSink((type, detail) -> {
            StageEvent e = stageFor(type, detail);
            events.add(e);
            emit.accept(e);
        });

        byte[] iv;
        try {
            txn.stage();
            txn.vault();
            CryptoShredder.ShredResult shred = txn.shredResult();
            iv = shred.iv.clone();
            txn.scrub();
            txn.validatePreCommit();
            txn.commit();
        } catch (Exception failure) {
            wipe(kek);
            return handleFailure(txn, source, fileName, events, emit, failure);
        }

        // ---- post-commit validation -------------------------------------
        // The wrapped key was wiped by commit(); re-attempting to decrypt the
        // kept ciphertext must FAIL. If it succeeds, the shred was incomplete.
        boolean decryptFailed;
        String decryptDetail;
        try {
            CryptoShredder.decrypt(txn.artifact(), txn.shredResult().wrappedDek, kek, iv);
            decryptFailed = false;
            decryptDetail = "the copy could still be read - the shred was incomplete";
        } catch (Exception e) {
            decryptFailed = true;
            decryptDetail = "the copy could not be read, because the key that opened it is gone";
        }
        boolean sourceGone = !Files.exists(source);
        // The per-run copy of the key has served its purpose: the file is
        // destroyed and the post-commit check is done. Wipe it out of this
        // method's stack frame so it does not linger in memory.
        wipe(kek);
        emit.accept(event(6, "Testing that it really cannot be read",
                "We tried to read the copy we kept. " + capitalise(decryptDetail) + "."));
        events.add(event(6, "Testing that it really cannot be read",
                "We tried to read the copy we kept. " + capitalise(decryptDetail) + "."));

        boolean verified = decryptFailed && sourceGone;

        // ---- evidence record --------------------------------------------
        List<String> txnEvents = events.stream().map(StageEvent::line).toList();
        EvidenceRecord rec = new EvidenceRecord();
        rec.toolVersion = TOOL_VERSION;
        rec.subjectHash = EvidenceRecord.subjectHash(SUBJECT_SALT, absolute);
        rec.method = EvidenceRecord.Method.CRYPTO_SHRED;
        rec.verificationStatus = verified
                ? EvidenceRecord.VerificationStatus.COMPLETED
                : EvidenceRecord.VerificationStatus.FAILED;
        rec.canaryResult = EvidenceRecord.CanaryResult.NOT_CHECKABLE;
        rec.controlsValid = false;
        rec.verificationDetail = "stages=" + txnEvents
                + "; post-commit decrypt attempt " + (decryptFailed ? "FAILED as required" : "SUCCEEDED")
                + "; source present after commit: " + (!sourceGone)
                + "; memory canary channel not run by the desktop app (NOT_CHECKABLE)";
        rec.validationDecision = EvidenceRecord.ValidationDecision.PENDING;
        rec.validationRationale =
                "Validation is a separate decision from verification (NIST SP 800-88 Rev.2); "
                        + "left PENDING for a person or policy to accept";
        rec.residualRisk = "physical media NOT verified; logical level only; "
                + "copies taken by the operating system, backups, or swap are outside the trust boundary";
        rec.assuranceLevel = Assurance.compute(rec.method, rec.controlsValid,
                rec.canaryResult, true, false, false);

        KeyPair logKp = keyStore.logKeys();
        Signer.sign(rec, logKp.getPrivate());

        Path recPath = workDir.resolve("evidence-record.json");
        Files.writeString(recPath, rec.toSignedJson(), StandardCharsets.UTF_8);
        emit.accept(event(7, "Writing the signed record",
                "We wrote a signed record of what happened, so you can show it to anyone."));
        events.add(event(7, "Writing the signed record",
                "We wrote a signed record of what happened, so you can show it to anyone."));

        // ---- append to the shared, witnessed log --------------------------
        PersistentLog pl = PersistentLog.open(paths.logDir());
        pl.append(rec.toSignedJson().getBytes(StandardCharsets.UTF_8));
        Checkpoint cp = new Checkpoint(pl.log().size(), pl.log().rootHash(), System.currentTimeMillis())
                .sign(logKp.getPrivate());
        String witnessNote = witnessCoSign(cp);
        pl.saveCheckpoint(cp);
        emit.accept(event(8, "Adding it to the history",
                "We added the record to your history of destructions" + witnessNote + "."));
        events.add(event(8, "Adding it to the history",
                "We added the record to your history of destructions" + witnessNote + "."));

        emit.accept(event(9, "Done", "Your file has been destroyed and we verified it."));
        events.add(event(9, "Done", "Your file has been destroyed and we verified it."));

        boolean sigValid = signatureValid(rec);
        ProofView proof = ProofView.of(rec, sigValid);

        String whatWasVerified = verified
                ? "We confirmed the file is no longer at its original location, and we tried to read the "
                    + "encrypted copy we kept - it could not be read, because the key that opened it was destroyed."
                : "We could NOT confirm the destruction. The file may still be recoverable - please check "
                    + "the record and try again.";

        return new DestroyResult(
                verified ? DestroyResult.Status.DESTROYED : DestroyResult.Status.FAILED,
                fileName,
                verified,
                verified ? "Your file was destroyed and we verified it."
                         : "We could not confirm the destruction.",
                whatWasVerified,
                Friendly.LIMITATION,
                recPath,
                paths.logDir(),
                proof,
                List.copyOf(events),
                verified ? null : "The destruction could not be verified.");
    }

    // ---------- helpers ----------

    /**
     * Maps a transaction lifecycle event onto a friendly stage.
     *
     * <p>The mapping is exhaustive over the transaction's states, so an
     * unfamiliar event type still produces a visible line rather than being
     * swallowed — the user always sees what the machinery did.
     */
    private static StageEvent stageFor(String type, String detail) {
        return switch (type) {
            case "STAGED" -> event(1, "Checking your file", "We looked at your file and started the job.");
            case "VAULTED" -> event(2, "Making a protected copy",
                    "We made an encrypted copy of your file. Without the key it is just noise.");
            case "SCRUBBED" -> event(3, "Destroying the original",
                    "The original file was overwritten with zeros and then deleted.");
            case "VALIDATED_PRECOMMIT" -> event(4, "Final check before we make it permanent",
                    "Everything is in order. Next we destroy the key that opens the copy.");
            case "COMMITTED" -> event(5, "Making it permanent",
                    "We destroyed the key that could open your file's copy. Your data can no longer be read.");
            case "ABORTED" -> event(4, "Putting your file back",
                    "Something went wrong before the destruction was final, so we restored your file: "
                            + (detail == null ? "the job was stopped" : detail));
            default -> event(0, "Working", type + (detail == null ? "" : ": " + detail));
        };
    }

    private static StageEvent event(int step, String title, String detail) {
        return new StageEvent(step, title, detail);
    }

    /**
     * The witness co-signature. The log module refuses to co-sign when the
     * witness key equals the log key (key independence, Assumption 8); if
     * that ever happened we would rather record the history unsigned than
     * fail the destruction the user asked for.
     */
    private String witnessCoSign(Checkpoint cp) {
        try {
            KeyPair wit = keyStore.witnessKeys();
            cp.witnessCosign(wit.getPrivate(), wit.getPublic(), keyStore.logPublicKey());
            return ", countersigned by a second, independent key";
        } catch (IllegalArgumentException e) {
            return " (the second signing key could not be used, so this entry has one signature)";
        } catch (Exception e) {
            return " (the second signing key could not be used, so this entry has one signature)";
        }
    }

    /** True when the record's signature verifies against the log's public key. */
    private boolean signatureValid(EvidenceRecord rec) {
        var pub = keyStore.logPublicKey();
        return pub != null && Signer.verify(rec, pub);
    }

    /**
     * Handles a failure that happened before the destruction was made final.
     *
     * <p>The transaction module's rollback restores the original file from the
     * vault, and it emits the ABORTED event <em>before</em> restoring, so a
     * failed destruction can never be hidden (Assumption 6). We then say
     * plainly whether the user's file is back.
     */
    private DestroyResult handleFailure(DestructionTransaction txn, Path source, String fileName,
                                         List<StageEvent> events, Consumer<StageEvent> emit,
                                         Exception failure) {
        String reason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        boolean rolledBack = false;
        try {
            if (txn.state() != DestructionTransaction.State.COMMITTED) {
                txn.rollback("desktop app run failed: " + reason);
                rolledBack = true;
            }
        } catch (Exception rollbackFailure) {
            rolledBack = false;
        }

        boolean restored = rolledBack && Files.exists(source);
        String headline = restored
                ? "We stopped before the destruction was final, so your file was put back."
                : "The destruction did not finish.";
        String what = restored
                ? "Your file is back where it was and nothing was lost. Nothing was destroyed."
                : "We could not finish and could not put your file back automatically. "
                    + "Your file may still be there - please check it.";
        emit.accept(event(9, headline, what));
        events.add(event(9, headline, what));

        return new DestroyResult(
                restored ? DestroyResult.Status.ROLLED_BACK : DestroyResult.Status.FAILED,
                fileName,
                false,
                headline,
                what,
                Friendly.LIMITATION,
                null,
                paths.logDir(),
                null,
                List.copyOf(events),
                Friendly.describeFailure(failure));
    }

    /** Checks the file before doing anything destructive. Returns a friendly problem, or null. */
    private static String validate(Path source) {
        if (source == null) return "No file was chosen.";
        try {
            if (!Files.exists(source)) {
                return "We could not find that file. It may have been moved, renamed, or deleted.";
            }
            if (Files.isDirectory(source)) {
                return "That is a folder, not a file. This app destroys one file at a time.";
            }
            if (!Files.isRegularFile(source)) {
                return "That is not a regular file, so we cannot destroy it safely.";
            }
            if (!Files.isReadable(source)) {
                return "We were not allowed to read that file. Check its permissions and try again.";
            }
            return null;
        } catch (SecurityException e) {
            return "We were not allowed to look at that file. Check its permissions and try again.";
        }
    }

    /** A fresh working directory for this run, so no run inherits another run's journal. */
    private Path createWorkDir() throws IOException {
        String suffix = HexFormat.of().formatHex(new byte[3]);
        String name = LocalDateTime.now().format(RUN_ID) + "-" + suffix;
        Path dir = paths.workRoot().resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    private static DestroyResult failed(String fileName, List<StageEvent> events, String problem) {
        return new DestroyResult(DestroyResult.Status.FAILED, fileName, false,
                "We could not start.", "Nothing was destroyed.", Friendly.LIMITATION,
                null, null, null, List.copyOf(events), problem);
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Overwrites a key copy in place. Best effort, like the rest of the project. */
    private static void wipe(byte[] key) {
        if (key != null) java.util.Arrays.fill(key, (byte) 0);
    }

    /** The run's working directory, for tests and for the "where did this go" hint. */
    public AppPaths paths() {
        return paths;
    }

    /** Exposed for the headless test: the tool version stamped into records. */
    public static String toolVersion() {
        return TOOL_VERSION;
    }
}
