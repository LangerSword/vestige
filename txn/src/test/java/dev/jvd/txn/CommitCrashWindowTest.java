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

/**
 * Tests for what the transaction guarantees when it is interrupted.
 *
 * <p>The interesting failure is the one that happens between the irreversible step
 * and the journal write. Destroying the key cannot be undone, so a crash in that
 * window used to leave the journal saying "not committed" for a transaction whose
 * key was already gone. Recovery then rolled back, failed to restore a file whose
 * key no longer existed, and reported ABORTED — a destroyed file reported as never
 * destroyed, with the original unrecoverable.
 *
 * <p>These tests reproduce that window by hand: the journal is put into exactly
 * the state a crash there would leave, and the shipped recovery is run.
 */
class CommitCrashWindowTest {

    @TempDir Path tmp;

    private static byte[] randomKek() {
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        return kek;
    }

    /**
     * A crash after the key was destroyed but before the COMMITTED row was
     * written must never be reported as a transaction that was rolled back.
     *
     * <p>Before the fix this reported ABORTED with the source still missing: the
     * tool said "nothing was destroyed" about a file it had destroyed.
     */
    @Test
    void crashAfterKeyDestructionIsReportedAsCommittedNotAborted() throws Exception {
        Path dir = tmp.resolve("crash-after-key");
        Files.createDirectories(dir);
        Path src = dir.resolve("victim.txt");
        byte[] content = "irreplaceable data".getBytes(StandardCharsets.UTF_8);
        Files.write(src, content);
        byte[] kek = randomKek();
        Path work = dir.resolve("work");

        // Run the real transaction up to the point where the key is about to go.
        DestructionTransaction txn = new DestructionTransaction(work, src, kek);
        txn.stage();
        txn.vault();
        txn.scrub();
        txn.validatePreCommit();
        CryptoShredder.ShredResult sr = txn.shredResult();
        byte[] iv = sr.iv.clone();

        // Now hand-simulate the crash: the key file is gone and the ciphertext has
        // been moved, but the journal has no COMMITTED row. This is exactly the
        // state a SIGKILL between destroyVaultKey() and journal(COMMITTED) leaves,
        // and commit() has already written the COMMITTING row before it started.
        Path keyFile = work.resolve("vault.key");
        Files.deleteIfExists(keyFile);
        Files.move(work.resolve("vault.enc"), work.resolve("artifact.enc"));
        Files.writeString(work.resolve("journal.log"),
                Files.readString(work.resolve("journal.log"))
                        + "4|COMMITTING|intent to destroy the wrapped DEK\n");

        DestructionTransaction.RecoveryResult rr = DestructionTransaction.recover(work, kek);

        assertEquals(DestructionTransaction.State.COMMITTED, rr.status(),
                "a transaction whose key was destroyed must recover as COMMITTED, never ABORTED");
        assertFalse(rr.restored(), "nothing can be restored once the key is gone");
        assertFalse(Files.exists(src), "the file is gone, and that must be reported honestly");
        assertTrue(rr.detail().contains("destruction stands"),
                "the detail must say the destruction stands: " + rr.detail());
    }

    /**
     * A crash before the key was destroyed can still be rolled back, because the
     * rollback material is still intact.
     */
    @Test
    void crashBeforeKeyDestructionIsRolledBackAndRestored() throws Exception {
        Path dir = tmp.resolve("crash-before-key");
        Files.createDirectories(dir);
        Path src = dir.resolve("victim.txt");
        byte[] content = "recoverable data".getBytes(StandardCharsets.UTF_8);
        Files.write(src, content);
        byte[] kek = randomKek();
        Path work = dir.resolve("work");

        DestructionTransaction txn = new DestructionTransaction(work, src, kek);
        txn.stage();
        txn.vault();
        txn.scrub();
        txn.validatePreCommit();

        // The COMMITTING row is written, then the process dies before the key
        // is destroyed. The key file is still there, so the rollback material is
        // intact and the original can be restored.
        Files.writeString(work.resolve("journal.log"),
                Files.readString(work.resolve("journal.log")) + "4|COMMITTING|intent to destroy the wrapped DEK\n");

        DestructionTransaction.RecoveryResult rr = DestructionTransaction.recover(work, kek);

        assertEquals(DestructionTransaction.State.ABORTED, rr.status(),
                "a crash before the key was destroyed is still recoverable");
        assertTrue(rr.restored(), "the original must be restored: " + rr.detail());
        assertTrue(Files.exists(src), "the file must be back");
        assertArrayEquals(content, Files.readAllBytes(src), "the restored bytes must be identical");
    }

    /**
     * The whole point of crypto-shredding is that the work directory alone must
     * not be enough to undo the destruction.
     */
    @Test
    void theWorkDirectoryDoesNotContainTheWrappingKey() throws Exception {
        Path dir = tmp.resolve("custody");
        Files.createDirectories(dir);
        Path src = dir.resolve("secret.txt");
        Files.writeString(src, "sensitive");
        byte[] kek = randomKek();
        Path work = dir.resolve("work");

        DestructionTransaction txn = new DestructionTransaction(work, src, kek);
        txn.stage();
        txn.vault();

        // Nothing in the work directory may contain the wrapping key.
        String keyHex = HexFormat.of().formatHex(kek);
        try (var walk = Files.walk(work)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                // The key is binary, so the file is scanned as bytes rather than
                // decoded as text: a binary container is not valid UTF-8.
                byte[] bytes = Files.readAllBytes(p);
                String asLatin1 = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(asLatin1.contains(keyHex),
                        "the wrapping key must not be written to " + p.getFileName());
            }
        }

        // And the metadata file holds only the source path.
        String meta = Files.readString(work.resolve("txn.meta"));
        assertFalse(meta.contains(keyHex), "txn.meta must not hold the wrapping key");
        assertTrue(meta.contains(src.toAbsolutePath().toString()),
                "txn.meta must still record the source path so a transaction can be found");
    }

    /**
     * With the key gone from the work directory, the ciphertext container is
     * unreadable even for somebody who can read every byte of the work directory.
     * That is what makes the shred a shred.
     */
    @Test
    void theCiphertextCannotBeReadWithoutTheWrappingKey() throws Exception {
        Path dir = tmp.resolve("unreadable");
        Files.createDirectories(dir);
        Path src = dir.resolve("secret.txt");
        byte[] content = "sensitive content".getBytes(StandardCharsets.UTF_8);
        Files.write(src, content);
        byte[] kek = randomKek();
        Path work = dir.resolve("work");

        DestructionTransaction txn = new DestructionTransaction(work, src, kek);
        txn.stage();
        txn.vault();
        CryptoShredder.ShredResult sr = txn.shredResult();
        byte[] iv = sr.iv.clone();
        Path keyFile = work.resolve("vault.key");
        // vault.key holds the IV followed by the wrapped DEK, so the wrapped key
        // starts after the IV rather than at the beginning of the file.
        byte[] keyBytes = Files.readAllBytes(keyFile);
        byte[] wrappedDek = java.util.Arrays.copyOfRange(keyBytes, iv.length, keyBytes.length);
        Path container = work.resolve("vault.enc");

        // Somebody who can read the work directory has the wrapped key and the
        // ciphertext, but not the wrapping key, so the container stays closed.
        // The wrong key cannot even unwrap the DEK, which is the first line of
        // defence: the wrapped key is useless without the KEK.
        assertThrows(Exception.class,
                () -> CryptoShredder.decrypt(container, wrappedDek, randomKek(), iv),
                "a different wrapping key must not open the container");

        // And with the right key it does open, which is what makes the shred real.
        assertArrayEquals(content,
                CryptoShredder.decrypt(container, wrappedDek, kek, iv),
                "the wrapping key must still open the container before the shred");
    }
}
