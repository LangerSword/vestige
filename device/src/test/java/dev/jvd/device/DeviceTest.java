package dev.jvd.device;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the device layer.
 *
 * <p>These run without root and without a real drive, so they cover the parts that can
 * be checked anywhere: the matching logic, the refusal logic, and the rule that a scan
 * which could not run never reports a clean result.
 */
class DeviceTest {

    @Test
    void anExactCanaryIsFound() {
        byte[] canary = "CANARY-PATTERN-0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] media = concat("some data before ".repeat(50).getBytes(StandardCharsets.UTF_8), canary);
        assertEquals(1, RawMediaScanner.countNear(media, media.length, canary, 0));
    }

    @Test
    void aCanaryThatIsNotThereIsNotFound() {
        byte[] canary = "CANARY-PATTERN-0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] media = "some other data ".repeat(100).getBytes(StandardCharsets.UTF_8);
        assertEquals(0, RawMediaScanner.countNear(media, media.length, canary, 0));
    }

    @Test
    void aCanaryThatSurvivedATranslationLayerIsStillFound() {
        // The flash translation layer can change a few bytes. A search that tolerated
        // nothing would miss a canary that is really there, which would report the
        // media as clean when it is not.
        byte[] canary = "CANARY-PATTERN-0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] media = concat("prefix ".repeat(20).getBytes(StandardCharsets.UTF_8), canary);
        // Two bytes differ, the way a scrambling layer would change them.
        media[media.length - 5] ^= 0x01;
        media[media.length - 11] ^= 0x02;
        assertEquals(1, RawMediaScanner.countNear(media, media.length, canary, 4),
                "a canary within the tolerance must still be found");
        assertEquals(0, RawMediaScanner.countNear(media, media.length, canary, 0),
                "the same canary is outside a tolerance of zero");
    }

    @Test
    void aToleranceThatIsTooLooseDoesNotMatchEverything() {
        // A tolerance that matched everything would find the canary everywhere, which
        // is as bad as a search that finds nothing.
        byte[] canary = "CANARY-PATTERN-0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] media = "totally different bytes here".repeat(50).getBytes(StandardCharsets.UTF_8);
        assertEquals(0, RawMediaScanner.countNear(media, media.length, canary, 4));
    }

    @Test
    void aScanOfAFileFindsTheCanaryAndReportsTheBytesItRead(@TempDir Path tmp) throws Exception {
        Path image = tmp.resolve("disk.img");
        byte[] canary = "CANARY-PATTERN-0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] media = new byte[1 << 20];
        for (int i = 0; i < media.length; i++) media[i] = (byte) (i % 251);
        System.arraycopy(canary, 0, media, 4096, canary.length);
        Files.write(image, media);

        RawMediaScanner.Result r = RawMediaScanner.scanFile(image, canary, 0);

        assertTrue(r.checkable());
        assertTrue(r.canaryFound());
        assertEquals(1, r.matches());
        assertEquals(media.length, r.bytesScanned());
    }

    @Test
    void aScanOfACleanFileReportsTheCanaryAsAbsent(@TempDir Path tmp) throws Exception {
        Path image = tmp.resolve("clean.img");
        byte[] canary = "CANARY-PATTERN-0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] media = new byte[1 << 20];
        for (int i = 0; i < media.length; i++) media[i] = (byte) (i % 251);
        Files.write(image, media);

        RawMediaScanner.Result r = RawMediaScanner.scanFile(image, canary, 0);

        assertTrue(r.checkable());
        assertTrue(r.canaryAbsent());
        assertEquals(0, r.matches());
    }

    @Test
    void aScanThatCouldNotRunHasNoOpinion(@TempDir Path tmp) {
        // This is the important one: a scan that could not read the media must not
        // report that the canary was absent. "We could not look" and "it is not there"
        // are different answers, and only the second one is a result.
        RawMediaScanner.Result r = RawMediaScanner.scan(tmp.resolve("does-not-exist"),
                "CANARY".getBytes(StandardCharsets.UTF_8), 0);

        assertFalse(r.checkable());
        assertFalse(r.canaryAbsent(), "a scan that could not run must not claim the canary is gone");
        assertNotNull(r.reason());
    }

    @Test
    void aScanWithNoCanaryIsNotCheckable(@TempDir Path tmp) {
        RawMediaScanner.Result r = RawMediaScanner.scan(tmp.resolve("x"), new byte[0], 0);
        assertFalse(r.checkable());
    }

    @Test
    void aSanitizeWithoutTheTokenIsRefused() {
        Sanitizer.Outcome o = Sanitizer.sanitize("/dev/nvme-does-not-exist",
                Sanitizer.Action.BLOCK_ERASE, "not-the-token");
        assertFalse(o.started(), "nothing may be sent to a drive without the token");
        assertTrue(o.reason().contains("confirmation"), o.reason());
    }

    @Test
    void aSanitizeOnAMountedDriveIsRefused() {
        // The boot drive on this machine is mounted. Sanitizing it would destroy a
        // running system, so the refusal has to happen before anything is sent.
        String controller = mountedController();
        if (controller == null) return; // No mounted NVMe on this machine.

        Sanitizer.Outcome o = Sanitizer.sanitize(controller,
                Sanitizer.Action.BLOCK_ERASE, Sanitizer.CONFIRM_TOKEN);

        assertFalse(o.started(), "a mounted drive must never be sanitized");
        assertTrue(o.reason().contains("mounted"), o.reason());
    }

    @Test
    void theStrongestOperationIsPickedInOrderOfStrength() {
        assertEquals("BLOCK_ERASE", identity(true, false, false).strongestSupported());
        assertEquals("OVERWRITE", identity(false, true, false).strongestSupported());
        assertEquals("CRYPTO_ERASE", identity(false, false, true).strongestSupported());
        assertEquals("OVERWRITE", identity(true, true, false).strongestSupported(),
                "crypto erase is strongest, then overwrite, then block erase");
        assertEquals("BLOCK_ERASE", identity(true, false, false).strongestSupported());
        assertEquals("NONE", identity(false, false, false).strongestSupported(),
                "a drive that supports none of them must say so");
    }

    @Test
    void theControllerOutputIsRecognisedAndTheUsageTextIsNot() {
        assertTrue(NvmeController.looksLikeControllerData(
                List.of("NVME Identify Controller:", "sn        : 1234")),
                "the real header must be recognised");
        assertFalse(NvmeController.looksLikeControllerData(
                List.of("Send an Identify Controller command to the given device")),
                "the usage text must not be mistaken for a result");
    }

    @Test
    void theColourCodesAreRemovedBeforeAnythingIsMatched() {
        String coloured = "  [1:1] : 0x1[1mBlock Erase Sanitize Operation Supported[0m";
        String plain = NvmeController.stripAnsi(coloured);
        assertFalse(plain.contains(""), "the colour codes must be gone: " + plain);
        assertTrue(plain.contains("Block Erase Sanitize Operation Supported"), plain);
    }

    /** Joins byte arrays. */
    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static NvmeController.Identity identity(boolean blockErase, boolean overwrite,
                                                      boolean cryptoErase) {
        return new NvmeController.Identity("/dev/nvmeX", "", "", "", "", 0L,
                blockErase, overwrite, cryptoErase, false, false, "");
    }

    /** The controller of a mounted NVMe namespace, or null when there is none. */
    private static String mountedController() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/mounts"))) {
                String[] parts = line.split(" ");
                if (parts[0].startsWith("/dev/nvme") && parts[0].contains("n1")) {
                    return "/dev/" + parts[0].substring("/dev/".length()).replaceAll("n\\d+p\\d+", "");
                }
            }
        } catch (Exception ignored) {
            // No mount list means no known mounts.
        }
        return null;
    }
}
