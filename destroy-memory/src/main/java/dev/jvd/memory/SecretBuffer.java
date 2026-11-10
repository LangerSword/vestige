package dev.jvd.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Off-heap secret storage via the FFM API (java.lang.foreign, final since JDK 22).
 *
 * <p>Destruction contract: {@link #destroy()} explicitly fills the segment with
 * zeros <em>before</em> closing the arena. Arena close alone only frees the
 * memory; zeroing is never implied (see SPIKES H5 / report C-10).
 *
 * <p>Best-effort hardening: the constructor attempts libc {@code mlock} (keep the
 * pages out of swap) and {@code madvise(MADV_DONTDUMP)} (exclude from core dumps)
 * via Linker downcalls. Downcalls are restricted methods on JDK 25 and need
 * {@code --enable-native-access=ALL-UNNAMED}; without it they may throw or warn.
 * Every failure is swallowed but recorded in {@link #hardeningReport()} — the
 * buffer remains fully functional in degraded mode.
 *
 * <p>Allocation is page-aligned (4096) because {@code madvise} returns EINVAL on
 * non-page-aligned segments (SPIKES H5).
 */
public final class SecretBuffer implements AutoCloseable {

    private static final int MADV_DONTDUMP = 16;

    private final Arena arena;
    private final MemorySegment segment;
    private final int length;
    private final String hardeningReport;
    private boolean destroyed;

    public SecretBuffer(int length) {
        if (length <= 0) throw new IllegalArgumentException("length must be > 0");
        this.length = length;
        this.arena = Arena.ofConfined();
        this.segment = arena.allocate(length, 4096);
        this.hardeningReport = attemptHardening(segment, length);
    }

    private static String attemptHardening(MemorySegment seg, int len) {
        String mlock;
        String madvise;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = linker.defaultLookup();
            MethodHandle mlockH = linker.downcallHandle(
                    lookup.find("mlock").orElseThrow(() -> new IllegalStateException("mlock symbol not found")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            int rc = (int) mlockH.invoke(seg, (long) len);
            mlock = (rc == 0) ? "OK" : "FAILED(rc=" + rc + ")";
        } catch (Throwable t) {
            mlock = "FAILED(" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()) + ")";
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = linker.defaultLookup();
            MethodHandle madviseH = linker.downcallHandle(
                    lookup.find("madvise").orElseThrow(() -> new IllegalStateException("madvise symbol not found")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
            int rc = (int) madviseH.invoke(seg, (long) len, MADV_DONTDUMP);
            madvise = (rc == 0) ? "OK" : "FAILED(rc=" + rc + ")";
        } catch (Throwable t) {
            madvise = "FAILED(" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()) + ")";
        }
        return "mlock=" + mlock + " madvise=DONTDUMP:" + madvise;
    }

    /** Outcome of the best-effort mlock / madvise attempts, e.g. {@code "mlock=OK madvise=DONTDUMP:OK"}. */
    public String hardeningReport() {
        return hardeningReport;
    }

    public int length() {
        return length;
    }

    public synchronized void write(byte[] src) {
        ensureOpen();
        if (src.length > length) throw new IllegalArgumentException("src longer than buffer");
        MemorySegment.copy(src, 0, segment, ValueLayout.JAVA_BYTE, 0, src.length);
    }

    /** Returns a copy of the current buffer contents. */
    public synchronized byte[] read() {
        ensureOpen();
        return segment.toArray(ValueLayout.JAVA_BYTE);
    }

    public synchronized boolean isDestroyed() {
        return destroyed;
    }

    /** Zeroes the segment and then closes the arena. Idempotent. */
    public synchronized void destroy() {
        if (destroyed) return;
        segment.fill((byte) 0);
        destroyed = true;
        arena.close();
    }

    @Override
    public void close() {
        destroy();
    }

    private void ensureOpen() {
        if (destroyed) throw new IllegalStateException("SecretBuffer already destroyed");
    }
}
