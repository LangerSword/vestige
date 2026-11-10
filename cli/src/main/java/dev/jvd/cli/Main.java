package dev.jvd.cli;

import dev.jvd.evidence.Assurance;
import dev.jvd.evidence.EvidenceRecord;
import dev.jvd.evidence.Signer;
import dev.jvd.log.Checkpoint;
import dev.jvd.log.PersistentLog;
import dev.jvd.txn.DestructionTransaction;
import dev.jvd.verify.CanaryVerifier;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * {@code jvd}: the operator CLI. Subcommands:
 * <ul>
 *   <li>{@code destroy-file <path> --kek-hex <hex> --work <dir> --log <dir>} —
 *       full crypto-shred transaction + signed evidence + witnessed log append.</li>
 *   <li>{@code verify-memory [--gc G1]} — canary verification protocol against a child victim JVM.</li>
 *   <li>{@code version}</li>
 * </ul>
 *
 * <p>Key management is demo-grade (Assumption: first use generates Ed25519
 * log/witness keypairs into the log dir as {@code log.key}/{@code log.pub} and
 * {@code witness.key}/{@code witness.pub}). Production custody belongs in a
 * KMS/HSM; what the code enforces regardless is log/witness key independence.
 *
 * <p>Honest scope of {@code destroy-file}: its evidence records the file
 * transaction, post-commit decrypt failure, and NOT_CHECKABLE for the memory
 * canary channel (not run by this command) — so its computed assurance is A0
 * by the PLAN §5 rules. Run {@code verify-memory} for memory assurance.
 */
public final class Main {

    public static final String VERSION = "jvd-0.1.0";

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    public static int run(String[] args, PrintStream out) {
        if (args.length == 0) {
            out.println("usage: jvd <destroy-file|verify-memory|version>");
            return 2;
        }
        try {
            return switch (args[0]) {
                case "version" -> { out.println(VERSION); yield 0; }
                case "verify-memory" -> verifyMemory(args, out);
                case "destroy-file" -> destroyFile(args, out);
                default -> { out.println("unknown subcommand: " + args[0]); yield 2; }
            };
        } catch (Exception e) {
            out.println("ERROR: " + e);
            return 1;
        }
    }

    private static int verifyMemory(String[] args, PrintStream out) {
        String gc = "G1";
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--gc") && i + 1 < args.length) gc = args[++i];
        }
        CanaryVerifier.VerificationOutcome o = new CanaryVerifier().run(gc, "WIPE");
        out.println("canaryResult=" + o.canaryResult());
        out.println("positiveControlOk=" + o.positiveControlOk());
        out.println("negativeControlOk=" + o.negativeControlOk());
        out.println("controlsValid=" + o.controlsValid());
        out.println("status=" + o.status());
        out.println("detail=" + o.detail());
        return o.status() == CanaryVerifier.Status.VALID ? 0 : 1;
    }

    private static int destroyFile(String[] args, PrintStream out) throws Exception {
        Path source = null, work = null, logDir = null;
        String kekHex = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--kek-hex" -> kekHex = args[++i];
                case "--work" -> work = Path.of(args[++i]);
                case "--log" -> logDir = Path.of(args[++i]);
                default -> { if (source == null) source = Path.of(args[i]); }
            }
        }
        if (source == null || kekHex == null || work == null || logDir == null) {
            out.println("usage: jvd destroy-file <path> --kek-hex <hex> --work <dir> --log <dir>");
            return 2;
        }
        byte[] kek = HexFormat.of().parseHex(kekHex);
        Files.createDirectories(logDir);
        KeyPair logKp = loadOrCreateKeys(logDir, "log");
        KeyPair witKp = loadOrCreateKeys(logDir, "witness");

        List<String> events = new ArrayList<>();
        DestructionTransaction txn = new DestructionTransaction(work, source, kek);
        txn.setEvidenceSink((t, d) -> events.add(t));
        txn.stage();
        txn.vault();
        var shred = txn.shredResult();
        byte[] iv = shred.iv.clone();
        txn.scrub();
        txn.validatePreCommit();
        txn.commit();

        // Post-commit validation evidence: decrypt with the transaction's own
        // (now destroyed) key record must fail.
        boolean decryptFailed;
        try {
            dev.jvd.file.CryptoShredder.decrypt(txn.artifact(), shred.wrappedDek, kek, iv);
            decryptFailed = false;
        } catch (Exception e) {
            decryptFailed = true;
        }

        EvidenceRecord rec = new EvidenceRecord();
        rec.toolVersion = VERSION;
        rec.subjectHash = EvidenceRecord.subjectHash("jvd-cli", source.toAbsolutePath().toString());
        rec.method = EvidenceRecord.Method.CRYPTO_SHRED;
        rec.verificationStatus = decryptFailed
                ? EvidenceRecord.VerificationStatus.COMPLETED : EvidenceRecord.VerificationStatus.FAILED;
        rec.canaryResult = EvidenceRecord.CanaryResult.NOT_CHECKABLE;
        rec.controlsValid = false;
        rec.verificationDetail = "txn events=" + events + "; post-commit decrypt attempt "
                + (decryptFailed ? "FAILED as required (key destroyed)" : "SUCCEEDED - shred incomplete!")
                + "; memory canary channel not run by destroy-file (NOT_CHECKABLE)";
        rec.validationDecision = EvidenceRecord.ValidationDecision.PENDING;
        rec.validationRationale = "validation is a separate decision (NIST Rev.2); left PENDING for a validator";
        rec.residualRisk = "physical media NOT verified; logical level only; caller-retained wrapped-DEK copies are outside the trust boundary";
        rec.assuranceLevel = Assurance.compute(rec.method, rec.controlsValid, rec.canaryResult, true, false, false);
        Signer.sign(rec, logKp.getPrivate());

        PersistentLog pl = PersistentLog.open(logDir);
        pl.append(rec.toSignedJson().getBytes(StandardCharsets.UTF_8));
        Checkpoint cp = new Checkpoint(pl.log().size(), pl.log().rootHash(), System.currentTimeMillis())
                .sign(logKp.getPrivate());
        cp.witnessCosign(witKp.getPrivate(), witKp.getPublic(), logKp.getPublic());
        pl.saveCheckpoint(cp);

        Path recPath = work.resolve("evidence-record.json");
        Files.writeString(recPath, rec.toSignedJson(), StandardCharsets.UTF_8);
        out.println("state=COMMITTED");
        out.println("artifact=" + txn.artifact());
        out.println("postCommitDecryptFailed=" + decryptFailed);
        out.println("assurance=" + rec.assuranceLevel + " (A0 here: no canary controls run by this command - see README)");
        out.println("record=" + recPath);
        out.println("logSize=" + pl.log().size());
        out.println("logPubkey=" + Base64.getEncoder().encodeToString(logKp.getPublic().getEncoded()));
        out.println("witnessPubkey=" + Base64.getEncoder().encodeToString(witKp.getPublic().getEncoded()));
        return decryptFailed ? 0 : 1;
    }

    /** Demo key management: generate on first use into the log dir, load afterwards. */
    private static KeyPair loadOrCreateKeys(Path dir, String name) throws Exception {
        Path priv = dir.resolve(name + ".key");
        Path pub = dir.resolve(name + ".pub");
        if (Files.exists(priv) && Files.exists(pub)) {
            PrivateKey pk = KeyFactory.getInstance("Ed25519").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(priv).strip())));
            PublicKey pubk = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(Files.readString(pub).strip())));
            return new KeyPair(pubk, pk);
        }
        KeyPair kp = Signer.generateKeyPair();
        Files.writeString(priv, Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        Files.writeString(pub, Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
        return kp;
    }
}
