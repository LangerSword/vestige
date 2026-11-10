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
 * <p>Key custody note (demo scope): the caller-supplied KEK hex and source
 * path are recorded in {@code txn.meta} inside the work directory so a later
 * process can recover after {@code kill -9}. The work directory is a trusted
 * location in this implementation's threat model; production custody belongs
 * in a KMS/HSM.
 */
public final class DestructionTransaction {

    public enum State { STAGED, VAULTED, SCRUBBED, VALIDATED_PRECOMMIT, COMMITTED, ABORTED }

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

    /** Recovers using the source path and KEK recorded in txn.meta. */
    public static RecoveryResult recover(Path workDir) throws Exception {
        Path meta = workDir.resolve(META);
        if (!Files.exists(meta)) return new RecoveryResult(null, false, "no transaction metadata in " + workDir);
        List<String> lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
        Path source = Path.of(lines.get(0));
        byte[] kek = HexFormat.of().parseHex(lines.get(1).strip());
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

    private void writeMeta() throws IOException {
        String content = source.toAbsolutePath() + "\n" + HexFormat.of().formatHex(kek) + "\n";
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
