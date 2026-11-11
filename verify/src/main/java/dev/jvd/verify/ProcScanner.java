package dev.jvd.verify;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scans another process's memory via {@code /proc/<pid>/maps} +
 * {@code /proc/<pid>/mem} — the primary verification channel (SPIKES H3: heap
 * dumps are secondary and order-sensitive; H4: scanning your own process is
 * invalid, so this always targets a <em>child</em> pid).
 *
 * <p>Access depends on the ptrace policy: with {@code kernel.yama.ptrace_scope=1}
 * a parent may read its children; scope 2/3 or non-child targets may make
 * {@code /proc/<pid>/mem} unopenable, which raises {@link ScanException} so the
 * caller can report {@code NOT_CHECKABLE} — never a silent pass (PLAN risk 2).
 */
public final class ProcScanner {

    /** Thrown when the target's memory cannot be opened at all: the scan is NOT_CHECKABLE, not NOT_FOUND. */
    public static final class ScanException extends Exception {
        public ScanException(String msg, Throwable cause) { super(msg, cause); }
    }

    private ProcScanner() {}

    /**
     * Counts occurrences of every canary-family variant in the target's
     * readable regions. Unreadable individual regions are skipped (guard
     * pages, racing teardown); only a total failure to open the mem file is a
     * {@link ScanException}.
     *
     * @return map variant name → occurrence count (zero counts included)
     */
    public static Map<String, Integer> scanPid(long pid, byte[] pattern) throws ScanException {
        Map<String, byte[]> family = Canary.family(pattern);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : family.keySet()) counts.put(k, 0);

        Path memPath = Path.of("/proc", Long.toString(pid), "mem");
        Path mapsPath = Path.of("/proc", Long.toString(pid), "maps");
        try (RandomAccessFile mem = new RandomAccessFile(memPath.toFile(), "r");
             BufferedReader maps = Files.newBufferedReader(mapsPath)) {
            String line;
            while ((line = maps.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;
                if (!parts[1].startsWith("r")) continue; // readable regions only
                // Skip file-backed library/module mappings (pathname not in
                // brackets): a planted heap canary cannot live there, and they
                // are hundreds of MB. Anonymous, [heap] and [stack] regions
                // are scanned — and so are /memfd: regions: ZGC maps its heap
                // via /memfd:java_heap, and skipping it made every ZGC run
                // INVALID (Assumption 17; regression test
                // CanaryVerifierTest#nowipeTargetIsFoundUnderZgc). memfd
                // regions are in-memory, not files. (A secret mmap'ed from a
                // real file is out of scope for this channel; the disk-image
                // channel covers files.)
                if (parts.length >= 6 && !parts[5].startsWith("[") && !parts[5].startsWith("/memfd:")) continue;
                String[] range = parts[0].split("-");
                long start = Long.parseUnsignedLong(range[0], 16);
                long end = Long.parseUnsignedLong(range[1], 16);
                long len = end - start;
                if (len <= 0 || len > (1L << 31)) continue;
                scanRegion(mem, start, (int) len, family, counts);
            }
        } catch (IOException e) {
            throw new ScanException("cannot open/scan /proc/" + pid + "/mem: " + e.getMessage(), e);
        }
        return counts;
    }

    private static void scanRegion(RandomAccessFile mem, long start, int len,
                                   Map<String, byte[]> family, Map<String, Integer> counts) {
        int maxPat = 0;
        for (byte[] p : family.values()) maxPat = Math.max(maxPat, p.length);
        int overlap = maxPat; // carry at least maxPat-1 bytes across chunk boundaries
        byte[] buf = new byte[1 << 20];
        byte[] carry = new byte[0];
        long pos = start;
        long remaining = len;
        try {
            mem.seek(start);
            while (remaining > 0) {
                int want = (int) Math.min(buf.length, remaining);
                int read = mem.read(buf, 0, want);
                if (read <= 0) return;
                byte[] window = new byte[carry.length + read];
                System.arraycopy(carry, 0, window, 0, carry.length);
                System.arraycopy(buf, 0, window, carry.length, read);
                for (Map.Entry<String, byte[]> e : family.entrySet()) {
                    counts.merge(e.getKey(), countOccurrences(window, e.getValue()), Integer::sum);
                }
                int keep = (int) Math.min(overlap, window.length);
                carry = new byte[keep];
                System.arraycopy(window, window.length - keep, carry, 0, keep);
                pos += read;
                remaining -= read;
            }
        } catch (IOException ignored) {
            // region not actually readable (races, guard pages): skip it
        }
    }

    static int countOccurrences(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) return 0;
        int count = 0;
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (haystack[i] != needle[0]) continue;
            for (int j = 1; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            count++;
            i += needle.length - 1;
        }
        return count;
    }

    public static int totalCount(Map<String, Integer> counts) {
        int t = 0;
        for (int v : counts.values()) t += v;
        return t;
    }
}
