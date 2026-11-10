package dev.jvd.memory;

import java.util.Arrays;

/**
 * Heap-array wiping utilities.
 *
 * <p><strong>BEST-EFFORT ONLY.</strong> {@code Arrays.fill} overwrites the live
 * array, but copying/relocating garbage collectors may already have moved the
 * array and left stale copies in old heap regions that no Java code can reach
 * or wipe. SPIKES H2 measured exactly that on JDK 25: after wipe + drop + GC,
 * G1 left 2 extra stale canary copies and ZGC left 5 (Serial/Parallel/Shenandoah
 * left 0 in that run). Dropping a reference without filling is far worse — the
 * SPIKES H1 positive control left 300,001 intact copies.
 *
 * <p>For secrets that must be destroyed reliably, use {@link SecretBuffer}
 * (off-heap, fill-before-close) or crypto-shredding. Never hold secrets in
 * {@link String} (immutable, cannot be wiped at all).
 */
public final class HeapWipe {

    private HeapWipe() {}

    /** Best-effort: fills the live array with zeros. Stale GC copies may survive. */
    public static void wipe(byte[] a) {
        if (a != null) Arrays.fill(a, (byte) 0);
    }

    /** Best-effort: fills the live array with zeros. Stale GC copies may survive. */
    public static void wipe(char[] a) {
        if (a != null) Arrays.fill(a, '\0');
    }
}
