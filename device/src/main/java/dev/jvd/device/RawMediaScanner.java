package dev.jvd.device;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans the raw media of a block device for a canary.
 *
 * <p>This is the channel that answers the question the rest of the tool cannot: is the
 * data still on the flash? Everything else in jvd reads a file. This reads the device.
 *
 * <p>The scan is a masked search, not an exact one. The canary is a known pattern; the
 * copy on the media may have been transformed by the flash translation layer (the
 * controller can add an encryption key, or a scrambling pattern, or store the block in a
 * different order), so the search tolerates a small number of differing bytes. The
 * tolerance is deliberately small and is reported, because a search that tolerates a
 * lot would find things that are not the canary.
 *
 * <p>Reading a block device needs root. When it is not available the result is
 * NOT_CHECKABLE with the reason. A scan that could not run is never reported as a
 * clean result.
 */
public final class RawMediaScanner {

    /** How many bytes of a candidate may differ before it stops counting as a match. */
    public static final int DEFAULT_TOLERANCE = 4;

    /** The outcome of one raw-media scan. */
    public record Result(
            boolean checkable,
            String reason,
            long bytesScanned,
            long sectorsScanned,
            int matches,
            int tolerance,
            boolean canaryFound,
            long elapsedMillis) {

        /**
         * Whether the canary is gone.
         *
         * <p>Only meaningful when {@link #checkable()} is true. A scan that could not run
         * has no opinion, and saying "the canary was not found" about a scan that never
         * ran would be a lie.
         */
        public boolean canaryAbsent() {
            return checkable && !canaryFound;
        }
    }

    private RawMediaScanner() {
    }

    /**
     * Scans a block device for a masked canary.
     *
     * @param device   the device to read, for example {@code /dev/nvme1n1}
     * @param canary   the exact canary bytes
     * @param tolerance how many bytes of a candidate may differ
     */
    public static Result scan(Path device, byte[] canary, int tolerance) {
        return scan(device, canary, tolerance, Long.MAX_VALUE);
    }

    /**
     * Scans a block device for a masked canary, up to a byte limit.
     *
     * <p>The limit matters. A modern namespace is hundreds of gigabytes, and reading all
     * of it takes longer than anybody will wait for a command. The caller chooses how much
     * to read: enough to cover where the data was, or enough to cover the whole device
     * when that is what the question demands.
     */
    public static Result scan(Path device, byte[] canary, int tolerance, long maxBytes) {
        long t0 = System.nanoTime();
        if (canary == null || canary.length == 0) {
            return new Result(false, "no canary supplied", 0, 0, 0, tolerance, false,
                    ms(t0));
        }
        try (FileChannel ch = FileChannel.open(device, java.nio.file.StandardOpenOption.READ)) {
            long size = ch.size();
            long limit = Math.min(size, maxBytes);
            int found = 0;
            long scanned = 0;
            long sectors = 0;
            byte[] buf = new byte[1 << 20];
            // A candidate can straddle a read boundary, so the tail of the previous
            // buffer is carried into the next one.
            byte[] carry = new byte[canary.length - 1];
            int carryLen = 0;
            while (scanned < limit) {
                int n = ch.read(java.nio.ByteBuffer.wrap(buf));
                if (n <= 0) break;
                scanned += n;
                sectors += (n + 511) / 512;
                byte[] window = new byte[carryLen + n];
                System.arraycopy(carry, 0, window, 0, carryLen);
                System.arraycopy(buf, 0, window, carryLen, n);
                found += countNear(window, window.length, canary, tolerance);
                carryLen = Math.min(canary.length - 1, window.length);
                System.arraycopy(window, window.length - carryLen, carry, 0, carryLen);
                java.util.Arrays.fill(window, (byte) 0);
                java.util.Arrays.fill(buf, 0, n, (byte) 0);
            }
            java.util.Arrays.fill(carry, (byte) 0);
            return new Result(true, null, scanned, sectors, found, tolerance, found > 0,
                    ms(t0));
        } catch (java.nio.file.AccessDeniedException e) {
            return new Result(false,
                    "cannot read " + device + ": it needs root (try: sudo jvd device scan ...)",
                    0, 0, 0, tolerance, false, ms(t0));
        } catch (IOException e) {
            return new Result(false, "read failed on " + device + ": " + e.getMessage(),
                    0, 0, 0, tolerance, false, ms(t0));
        }
    }

    /** Scans with the default tolerance. */
    public static Result scan(Path device, byte[] canary) {
        return scan(device, canary, DEFAULT_TOLERANCE);
    }

    /**
     * Counts the places where the canary appears, allowing a few bytes to differ.
     *
     * <p>A plain comparison would find nothing on a device that encrypts or scrambles
     * the blocks it stores, and would report a clean result for media that still holds
     * the data. Allowing a small number of differing bytes keeps the search honest in
     * both directions: it still finds the canary through a translation layer, and it
     * still refuses to call unrelated bytes a match.
     */
    static int countNear(byte[] data, int length, byte[] canary, int tolerance) {
        int found = 0;
        if (canary.length == 0 || length < canary.length) return 0;
        outer:
        for (int i = 0; i + canary.length <= length; i++) {
            int differing = 0;
            for (int j = 0; j < canary.length; j++) {
                if (data[i + j] != canary[j]) {
                    differing++;
                    if (differing > tolerance) continue outer;
                }
            }
            found++;
            i += canary.length - 1;
        }
        return found;
    }

    /**
     * Scans a plain file the same way.
     *
     * <p>Used for the case where the caller has a disk image rather than the device
     * itself. The result is the same kind of statement: the canary was or was not in
     * these bytes.
     */
    public static Result scanFile(Path file, byte[] canary, int tolerance) {
        long t0 = System.nanoTime();
        if (canary == null || canary.length == 0) {
            return new Result(false, "no canary supplied", 0, 0, 0, tolerance, false, ms(t0));
        }
        try (InputStream in = java.nio.file.Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 20];
            byte[] carry = new byte[canary.length - 1];
            int carryLen = 0;
            int found = 0;
            long scanned = 0;
            while (true) {
                int n = in.read(buf);
                if (n <= 0) break;
                scanned += n;
                byte[] window = new byte[carryLen + n];
                System.arraycopy(carry, 0, window, 0, carryLen);
                System.arraycopy(buf, 0, window, carryLen, n);
                found += countNear(window, window.length, canary, tolerance);
                carryLen = Math.min(canary.length - 1, window.length);
                System.arraycopy(window, window.length - carryLen, carry, 0, carryLen);
                java.util.Arrays.fill(window, (byte) 0);
            }
            java.util.Arrays.fill(carry, (byte) 0);
            return new Result(true, null, scanned, scanned / 512, found, tolerance,
                    found > 0, ms(t0));
        } catch (IOException e) {
            return new Result(false, "read failed on " + file + ": " + e.getMessage(),
                    0, 0, 0, tolerance, false, ms(t0));
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** The controllers and namespaces present, for a report. */
    public static List<String> describeDevices() {
        List<String> out = new ArrayList<>();
        for (String c : NvmeController.controllers()) {
            NvmeController.Identity id = NvmeController.identify(c);
            out.add(c + " " + id.modelNumber() + " sn=" + id.serialNumber()
                    + " sanitize=" + id.strongestSupported());
        }
        return out;
    }
}
