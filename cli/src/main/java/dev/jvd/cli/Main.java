package dev.jvd.cli;

import dev.jvd.evidence.Assurance;
import dev.jvd.evidence.EvidenceRecord;
import dev.jvd.evidence.Signer;
import dev.jvd.log.Checkpoint;
import dev.jvd.log.MerkleLog;
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
 *   <li>{@code verify --log <dir>} — check a stored log against its checkpoint and keys.</li>
 *   <li>{@code recover --work <dir> --kek-hex <hex>} — recover an interrupted destruction.</li>
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
            out.println("usage: jvd <destroy-file|verify|recover|verify-memory|device|version>");
            return 2;
        }
        try {
            return switch (args[0]) {
                case "version" -> { out.println(VERSION); yield 0; }
                case "verify-memory" -> verifyMemory(args, out);
                case "destroy-file" -> destroyFile(args, out);
                case "verify" -> verifyLog(args, out);
                case "recover" -> recover(args, out);
                case "device" -> device(args, out);
                default -> { out.println("unknown subcommand: " + args[0]); yield 2; }
            };
        } catch (Exception e) {
            out.println("ERROR: " + e);
            return 1;
        }
    }

    /**
     * The device commands: what the drives on this machine can do, and whether the
     * data is still on the flash.
     *
     * <p>These are the commands that make the physical media identifiable. Everything
     * else in jvd reads a file; these read the device.
     */
    private static int device(String[] args, PrintStream out) throws Exception {
        String sub = args.length > 1 ? args[1] : "list";
        return switch (sub) {
            case "list" -> deviceList(out);
            case "scan" -> deviceScan(args, out);
            case "sanitize-status" -> deviceSanitizeStatus(args, out);
            case "sanitize" -> deviceSanitize(args, out);
            default -> {
                out.println("usage: jvd device <list|scan|sanitize-status|sanitize>");
                yield 2;
            }
        };
    }

    private static int deviceList(PrintStream out) {
        var controllers = dev.jvd.device.NvmeController.controllers();
        if (controllers.isEmpty()) {
            out.println("no NVMe controllers were found on this machine");
            return 2;
        }
        for (String c : controllers) {
            var id = dev.jvd.device.NvmeController.identify(c);
            out.println(c);
            out.println("  model      = " + id.modelNumber());
            out.println("  serial     = " + id.serialNumber());
            out.println("  firmware   = " + id.firmware());
            out.println("  namespace  = " + id.namespaceBytes() + " bytes");
            out.println("  sanitize   = " + id.strongestSupported()
                    + "  (blockErase=" + id.blockEraseSupported()
                    + " overwrite=" + id.overwriteSupported()
                    + " cryptoErase=" + id.cryptoEraseSupported() + ")");
        }
        return 0;
    }

    /**
     * Scans the raw media of a device for a canary.
     *
     * <p>The canary is given as hex. Reading a block device needs root, and without it
     * the command says so instead of reporting a clean result.
     */
    private static int deviceScan(String[] args, PrintStream out) throws Exception {
        Path device = null;
        String canaryHex = null;
        int tolerance = dev.jvd.device.RawMediaScanner.DEFAULT_TOLERANCE;
        // A namespace is hundreds of gigabytes. Reading all of it takes longer than
        // anybody will wait, so the scan is bounded by default and the bound is
        // reported in the output.
        long limit = 4L * 1024 * 1024 * 1024;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--device" -> device = Path.of(args[++i]);
                case "--canary" -> canaryHex = args[++i];
                case "--tolerance" -> tolerance = Integer.parseInt(args[++i]);
                case "--limit-gib" -> limit = Long.parseLong(args[++i]) * 1024L * 1024 * 1024;
                default -> { }
            }
        }
        if (device == null || canaryHex == null) {
            out.println("usage: jvd device scan --device /dev/nvme0n1 --canary <hex> "
                    + "[--tolerance N] [--limit-gib N]");
            return 2;
        }
        byte[] canary = HexFormat.of().parseHex(canaryHex.trim());
        var r = dev.jvd.device.RawMediaScanner.scan(device, canary, tolerance, limit);
        if (!r.checkable()) {
            out.println("NOT CHECKABLE: " + r.reason());
            return 2;
        }
        out.println("device   = " + device);
        out.println("scanned  = " + r.bytesScanned() + " bytes (" + r.sectorsScanned() + " sectors)");
        if (r.bytesScanned() < limit) {
            out.println("note     = the scan covered the first " + (r.bytesScanned() / (1024 * 1024 * 1024))
                    + " GiB of the device; a canary outside that range was not looked for");
        }
        out.println("tolerance= " + r.tolerance() + " byte(s)");
        out.println("canary   = " + (r.canaryFound()
                ? "FOUND on the media, " + r.matches() + " match(es)"
                : "not present on the media"));
        out.println("result   = " + (r.canaryFound()
                ? "the data is still on the flash"
                : "the data is not on the flash"));
        return r.canaryFound() ? 1 : 0;
    }

    private static int deviceSanitizeStatus(String[] args, PrintStream out) {
        String controller = null;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--controller")) controller = args[++i];
        }
        if (controller == null) {
            out.println("usage: jvd device sanitize-status --controller /dev/nvme0");
            return 2;
        }
        out.println(dev.jvd.device.Sanitizer.status(controller));
        return 0;
    }

    /**
     * Asks a controller to sanitize itself.
     *
     * <p>Needs the confirmation token, and refuses while the drive is mounted.
     */
    private static int deviceSanitize(String[] args, PrintStream out) throws Exception {
        String controller = null;
        String action = "block-erase";
        String confirm = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--controller" -> controller = args[++i];
                case "--action" -> action = args[++i];
                case "--confirm" -> confirm = args[++i];
                default -> { }
            }
        }
        if (controller == null) {
            out.println("usage: jvd device sanitize --controller /dev/nvme0 "
                    + "--action block-erase|crypto-erase|overwrite --confirm " 
                    + dev.jvd.device.Sanitizer.CONFIRM_TOKEN);
            return 2;
        }
        dev.jvd.device.Sanitizer.Action act;
        try {
            act = dev.jvd.device.Sanitizer.Action.valueOf(
                    action.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            out.println("unknown sanitize action: " + action);
            return 2;
        }
        var outcome = dev.jvd.device.Sanitizer.sanitize(controller, act, confirm);
        out.println("controller = " + controller);
        out.println("action     = " + act.label());
        out.println("started    = " + outcome.started());
        out.println("completed  = " + outcome.completed());
        if (outcome.reason() != null) out.println("reason     = " + outcome.reason());
        if (!outcome.output().isBlank()) out.println(outcome.output());
        return outcome.completed() ? 0 : 1;
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
        if (source == null || work == null || logDir == null) {
            out.println("usage: jvd destroy-file <path> [--kek-hex <hex>] --work <dir> --log <dir>");
            return 2;
        }
        // The wrapping key is not written to disk any more, so a transaction that
        // has to be recovered needs the key again. When the caller does not supply
        // one, a fresh key is generated for this run and printed, so the operator
        // can keep it. When they do supply one, it is used for every transaction in
        // that work directory, which is what makes a later recovery possible.
        byte[] kek;
        boolean callerSupplied = kekHex != null && !kekHex.isBlank();
        if (callerSupplied) {
            kek = HexFormat.of().parseHex(kekHex.trim());
        } else {
            kek = new byte[32];
            new java.security.SecureRandom().nextBytes(kek);
            out.println("key=" + HexFormat.of().formatHex(kek));
            out.println("keep this key: a transaction that has to be recovered needs it again.");
        }
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

    /**
     * Checks a stored log against its checkpoint and the keys.
     *
     * <p>This is the same verification the offline verifier performs, exposed here so a
     * single installation can both create evidence and check it. The exit code is 0 when
     * the log verifies and 1 when it does not, so a script can branch on it.
     */
    private static int verifyLog(String[] args, PrintStream out) throws Exception {
        Path logDir = null;
        String logKey = null, witKey = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--log" -> logDir = Path.of(args[++i]);
                case "--log-key" -> logKey = args[++i];
                case "--witness-key" -> witKey = args[++i];
                default -> { }
            }
        }
        if (logDir == null) {
            out.println("usage: jvd verify --log <dir> [--log-key <b64>] [--witness-key <b64>]");
            return 2;
        }
        PersistentLog pl = PersistentLog.open(logDir);
        Checkpoint cp = pl.loadCheckpoint();
        if (cp == null) {
            out.println("NOT CHECKABLE: no checkpoint found under " + logDir);
            return 2;
        }
        // The public keys sit beside the log, so the common case needs no flags at all.
        PublicKey logPub = logKey != null
                ? decodePublic(logKey)
                : loadPublic(logDir.resolve("log.pub"));
        PublicKey witPub = witKey != null
                ? decodePublic(witKey)
                : (Files.exists(logDir.resolve("witness.pub"))
                        ? loadPublic(logDir.resolve("witness.pub")) : null);

        boolean ok = true;
        var log = pl.log();
        if (cp.size != log.size()) {
            out.println("TAMPER DETECTED: checkpoint size " + cp.size + " != log size " + log.size()
                    + " (record deleted or added)");
            ok = false;
        } else if (!java.util.Arrays.equals(cp.root, log.rootHash())) {
            out.println("TAMPER DETECTED: checkpoint root does not match recomputed log root (record edited)");
            ok = false;
        }
        if (!cp.verifyLogSignature(logPub)) {
            out.println("TAMPER DETECTED: checkpoint log signature invalid");
            ok = false;
        }
        if (cp.witnessSignature != null) {
            if (witPub == null) {
                out.println("NOTE: checkpoint is witness-cosigned but no witness key given; cosignature not checked");
            } else if (!cp.verifyWitnessSignature(witPub)) {
                out.println("TAMPER DETECTED: witness cosignature invalid");
                ok = false;
            }
        }
        if (ok) {
            for (int i = 0; i < log.size(); i++) {
                if (!MerkleLog.verifyInclusion(log.leaf(i), i, log.size(), log.inclusionProof(i), cp.root)) {
                    out.println("TAMPER DETECTED: inclusion proof fails for leaf " + i);
                    ok = false;
                    break;
                }
            }
        }
        if (ok) {
            out.println("OK: log verified (size=" + log.size()
                    + ", root matches checkpoint, signatures valid, inclusion proofs valid)");
        } else {
            out.println("TAMPER DETECTED: the log does not match its checkpoint or its signatures");
        }
        return ok ? 0 : 1;
    }

    /**
     * Recovers an interrupted destruction.
     *
     * <p>The wrapping key is needed again because it is not stored in the work
     * directory. Supplying it is the whole point: the work directory alone must never
     * be enough to undo a destruction.
     */
    private static int recover(String[] args, PrintStream out) throws Exception {
        Path work = null;
        String kekHex = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--work" -> work = Path.of(args[++i]);
                case "--kek-hex" -> kekHex = args[++i];
                default -> { }
            }
        }
        if (work == null || kekHex == null) {
            out.println("usage: jvd recover --work <dir> --kek-hex <hex>");
            return 2;
        }
        DestructionTransaction.RecoveryResult rr =
                DestructionTransaction.recover(work, HexFormat.of().parseHex(kekHex.trim()));
        out.println("status=" + rr.status());
        out.println("restored=" + rr.restored());
        out.println("detail=" + rr.detail());
        return rr.restored() ? 0 : (rr.status() == null ? 2 : 1);
    }

    private static PublicKey decodePublic(String b64) throws Exception {
        return KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(b64.trim())));
    }

    private static PublicKey loadPublic(Path file) throws Exception {
        return decodePublic(Files.readString(file).strip());
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
