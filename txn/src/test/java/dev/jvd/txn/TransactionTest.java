package dev.jvd.txn;

import dev.jvd.file.CryptoShredder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {

    @TempDir Path tmp;

    private static byte[] randomKek() {
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        return kek;
    }

    @Test
    void happyPathCommit() throws Exception {
        Path src = tmp.resolve("data.txt");
        byte[] content = "destroy me properly".getBytes(StandardCharsets.UTF_8);
        Files.write(src, content);
        byte[] kek = randomKek();
        DestructionTransaction txn = new DestructionTransaction(tmp.resolve("work"), src, kek);
        txn.stage();
        txn.vault();
        CryptoShredder.ShredResult sr = txn.shredResult();
        byte[] wrappedCopy = sr.wrappedDek.clone();
        byte[] iv = sr.iv.clone();
        txn.scrub();
        assertFalse(Files.exists(src), "source scrubbed");
        txn.validatePreCommit();
        txn.commit();
        assertEquals(DestructionTransaction.State.COMMITTED, txn.state());
        assertFalse(Files.exists(src));
        assertTrue(Files.exists(txn.artifact()), "ciphertext artefact kept (crypto-shredding model)");
        assertFalse(Files.exists(tmp.resolve("work/vault.key")), "wrapped DEK destroyed at commit");
        // post-commit validation: decrypt must fail with the transaction's own
        // key record — commit wiped the in-memory wrapped DEK as well.
        assertThrows(Exception.class,
                () -> CryptoShredder.decrypt(txn.artifact(), sr.wrappedDek, kek, iv));
        // Honest caveat, asserted so it cannot be forgotten: a wrapped-DEK copy
        // retained by the CALLER outside the transaction would still decrypt.
        // Crypto-shredding destroys the copies inside the trust boundary;
        // custody of external copies is the caller's responsibility.
        assertArrayEquals(content, CryptoShredder.decrypt(txn.artifact(), wrappedCopy, kek, iv));
    }

    @Test
    void rollbackRestoresAndEmitsAbortedBeforeRestore() throws Exception {
        Path src = tmp.resolve("keepme.txt");
        byte[] content = "precious data".getBytes(StandardCharsets.UTF_8);
        Files.write(src, content);
        byte[] kek = randomKek();
        DestructionTransaction txn = new DestructionTransaction(tmp.resolve("work2"), src, kek);
        List<String> events = new ArrayList<>();
        boolean[] sourceExistedAtAborted = {true};
        txn.setEvidenceSink((type, detail) -> {
            events.add(type);
            if (type.equals("ABORTED")) sourceExistedAtAborted[0] = Files.exists(src);
        });
        txn.stage();
        txn.vault();
        txn.scrub();
        assertFalse(Files.exists(src));
        txn.rollback("pre-commit validation failed (test)");
        // ABORTED was emitted while the source was still absent (i.e. BEFORE restore)
        assertEquals(List.of("STAGED", "VAULTED", "SCRUBBED", "ABORTED"), events);
        assertFalse(sourceExistedAtAborted[0], "ABORTED must be emitted before restore executes");
        assertTrue(Files.exists(src), "rollback restored the source");
        assertArrayEquals(content, Files.readAllBytes(src));
        assertEquals(DestructionTransaction.State.ABORTED, txn.state());
    }

    @Test
    void rollbackAfterCommitRefused() throws Exception {
        Path src = tmp.resolve("gone.txt");
        Files.writeString(src, "x");
        DestructionTransaction txn = new DestructionTransaction(tmp.resolve("work3"), src, randomKek());
        txn.stage(); txn.vault(); txn.scrub(); txn.validatePreCommit(); txn.commit();
        assertThrows(IllegalStateException.class, () -> txn.rollback("too late"));
    }

    @Test
    void kill9AtEveryStateRecovers() throws Exception {
        for (String stop : List.of("STAGED", "VAULTED", "SCRUBBED", "VALIDATED_PRECOMMIT")) {
            Path dir = tmp.resolve("crash-" + stop);
            Files.createDirectories(dir);
            Path src = dir.resolve("victim.txt");
            byte[] content = ("crash test " + stop).getBytes(StandardCharsets.UTF_8);
            Files.write(src, content);
            byte[] kek = randomKek();
            Path work = dir.resolve("work");
            int exit = runCrashMain(work, stop, src, kek);
            assertEquals(137, exit, "child must die by halt(137) at " + stop);
            DestructionTransaction.RecoveryResult rr = DestructionTransaction.recover(work, kek);
            assertEquals(DestructionTransaction.State.ABORTED, rr.status(),
                    "crashed at " + stop + " must recover as ABORTED, never destroyed");
            assertTrue(Files.exists(src), "no data loss after crash at " + stop);
            assertArrayEquals(content, Files.readAllBytes(src), "restored bytes identical after " + stop);
        }
    }

    @Test
    void kill9AfterCommitStaysCommitted() throws Exception {
        Path dir = tmp.resolve("crash-commit");
        Files.createDirectories(dir);
        Path src = dir.resolve("victim.txt");
        Files.writeString(src, "committed then killed");
        byte[] kek = randomKek();
        Path work = dir.resolve("work");
        int exit = runCrashMain(work, "COMMITTED", src, kek);
        assertEquals(137, exit);
        DestructionTransaction.RecoveryResult rr = DestructionTransaction.recover(work, kek);
        assertEquals(DestructionTransaction.State.COMMITTED, rr.status());
        assertFalse(rr.restored());
        assertFalse(Files.exists(src), "committed destruction is never restored");
        assertTrue(Files.exists(work.resolve("artifact.enc")));
    }

    private int runCrashMain(Path work, String stop, Path src, byte[] kek) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", crashClasspath(),
                CrashMain.class.getName(), work.toString(), stop, src.toString(),
                HexFormat.of().formatHex(kek));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        boolean done = p.waitFor(60, TimeUnit.SECONDS);
        assertTrue(done, "CrashMain did not exit; output: " + out);
        return p.exitValue();
    }

    /** Classpath containing both the txn classes and the destroy-file classes (for the child JVM). */
    private static String crashClasspath() throws Exception {
        URL txnLoc = DestructionTransaction.class.getProtectionDomain().getCodeSource().getLocation();
        URL fileLoc = CryptoShredder.class.getProtectionDomain().getCodeSource().getLocation();
        return Path.of(txnLoc.toURI()) + java.io.File.pathSeparator + Path.of(fileLoc.toURI());
    }
}
