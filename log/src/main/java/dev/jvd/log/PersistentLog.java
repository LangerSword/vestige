package dev.jvd.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

/**
 * Directory-backed persistence for a {@link MerkleLog}: leaves in
 * {@code leaves.jsonl} (one Base64 leaf per line), latest checkpoint in
 * {@code checkpoint.txt}. Deliberately simple — the format is an audit
 * artefact, not a database.
 */
public final class PersistentLog {

    public static final String LEAVES_FILE = "leaves.jsonl";
    public static final String CHECKPOINT_FILE = "checkpoint.txt";

    private final Path dir;
    private final MerkleLog log = new MerkleLog();

    private PersistentLog(Path dir) {
        this.dir = dir;
    }

    public static PersistentLog open(Path dir) throws IOException {
        Files.createDirectories(dir);
        PersistentLog pl = new PersistentLog(dir);
        Path leaves = dir.resolve(LEAVES_FILE);
        if (Files.exists(leaves)) {
            for (String line : Files.readAllLines(leaves, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (!t.isEmpty()) pl.log.append(Base64.getDecoder().decode(t));
            }
        }
        return pl;
    }

    /** Appends a leaf in memory and on disk. */
    public int append(byte[] leaf) throws IOException {
        int idx = log.append(leaf);
        Files.writeString(dir.resolve(LEAVES_FILE),
                Base64.getEncoder().encodeToString(leaf) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return idx;
    }

    public MerkleLog log() {
        return log;
    }

    public Path dir() {
        return dir;
    }

    public void saveCheckpoint(Checkpoint cp) throws IOException {
        Files.writeString(dir.resolve(CHECKPOINT_FILE), cp.toText() + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public Checkpoint loadCheckpoint() throws IOException {
        Path f = dir.resolve(CHECKPOINT_FILE);
        if (!Files.exists(f)) return null;
        return Checkpoint.parse(Files.readString(f, StandardCharsets.UTF_8));
    }
}
