package dev.jvd.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Single-pass zero overwrite + fsync + delete.
 *
 * <p>NIST SP 800-88 category: <strong>Clear</strong> at the logical level, and
 * <strong>weak on flash and copy-on-write filesystems</strong>: SPIKES H6 showed
 * unlink alone leaves all block contents in an ext4 image, and on btrfs an
 * in-place overwrite + fsync relocated the extent (COW) instead of overwriting
 * the old physical bytes. SSD FTL remapping is unobservable from user space.
 * Multi-pass overwriting is not offered: Rev.2/IEEE 2883 treat extra passes as
 * folklore on modern media; one verified pass beats N unverified ones.
 *
 * @return number of bytes overwritten
 */
public final class OverwriteDestroyer {

    private OverwriteDestroyer() {}

    public static long destroy(Path path) throws IOException {
        long size = Files.size(path);
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ByteBuffer zeros = ByteBuffer.allocate(8192);
            long pos = 0;
            while (pos < size) {
                zeros.clear();
                int chunk = (int) Math.min(zeros.capacity(), size - pos);
                zeros.limit(chunk);
                while (zeros.hasRemaining()) {
                    pos += ch.write(zeros, pos);
                }
            }
            ch.force(true);
        }
        Files.delete(path);
        return size;
    }
}
