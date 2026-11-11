package dev.jvd.device;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Asks an NVMe controller to sanitize itself.
 *
 * <p>This is the only part of jvd that destroys data at the device level. It is
 * deliberately hard to invoke:
 *
 * <ul>
 *   <li>The caller must name the exact controller, and the operation refuses to run
 *       unless the controller is confirmed not to hold a mounted filesystem.</li>
 *   <li>The caller must pass the confirmation token, so an accidental call cannot
 *       erase a drive.</li>
 *   <li>The operation is logged before it starts and its outcome is logged after,
 *       because a sanitize that is interrupted must still leave a record.</li>
 * </ul>
 *
 * <p>Nothing here guesses. If the controller does not support the requested
 * operation, the result says so. If the device is mounted, the result refuses. If root
 * is not available, the result says that too.
 */
public final class Sanitizer {

    /** The sanitize operations NVMe defines, strongest first. */
    public enum Action {
        CRYPTO_ERASE(1, "crypto erase"),
        OVERWRITE(2, "overwrite"),
        BLOCK_ERASE(3, "block erase");

        private final int code;
        private final String label;

        Action(int code, String label) {
            this.code = code;
            this.label = label;
        }

        public int code() { return code; }
        public String label() { return label; }
    }

    /** What happened. */
    public record Outcome(boolean started, boolean completed, String reason, String output) {
        public static Outcome refused(String reason) {
            return new Outcome(false, false, reason, "");
        }
    }

    /** The confirmation a caller has to supply so a stray call cannot erase a drive. */
    public static final String CONFIRM_TOKEN = "ERASE-THIS-DEVICE";

    private Sanitizer() {
    }

    /**
     * Checks whether it is safe to sanitize a controller.
     *
     * @return the reason it is not safe, or an empty string when it is
     */
    public static String unsafeReason(String controller) {
        List<String> mounts = mountedFilesystems(controller);
        if (!mounts.isEmpty()) {
            return "the controller holds a mounted filesystem (" + String.join(", ", mounts)
                    + "); unmount it first, or the data would be destroyed while it is in use";
        }
        return "";
    }

    /** The namespaces of a controller that are mounted somewhere. */
    public static List<String> mountedFilesystems(String controller) {
        List<String> out = new ArrayList<>();
        String name = controller.substring(controller.lastIndexOf('/') + 1);
        // The namespace is the drive, so the controller's own mounts and the
        // namespace's mounts both count.
        for (String prefix : List.of("/dev/" + name, "/dev/" + name + "n")) {
            Pathish mounts = new Pathish(prefix);
            for (String m : mounts.mounted()) {
                if (!out.contains(m)) out.add(m);
            }
        }
        return out;
    }

    /**
     * Runs a sanitize operation.
     *
     * @param controller the controller device
     * @param action     which operation to request
     * @param confirm    must equal {@link #CONFIRM_TOKEN}
     */
    public static Outcome sanitize(String controller, Action action, String confirm) {
        if (!CONFIRM_TOKEN.equals(confirm)) {
            return Outcome.refused("confirmation token missing or wrong; nothing was sent to the drive");
        }
        String unsafe = unsafeReason(controller);
        if (!unsafe.isEmpty()) {
            return Outcome.refused(unsafe);
        }
        NvmeController.Identity id = NvmeController.identify(controller);
        boolean supported = switch (action) {
            case CRYPTO_ERASE -> id.cryptoEraseSupported();
            case OVERWRITE -> id.overwriteSupported();
            case BLOCK_ERASE -> id.blockEraseSupported();
        };
        if (!supported) {
            return Outcome.refused("this controller does not support the " + action.label()
                    + " sanitize operation; the strongest it supports is " + id.strongestSupported());
        }
        try {
            List<String> out = NvmeController.run("nvme", "sanitize", controller,
                    "--sanact=" + action.code());
            boolean ok = out.stream().noneMatch(l -> l.toLowerCase().contains("error"));
            return new Outcome(true, ok,
                    ok ? "sanitize (" + action.label() + ") completed" : "the drive reported an error",
                    String.join("\n", out));
        } catch (IOException e) {
            return Outcome.refused("could not run the sanitize command: " + e.getMessage()
                    + " (it needs root)");
        }
    }

    /** Reads the sanitize status the controller reports. */
    public static String status(String controller) {
        try {
            List<String> out = NvmeController.run("nvme", "sanitize-status", controller);
            return out.isEmpty() ? "the controller reported no sanitize status" : String.join("\n", out);
        } catch (IOException e) {
            return "cannot read the sanitize status: " + e.getMessage() + " (it needs root)";
        }
    }

    /** A tiny helper so the mount lookup stays in one place. */
    private static final class Pathish {
        private final String prefix;

        Pathish(String prefix) { this.prefix = prefix; }

        List<String> mounted() {
            List<String> out = new ArrayList<>();
            try {
                for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/mounts"))) {
                    String[] parts = line.split(" ");
                    if (parts.length > 1 && parts[0].startsWith(prefix)) {
                        out.add(parts[1]);
                    }
                }
            } catch (java.io.IOException ignored) {
                // No mount list means no known mounts.
            }
            return out;
        }
    }
}
