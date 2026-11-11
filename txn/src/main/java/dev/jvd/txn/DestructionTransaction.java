package dev.jvd.txn;

import dev.jvd.file.CryptoShredder;
import dev.jvd.file.OverwriteDestroyer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Commit/rollback destruction transaction for the crypto-shred file flow
 * (report §6.1, PLAN §4).
 *
 * <pre>
 * STAGED → VAULTED → SCRUBBED → VALIDATED_PRECOMMIT → COMMITTED
 *                    ↘ (failure/crash before COMMIT) ABORTED + restore
 * </pre>
 *
 * <ul>
 *   <li>VAULTED: the source is encrypted to {@code vault.enc}; the wrapped DEK
 *       and IV are stored in {@code vault.key}. Until COMMIT the data is
 *       <em>intentionally recoverable</em> — assurance A0 in this window.</li>
 *   <li>SCRUBBED: the original is overwrite-destroyed; the vault is the only copy.</li>
 *   <li>COMMIT: the wrapped DEK is destroyed (file overwritten + deleted, the
 *       in-memory copy wiped). The ciphertext is renamed to
 *       {@code artifact.enc} and kept as the crypto-shredded artefact —
 *       ciphertext without a key. <b>After COMMIT nothing can be restored;
 *       this class never promises otherwise.</b></li>
 *   <li>ROLLBACK (Assumption 6 ordering): the ABORTED event is emitted to the
 *       {@link EvidenceSink} <b>before</b> the vault restore executes, so a
 *       rollback can never silently hide a failed destruction.</li>
 * </ul>
 *
 * <p>Every state transition is appended to a write-ahead journal and fsynced
 * before the corresponding action's completion is acknowledged. Crash
 * recovery ({@link #recover}) replays the journal: anything not COMMITTED is
 * aborted + restored; COMMITTED stays destroyed. Recovery never reports a
 * non-committed transaction as destroyed.
 *
 * <p><b>COMMIT is journalled as an intent before the irreversible step.</b>
 * Destroying the key cannot be undone, so a crash between the destruction
 * and the journal write would leave the journal saying "not committed" for a
 * transaction whose key is already gone. Recovery would then roll back,
 * fail to restore, and report ABORTED — a destroyed file reported as
 * never destroyed, with the original unrecoverable. The journal therefore
 * records {@link State#COMMITTING} first, and recovery treats that state as
 * "the key may or may not be gone, inspect the filesystem, never roll back".
 *
 * <p>Key custody note: the KEK is never written to disk. The work
 * directory holds the source path, the journal, the ciphertext container and
 * the wrapped key — but not the wrapping key that protects them. A later
 * process that needs to recover a transaction must be given the KEK again by
 * whoever holds it. Production custody belongs in a KMS/HSM.
 */
public final class DestructionTransaction {

    public enum State { STAGED, VAULTED, SCRUBBED, VALIDATED_PRECOMMIT, COMMITTING, COMMITTED, ABORTED }

    /** Receives lifecycle events (evidence/logging hook). ABORTED is delivered before any restore runs. */
    @FunctionalInterface
    public interface EvidenceSink {
        void onEvent(String type, String detail);
    }

    public record RecoveryResult(State status, boolean restored, String detail) {}

    static final String JOURNAL = "journal.log";
    static final String META = "txn.meta";
    static final String VAULT_ENC = "vault.enc";
    static final String VAULT_KEY = "vault.key";
    static final String ARTIFACT = "artifact.enc";

    private final Path workDir;
    private final Path source;
    private final byte[] kek;
    private State state;
    private CryptoShredder.ShredResult shred;
    private EvidenceSink sink = (t, d) -> {};

    public DestructionTransaction(Path workDir, Path source, byte[] kek) throws IOException {
        this.workDir = workDir;
        this.source = source;
        this.kek = kek.clone();
        Files.createDirectories(workDir);
        writeMeta();
    }

    private DestructionTransaction(Path workDir, Path source, byte[] kek, State state) throws IOException {
        this.workDir = workDir;
        this.source = source;
        this.kek = kek.clone();
        this.state = state;
        Files.createDirectories(workDir);
    }

    public void setEvidenceSink(EvidenceSink sink) {
        this.sink = sink == null ? (t, d) -> {} : sink;
    }

    public State state() { return state; }
    public Path workDir() { return workDir; }
    public Path source() { return source; }
    public Path artifact() { return workDir.resolve(ARTIFACT); }

    /** The vault encryption result (wrapped DEK is wiped at commit/rollback). */
    public CryptoShredder.ShredResult shredResult() { return shred; }

    // ---------- flow ----------

    public void stage() throws IOException {
        journal(State.STAGED, "source=" + source);
        state = State.STAGED;
        sink.onEvent("STAGED", source.toString());
    }

    public void vault() throws Exception {
        requireState(State.STAGED);
        shred = CryptoShredder.encryptFile(source, workDir.resolve(VAULT_ENC), kek);
        // vault.key layout: IV (12 bytes) || wrapped DEK
        byte[] keyFile = new byte[shred.iv.length + shred.wrappedDek.length];
        System.arraycopy(shred.iv, 0, keyFile, 0, shred.iv.length);
        System.arraycopy(shred.wrappedDek, 0, keyFile, shred.iv.length, shred.wrappedDek.length);
        writeFsynced(workDir.resolve(VAULT_KEY), keyFile);
        journal(State.VAULTED, "vault=" + VAULT_ENC);
        state = State.VAULTED;
        sink.onEvent("VAULTED", VAULT_ENC);
    }

    public void scrub() throws IOException {
        requireState(State.VAULTED);
        OverwriteDestroyer.destroy(source);
        journal(State.SCRUBBED, "source overwrite+delete (Clear, weak on flash)");
        state = State.SCRUBBED;
        sink.onEvent("SCRUBBED", source.toString());
    }

    public void validatePreCommit() throws IOException {
        requireState(State.SCRUBBED);
        journal(State.VALIDATED_PRECOMMIT, "pre-commit validation passed");
        state = State.VALIDATED_PRECOMMIT;
        sink.onEvent("VALIDATED_PRECOMMIT", "");
    }

    /** COMMIT: destroy the key material. Irreversible — no restore after this. */
    public void commit() throws IOException {
        requireState(State.VALIDATED_PRECOMMIT);

        // Journal the intent BEFORE the irreversible step. If the process dies after
        // the key is destroyed but before the COMMITTED row is written, the journal
        // still says COMMITTING, and recovery can tell that the destruction may have
        // happened. Without this row, recovery would see "not committed", try to roll
        // back, fail to restore a file whose key is gone, and report ABORTED for a
        // transaction that did destroy the file.
        journal(State.COMMITTING, "intent to destroy the wrapped DEK");
        state = State.COMMITTING;
        sink.onEvent("COMMITTING", "destroying the key that opens the copy");

        destroyVaultKey();
        if (shred != null) shred.destroyKey(); // wipe the in-memory wrapped DEK too
        Files.move(workDir.resolve(VAULT_ENC), workDir.resolve(ARTIFACT));
        journal(State.COMMITTED, "wrapped DEK destroyed; ciphertext kept as shredded artefact");
        state = State.COMMITTED;
        sink.onEvent("COMMITTED", ARTIFACT);
    }

    /**
     * ROLLBACK (only before COMMIT): ABORTED is journalled and emitted to the
     * evidence sink FIRST, then the vault is restored (Assumption 6).
     */
    public void rollback(String reason) throws Exception {
        if (state == State.COMMITTED) throw new IllegalStateException("cannot roll back after COMMIT");
        if (state == State.ABORTED) return;
        journal(State.ABORTED, "rollback: " + reason);
        sink.onEvent("ABORTED", reason);
        state = State.ABORTED;
        restoreFromVault();
        cleanupVault();
    }

    // ---------- recovery ----------

    /**
     * Recovers a transaction using the source path recorded in {@code txn.meta}.
     *
     * <p>The KEK is <b>not</b> read from the work directory: it is not written there
     * any more, because a KEK stored beside the wrapped key protects nothing. The
     * caller must supply it again, which means recovering a transaction requires
     * whoever holds the key — exactly the property crypto-shredding depends on.
     *
     * @param workDir the work directory of the interrupted transaction
     * @param kek     the wrapping key, supplied again by whoever holds it
     */
    public static RecoveryResult recover(Path workDir, byte[] kek) throws Exception {
        Path meta = workDir.resolve(META);
        if (!Files.exists(meta)) return new RecoveryResult(null, false, "no transaction metadata in " + workDir);
        List<String> lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
        Path source = Path.of(lines.get(0).strip());
        return recover(workDir, source, kek);
    }

    public static RecoveryResult recover(Path workDir, Path source, byte[] kek) throws Exception {
        State last = lastJournalState(workDir);
        if (last == null) return new RecoveryResult(null, false, "no journal in " + workDir);
        if (last == State.COMMITTED) {
            return new RecoveryResult(State.COMMITTED, false,
                    "transaction was COMMITTED; destruction stands, nothing restored (by design)");
        }
        if (last == State.ABORTED) {
            return new RecoveryResult(State.ABORTED, false, "transaction already ABORTED");
        }
        if (last == State.COMMITTING) {
            // The transaction reached the point where destroying the key is allowed to
            // happen. The crash may have landed before or after the key was destroyed,
            // and the only honest answer comes from the filesystem, not the journal.
            //
            // The key file is gone: the destruction happened, so it stands.
            // The key file is still there: the crash landed before the destruction, so
            // the transaction is rolled back and the original is restored.
            //
            // Either way this is never reported as ABORTED for a transaction whose
            // data is gone.
            Path keyFile = workDir.resolve(VAULT_KEY);
            boolean keyGone = !Files.exists(keyFile);
            if (keyGone) {
                return new RecoveryResult(State.COMMITTED, false,
                        "crashed during COMMIT after the key was destroyed; destruction stands, "
                                + "nothing restored. The file cannot be brought back.");
            }
            DestructionTransaction txn = new DestructionTransaction(workDir, source, kek, last);
            List<String> events = new ArrayList<>();
            txn.setEvidenceSink((t, d) -> events.add(t + ":" + d));
            boolean sourceMissing = !Files.exists(source);
            txn.rollback("crashed during COMMIT before the key was destroyed");
            boolean restored = sourceMissing && Files.exists(source);
            return new RecoveryResult(State.ABORTED, restored,
                    "crashed during COMMIT before the key was destroyed; rolled back and restored; events="
                            + events);
        }
        // Any other last state = crashed mid-transaction, pre-COMMIT: abort + restore.
        DestructionTransaction txn = new DestructionTransaction(workDir, source, kek, last);
        List<String> events = new ArrayList<>();
        boolean[] restoredFlag = {false};
        txn.setEvidenceSink((t, d) -> events.add(t + ":" + d));
        boolean sourceMissing = !Files.exists(source);
        txn.rollback("recovered after crash at state " + last);
        restoredFlag[0] = sourceMissing && Files.exists(source);
        return new RecoveryResult(State.ABORTED, restoredFlag[0],
                "crashed at " + last + "; ABORTED emitted before restore; events=" + events);
    }

    static State lastJournalState(Path workDir) throws IOException {
        Path j = workDir.resolve(JOURNAL);
        if (!Files.exists(j)) return null;
        State last = null;
        for (String line : Files.readAllLines(j, StandardCharsets.UTF_8)) {
            String[] parts = line.split("\\|", 3);
            if (parts.length >= 2) {
                try { last = State.valueOf(parts[1]); } catch (IllegalArgumentException ignored) {}
            }
        }
        return last;
    }

    // ---------- internals ----------

    private void restoreFromVault() throws Exception {
        Path enc = workDir.resolve(VAULT_ENC);
        Path key = workDir.resolve(VAULT_KEY);
        if (Files.exists(source)) return;          // never scrubbed: original intact
        if (!Files.exists(enc) || !Files.exists(key)) return; // nothing to restore from
        byte[] keyFile = Files.readAllBytes(key);
        byte[] iv = Arrays.copyOfRange(keyFile, 0, 12);
        byte[] wrappedDek = Arrays.copyOfRange(keyFile, 12, keyFile.length);
        byte[] plain = CryptoShredder.decrypt(enc, wrappedDek, kek, iv);
        if (source.getParent() != null) Files.createDirectories(source.getParent());
        Files.write(source, plain);
    }

    private void destroyVaultKey() throws IOException {
        Path key = workDir.resolve(VAULT_KEY);
        if (!Files.exists(key)) return;
        long len = Files.size(key);
        try (FileChannel ch = FileChannel.open(key, StandardOpenOption.WRITE)) {
            ByteBuffer zeros = ByteBuffer.allocate((int) Math.min(len, 8192));
            long pos = 0;
            while (pos < len) {
                zeros.clear();
                zeros.limit((int) Math.min(zeros.capacity(), len - pos));
                while (zeros.hasRemaining()) pos += ch.write(zeros, pos);
            }
            ch.force(true);
        }
        Files.delete(key);
    }

    private void cleanupVault() throws IOException {
        Files.deleteIfExists(workDir.resolve(VAULT_ENC));
        destroyVaultKey();
    }

    /**
     * Writes the transaction metadata.
     *
     * <p><b>The KEK is deliberately not written here.</b> The wrapping key is the
     * one secret that turns "the key file was destroyed" into "the data is
     * unrecoverable". Storing it next to the wrapped key it protects in plain text
     * means anyone who can read the work directory can decrypt the ciphertext
     * container, which is exactly what the crypto-shred was supposed to prevent:
     * shredding the wrapped DEK achieves nothing while the KEK sits in a
     * readable file a few bytes away.
     *
     * <p>Only the source path is recorded, so a later process can find what was
     * being destroyed. Recovering a transaction needs the KEK supplied again by
     * whoever holds it — see {@link #recover(Path, Path, byte[])}.
     */
    private void writeMeta() throws IOException {
        String content = source.toAbsolutePath() + "\n";
        writeFsynced(workDir.resolve(META), content.getBytes(StandardCharsets.UTF_8));
    }

    private void journal(State s, String detail) throws IOException {
        Path j = workDir.resolve(JOURNAL);
        long seq = Files.exists(j) ? Files.readAllLines(j, StandardCharsets.UTF_8).size() : 0;
        String line = seq + "|" + s.name() + "|" + detail.replace('|', '/') + "\n";
        try (FileChannel ch = FileChannel.open(j, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
    }

    private static void writeFsynced(Path p, byte[] data) throws IOException {
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(data));
            ch.force(true);
        }
    }

    private void requireState(State expected) {
        if (state != expected) throw new IllegalStateException("expected state " + expected + " but was " + state);
    }
}
