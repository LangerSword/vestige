package dev.jvd.verifycli;

import dev.jvd.evidence.EvidenceRecord;
import dev.jvd.evidence.Signer;
import dev.jvd.log.Checkpoint;
import dev.jvd.log.MerkleLog;
import dev.jvd.log.PersistentLog;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * {@code jvd-verify}: the offline evidence verifier. It trusts nothing the
 * operator's tool printed — it recomputes the Merkle root from the stored
 * leaves, checks checkpoint signatures and the witness co-signature, verifies
 * every leaf's inclusion proof, checks record-timestamp monotonicity, and
 * compares a second checkpoint for fork detection.
 *
 * <p>Detects: edited records (signature / root mismatch), deleted records
 * (size/root mismatch vs checkpoint), backdated records (non-monotonic
 * timestamps — the strongest honest offline version; Assumption 9), forked
 * logs (two checkpoints, same size, different roots; or a prefix root that
 * contradicts a smaller checkpoint).
 *
 * <p>Exit codes: 0 = OK, 1 = TAMPER DETECTED, 2 = usage / not checkable.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    public static int run(String[] args, PrintStream out) {
        String logDir = null, checkpointFile = null, checkpoint2File = null;
        String pubkeyB64 = null, witnessPubkeyB64 = null, recordFile = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--log" -> logDir = args[++i];
                case "--checkpoint" -> checkpointFile = args[++i];
                case "--checkpoint2" -> checkpoint2File = args[++i];
                case "--pubkey" -> pubkeyB64 = args[++i];
                case "--witness-pubkey" -> witnessPubkeyB64 = args[++i];
                case "--record" -> recordFile = args[++i];
                default -> { out.println("unknown argument: " + args[i]); return 2; }
            }
        }
        try {
            if (recordFile != null) {
                if (pubkeyB64 == null) { out.println("NOT CHECKABLE: --record needs --pubkey"); return 2; }
                return verifyRecord(Path.of(recordFile), loadKey(pubkeyB64), out);
            }
            if (logDir == null) {
                out.println("usage: jvd-verify --log <dir> --checkpoint <file> --pubkey <b64> [--witness-pubkey <b64>] [--checkpoint2 <file>]");
                out.println("       jvd-verify --record <file> --pubkey <b64>");
                return 2;
            }
            if (pubkeyB64 == null) { out.println("NOT CHECKABLE: --pubkey is required"); return 2; }
            PublicKey logKey = loadKey(pubkeyB64);
            PublicKey witnessKey = witnessPubkeyB64 == null ? null : loadKey(witnessPubkeyB64);

            PersistentLog pl = PersistentLog.open(Path.of(logDir));
            MerkleLog log = pl.log();
            Checkpoint cp = checkpointFile != null
                    ? Checkpoint.parse(Files.readString(Path.of(checkpointFile), StandardCharsets.UTF_8))
                    : pl.loadCheckpoint();
            if (cp == null) { out.println("NOT CHECKABLE: no checkpoint found"); return 2; }

            boolean ok = true;
            // 1. Log size + root vs checkpoint (catches edited/deleted leaves)
            if (cp.size != log.size()) {
                out.println("TAMPER DETECTED: checkpoint size " + cp.size + " != log size " + log.size() + " (record deleted or added)");
                ok = false;
            } else if (!java.util.Arrays.equals(cp.root, log.rootHash())) {
                out.println("TAMPER DETECTED: checkpoint root does not match recomputed log root (record edited)");
                ok = false;
            }
            // 2. Checkpoint signature (+ witness)
            if (!cp.verifyLogSignature(logKey)) {
                out.println("TAMPER DETECTED: checkpoint log signature invalid");
                ok = false;
            }
            if (cp.witnessSignature != null) {
                if (witnessKey == null) {
                    out.println("NOTE: checkpoint is witness-cosigned but no --witness-pubkey given; cosignature not checked");
                } else if (!cp.verifyWitnessSignature(witnessKey)) {
                    out.println("TAMPER DETECTED: witness cosignature invalid");
                    ok = false;
                }
            }
            // 3. Inclusion proof for every leaf against the checkpoint root
            if (ok) {
                for (int i = 0; i < log.size(); i++) {
                    if (!MerkleLog.verifyInclusion(log.leaf(i), i, log.size(), log.inclusionProof(i), cp.root)) {
                        out.println("TAMPER DETECTED: inclusion proof fails for leaf " + i);
                        ok = false;
                        break;
                    }
                }
            }
            // 4. Timestamp monotonicity of records (backdating check, Assumption 9)
            Instant prev = null;
            for (int i = 0; i < log.size(); i++) {
                EvidenceRecord rec;
                try {
                    rec = EvidenceRecord.parse(new String(log.leaf(i), StandardCharsets.UTF_8));
                } catch (Exception e) {
                    continue; // non-record leaf: nothing to check here
                }
                if (prev != null && rec.timestampUtc.isBefore(prev)) {
                    out.println("TAMPER DETECTED: record " + i + " timestamp " + rec.timestampUtc
                            + " is before previous record " + prev + " (backdated or reordered)");
                    ok = false;
                    break;
                }
                prev = rec.timestampUtc;
            }
            // 5. Fork check against a second checkpoint
            if (checkpoint2File != null) {
                Checkpoint cp2 = Checkpoint.parse(Files.readString(Path.of(checkpoint2File), StandardCharsets.UTF_8));
                if (!cp2.verifyLogSignature(logKey)) {
                    out.println("TAMPER DETECTED: second checkpoint signature invalid");
                    ok = false;
                } else if (cp2.size == cp.size && !java.util.Arrays.equals(cp2.root, cp.root)) {
                    out.println("FORK DETECTED: two checkpoints for size " + cp.size + " with different roots");
                    ok = false;
                } else if (cp2.size != cp.size) {
                    Checkpoint small = cp2.size < cp.size ? cp2 : cp;
                    Checkpoint large = cp2.size < cp.size ? cp : cp2;
                    if (large.size <= log.size()) {
                        byte[] prefixRoot = log.rootHashForPrefix((int) small.size);
                        if (java.util.Arrays.equals(log.rootHashForPrefix((int) large.size), large.root)
                                && !java.util.Arrays.equals(prefixRoot, small.root)) {
                            out.println("FORK DETECTED: log at size " + large.size
                                    + " does not extend the checkpoint at size " + small.size);
                            ok = false;
                        }
                    }
                }
            }
            if (ok) {
                out.println("OK: log verified (size=" + log.size() + ", root matches checkpoint, signatures valid, "
                        + "inclusion proofs valid, timestamps monotonic)");
                return 0;
            }
            return 1;
        } catch (Exception e) {
            out.println("NOT CHECKABLE: " + e);
            return 2;
        }
    }

    private static int verifyRecord(Path file, PublicKey key, PrintStream out) throws Exception {
        EvidenceRecord rec = EvidenceRecord.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (Signer.verify(rec, key)) {
            out.println("OK: record signature valid (eventId=" + rec.eventId + ")");
            return 0;
        }
        out.println("TAMPER DETECTED: record signature invalid (record edited after signing, or wrong key)");
        return 1;
    }

    static PublicKey loadKey(String b64) throws Exception {
        byte[] der = Base64.getDecoder().decode(b64);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    static String keyToB64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
}
