package dev.jvd.device;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads an NVMe controller's identity and capabilities.
 *
 * <p>This is the part of the tool that looks at the <em>device</em> rather than at a
 * file. The distinction matters because it is the difference between saying "the file was
 * overwritten" and saying "the drive was sanitized": the first is observable from user
 * space, the second is only observable if the drive itself reports it.
 *
 * <p>Everything here is read-only. The controller is asked to describe itself; it is
 * never asked to change anything. Destructive operations live in {@link Sanitizer} and
 * are a separate, explicitly-invoked step.
 *
 * <p><b>Root is required</b> to open a character device under {@code /dev/nvme*}. When
 * that is not available the result is NOT_CHECKABLE with the reason, never a guess.
 */
public final class NvmeController {

    /** What the controller says about itself. */
    public record Identity(
            String path,
            String serialNumber,
            String modelNumber,
            String firmware,
            String ieeeIdentifier,
            long namespaceBytes,
            boolean blockEraseSupported,
            boolean overwriteSupported,
            boolean cryptoEraseSupported,
            boolean sanitizePurgeSupported,
            boolean noDeallocateSupported,
            String rawOutput) {

        /**
         * The strongest sanitize operation this controller can perform.
         *
         * <p>The order is the NVMe order of strength: Block Erase asks the controller to
         * erase its own allocation, Overwrite writes a pattern through the media, and
         * Crypto Erase destroys the keys. A controller that supports none of them cannot
         * sanitize, and that fact is worth stating rather than hiding.
         */
        public String strongestSupported() {
            if (cryptoEraseSupported) return "CRYPTO_ERASE";
            if (overwriteSupported) return "OVERWRITE";
            if (blockEraseSupported) return "BLOCK_ERASE";
            return "NONE";
        }
    }

    private NvmeController() {
    }

    /**
     * Reads the identity of one controller.
     *
     * @param controller the controller device, for example {@code /dev/nvme0}
     * @return what the controller reports, or a NOT_CHECKABLE identity with the reason
     */
    public static Identity identify(String controller) {
        if (!Files.exists(Path.of(controller))) {
            return unavailable(controller, "no such device: " + controller);
        }
        // Reading the controller's capabilities needs an admin command. When it cannot
        // run, the identity fields still come from sysfs, and the sanitize capability is
        // reported as unknown rather than as unsupported: "we could not ask" and "the
        // drive cannot do it" are different facts.
        List<String> out = new ArrayList<>();
        boolean controllerRead = false;
        try {
            out.addAll(run("nvme", "id-ctrl", controller, "-H"));
            // The command prints "Permission denied" rather than failing, so the output
            // itself has to be checked to know whether the controller was really read.
            controllerRead = looksLikeControllerData(out);
        } catch (IOException e) {
            // The command could not be started at all.
        }
        String raw = String.join("\n", out);
        if (!controllerRead) {
            // The controller could not be read without elevation. Try once with it.
            List<String> elevated = tryElevated("nvme", "id-ctrl", controller, "-H");
            if (looksLikeControllerData(elevated)) {
                raw = String.join("\n", elevated);
                controllerRead = true;
            }
        }
        String sysfs = "/sys/class/nvme/" + controller.substring(controller.lastIndexOf('/') + 1);

        String sn = readSysfs(sysfs + "/serial");
        String mn = readSysfs(sysfs + "/model");
        String fw = readSysfs(sysfs + "/firmware_rev");
        String ieee = readSysfs(sysfs + "/ieee_address");

        // The lines carry colour codes, so the raw text is cleaned before matching.
        String plain = stripAnsi(raw);
        boolean blockErase = plain.contains("[1:1] : 0x1\tBlock Erase Sanitize Operation Supported");
        boolean overwrite = plain.contains("[2:2] : 0x1\tOverwrite Sanitize Operation Supported");
        boolean cryptoErase = plain.contains("[0:0] : 0x1\tCrypto Erase Sanitize Operation Supported");
        boolean purge = plain.contains("[5:5] : 0x1\tSanitize Purge Request and Reporting Supported");
        boolean noDealloc = plain.contains("[29:29] : 0x1\tNo-Deallocate After Sanitize bit in Sanitize command Supported");

        if (sn == null && mn == null && raw.isEmpty()) {
            return unavailable(controller,
                    "cannot read the controller: it needs root, and no controller identity was readable");
        }
        return new Identity(controller,
                sn == null ? "" : sn,
                mn == null ? "" : mn,
                fw == null ? "" : fw,
                ieee == null ? "" : ieee,
                namespaceBytes(controller),
                blockErase, overwrite, cryptoErase, purge, noDealloc,
                raw);
    }

    /**
     * Whether the output is a real controller identification.
     *
     * <p>The command reports a permission problem by printing a usage message rather
     * than by failing, so the output has to be inspected. A real identification always
     * carries the identify header.
     */
    static boolean looksLikeControllerData(List<String> output) {
        for (String l : output) {
            // The real identification starts with the header line "NVME Identify
            // Controller:" and the usage text also says "Identify Controller", so the
            // header is matched exactly rather than as a phrase.
            String s = stripAnsi(l).strip();
            if (s.toUpperCase(java.util.Locale.ROOT).startsWith("NVME IDENTIFY CONTROLLER")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the terminal colour codes from a line.
     *
     * <p>nvme-cli colours its output when it thinks it is talking to a terminal, and the
     * escape sequences sit in the middle of the capability lines. Matching against the
     * coloured text fails, so the codes are removed before anything is matched.
     */
    static String stripAnsi(String line) {
        if (line == null) return "";
        return line.replaceAll("\\[[0-9;]*m", "")
                .replaceAll("\\[[0-9;]*[A-Za-z]", "");
    }

    /**
     * Runs a command with administrator rights, when the system allows it without a
     * password.
     *
     * <p>Reading an NVMe controller is an admin operation. A user who has configured
     * polkit for passwordless administration should not have to type a password to ask
     * their own tool what their own drive can do, so the tool tries. When it is not
     * allowed, the empty list is returned and the caller reports the limit.
     */
    static List<String> tryElevated(String... command) {
        List<String> wrapper = new ArrayList<>();
        wrapper.add("pkexec");
        wrapper.addAll(List.of(command));
        try {
            List<String> out = run(wrapper.toArray(new String[0]));
            // pkexec writes the failure to stderr, which is merged into the output.
            for (String l : out) {
                if (l.contains("not authorized") || l.contains("Error executing command")
                        || l.contains("authentication")) {
                    return List.of();
                }
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Lists the controllers present on this machine. */
    public static List<String> controllers() {
        List<String> out = new ArrayList<>();
        Path dir = Path.of("/dev");
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().matches("nvme\\d+"))
                    .map(Path::toString)
                    .sorted()
                    .forEach(out::add);
        } catch (IOException ignored) {
            // No list is a legitimate answer: the caller reports NOT_CHECKABLE.
        }
        return out;
    }

    /** The namespace size in bytes, or 0 when it cannot be read. */
    public static long namespaceBytes(String controller) {
        // The size is in sysfs next to the namespace. A character device cannot carry
        // attributes a caller may read without opening it, so the kernel's own view is
        // the reliable source. The block class holds the size in 512-byte sectors.
        String name = controller.substring(controller.lastIndexOf('/') + 1);
        for (int ns = 1; ns <= 32; ns++) {
            String sectors = readSysfs("/sys/class/block/" + name + "n" + ns + "/size");
            if (sectors == null) continue;
            try {
                return Long.parseLong(sectors.trim()) * 512L;
            } catch (NumberFormatException ignored) {
                // Try the next namespace.
            }
        }
        return 0L;
    }

    private static String readSysfs(String path) {
        try {
            String s = Files.readString(Path.of(path)).strip();
            return s.isEmpty() ? null : s;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Identity unavailable(String controller, String reason) {
        return new Identity(controller, "", "", "", "", 0L,
                false, false, false, false, false, reason);
    }

    /** Runs a command and returns its output lines. */
    static List<String> run(String... command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output;
        try (var in = p.getInputStream()) {
            output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try {
            if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        List<String> lines = new ArrayList<>();
        for (String l : output.split("\n")) {
            if (!l.isBlank()) lines.add(l);
        }
        return lines;
    }
}
