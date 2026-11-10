package dev.jvd.verifycli;

import dev.jvd.evidence.EvidenceRecord;
import dev.jvd.evidence.Signer;
import dev.jvd.log.Checkpoint;
import dev.jvd.log.PersistentLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VerifierCliTest {

    @TempDir Path tmp;

    private KeyPair logKp;
    private KeyPair witKp;
    private Path logDir;
    private Path checkpointFile;

    /** Builds a 3-record signed log with a witness-cosigned checkpoint. Timestamps t0 < t1 < t2. */
    private void buildLog(Instant... times) throws Exception {
        logKp = Signer.generateKeyPair();
        witKp = Signer.generateKeyPair();
        logDir = tmp.resolve("log");
        PersistentLog pl = PersistentLog.open(logDir);
        for (Instant t : times) {
            EvidenceRecord r = new EvidenceRecord();
            r.timestampUtc = t;
            r.subjectHash = EvidenceRecord.subjectHash("salt", "subject-" + t);
            Signer.sign(r, logKp.getPrivate());
            pl.append(r.toSignedJson().getBytes(StandardCharsets.UTF_8));
        }
        Checkpoint cp = new Checkpoint(pl.log().size(), pl.log().rootHash(), System.currentTimeMillis())
                .sign(logKp.getPrivate());
        cp.witnessCosign(witKp.getPrivate(), witKp.getPublic(), logKp.getPublic());
        pl.saveCheckpoint(cp);
        checkpointFile = logDir.resolve(PersistentLog.CHECKPOINT_FILE);
    }

    private String pub(KeyPair kp) {
        return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    private int runVerify(String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--log", logDir.toString(),
                "--checkpoint", checkpointFile.toString(),
                "--pubkey", pub(logKp),
                "--witness-pubkey", pub(witKp)));
        args.addAll(List.of(extra));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int code = Main.run(args.toArray(new String[0]), new PrintStream(bos));
        System.out.println(bos);
        return code;
    }

    private static final Instant T0 = Instant.parse("2026-11-10T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-10T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-10T02:00:00Z");

    @Test
    void cleanLogVerifies() throws Exception {
        buildLog(T0, T1, T2);
        assertEquals(0, runVerify());
    }

    @Test
    void editedRecordDetected() throws Exception {
        buildLog(T0, T1, T2);
        Path leaves = logDir.resolve(PersistentLog.LEAVES_FILE);
        List<String> lines = Files.readAllLines(leaves);
        String json = new String(Base64.getDecoder().decode(lines.get(1)), StandardCharsets.UTF_8);
        String edited = json.replace("PENDING", "ACCEPTED");
        assertNotEquals(json, edited);
        lines.set(1, Base64.getEncoder().encodeToString(edited.getBytes(StandardCharsets.UTF_8)));
        Files.write(leaves, lines);
        assertEquals(1, runVerify());
    }

    @Test
    void deletedLeafDetected() throws Exception {
        buildLog(T0, T1, T2);
        Path leaves = logDir.resolve(PersistentLog.LEAVES_FILE);
        List<String> lines = Files.readAllLines(leaves);
        lines.remove(1);
        Files.write(leaves, lines);
        assertEquals(1, runVerify());
    }

    @Test
    void backdatedRecordDetected() throws Exception {
        // Records signed and appended in order, but the third is backdated.
        // Root and signatures all match the checkpoint: only monotonicity catches it.
        buildLog(T0, T2, T1);
        assertEquals(1, runVerify());
    }

    @Test
    void forkedCheckpointsDetected() throws Exception {
        buildLog(T0, T1, T2);
        // Build a fork: same first two records, different third, same size.
        Path forkDir = tmp.resolve("forklog");
        PersistentLog fork = PersistentLog.open(forkDir);
        PersistentLog orig = PersistentLog.open(logDir);
        fork.append(orig.log().leaf(0));
        fork.append(orig.log().leaf(1));
        EvidenceRecord evil = new EvidenceRecord();
        evil.timestampUtc = T2;
        evil.subjectHash = EvidenceRecord.subjectHash("salt", "forked-subject");
        Signer.sign(evil, logKp.getPrivate());
        fork.append(evil.toSignedJson().getBytes(StandardCharsets.UTF_8));
        Checkpoint forkCp = new Checkpoint(fork.log().size(), fork.log().rootHash(), System.currentTimeMillis())
                .sign(logKp.getPrivate());
        Path forkCpFile = tmp.resolve("fork-checkpoint.txt");
        Files.writeString(forkCpFile, forkCp.toText());
        assertEquals(1, runVerify("--checkpoint2", forkCpFile.toString()));
    }

    @Test
    void singleRecordMode() throws Exception {
        KeyPair kp = Signer.generateKeyPair();
        EvidenceRecord r = new EvidenceRecord();
        Signer.sign(r, kp.getPrivate());
        Path f = tmp.resolve("record.json");
        Files.writeString(f, r.toSignedJson());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{"--record", f.toString(), "--pubkey",
                Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())}, new PrintStream(bos)));

        EvidenceRecord tampered = EvidenceRecord.parse(r.toSignedJson());
        tampered.verificationDetail = "forged";
        Files.writeString(f, tampered.toSignedJson());
        assertEquals(1, Main.run(new String[]{"--record", f.toString(), "--pubkey",
                Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())}, new PrintStream(new ByteArrayOutputStream())));
    }
}
