package dev.jvd.gui;

import dev.jvd.evidence.EvidenceRecord;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The translation layer between jvd's machine-facing vocabulary and the words
 * a person who just wants their file gone actually needs.
 *
 * <p>The design rule for the desktop app is that the operator should never
 * have to think about the machinery (see the "hide the hood" requirement that
 * motivates this module). Every string the GUI shows to a human passes through
 * this class, so jargon cannot leak into the interface by accident — and so a
 * reviewer can audit in one file whether the user-facing language stays
 * honest.
 *
 * <p>Two rules apply to every translation here:
 * <ol>
 *   <li>Never overstate. The technique is real, but user-space Java cannot
 *       verify physical flash (PLAN §2, NIST SP 800-88 Rev.2 §2.3: user-space
 *       tools operate at the logical level). The limitation is always stated
 *       next to the success message.</li>
 *   <li>Never merge <em>verification</em> (machine results) and
 *       <em>validation</em> (the separate human/policy decision). Rev.2 keeps
 *       them apart, and so does the GUI: "we checked" and "a person signed
 *       off" are shown as different lines.</li>
 * </ol>
 */
final class Friendly {

    /** Shown next to every success message, so the honest limit is never more than one glance away. */
    static final String LIMITATION =
            "Note: we can only confirm the software side. Hardware copies on the drive itself "
                    + "cannot be checked from here.";

    private Friendly() {}

    /**
     * The stages a destruction goes through, in the order they happen.
     *
     * <p>The window shows this list before the run starts, so the user can see the plan
     * rather than watching a blank panel. The order matches the transaction exactly, and
     * the wording matches what the runner reports when each stage really completes.
     */
    static java.util.List<StageEvent> plannedStages() {
        return java.util.List.of(
                new StageEvent(1, "Checking your file",
                        "We look at the file and work out how to destroy it."),
                new StageEvent(2, "Making a protected copy",
                        "We make an encrypted copy, so a failed run can put the file back."),
                new StageEvent(3, "Destroying the original",
                        "We overwrite the original with zeros, then delete it."),
                new StageEvent(4, "Final check before we make it permanent",
                        "We check that the original is gone and that the copy still opens."),
                new StageEvent(5, "Making it permanent",
                        "We destroy the key that opens the copy. From here it cannot be undone."),
                new StageEvent(6, "Testing that it really cannot be read",
                        "We try to read the copy we kept. It cannot be read, because the key is gone."),
                new StageEvent(7, "Writing the signed record",
                        "We write a signed record, so you can show it to anyone."),
                new StageEvent(8, "Adding it to the history",
                        "We add the record to your history, countersigned by a second key."),
                new StageEvent(9, "Done",
                        "Your file has been destroyed and we verified it."));
    }

    /**
     * The index of a reported stage in the planned list.
     *
     * <p>The runner reports steps 1 to 9, and the planned list has nine entries in the
     * same order, so the mapping is the step number minus one. A stage the runner
     * reports that the plan does not know returns -1, and the caller leaves it out
     * rather than showing a step in the wrong place.
     */
    static int stageIndex(StageEvent event) {
        int index = event.step() - 1;
        return index >= 0 && index < plannedStages().size() ? index : -1;
    }

    /** Turns a technical failure into something a person can act on. */
    static String describeFailure(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        if (root instanceof NoSuchFileException) {
            return "We could not find that file. It may have been moved, renamed, or deleted.";
        }
        if (root instanceof AccessDeniedException) {
            return "We were not allowed to open that file. Check its permissions and try again.";
        }
        if (root instanceof IOException) {
            return "That file could not be read or written. Another program may be holding it open, "
                    + "or it may sit on a read-only drive.";
        }
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            return "Something went wrong while destroying the file.";
        }
        return "Something went wrong while destroying the file: " + msg;
    }

    /** A short human name for the destruction technique that was actually used. */
    static String methodName(EvidenceRecord.Method m) {
        return switch (m) {
            case CRYPTO_SHRED -> "Destroyed by making the key unreadable (the strongest method this app has)";
            case CLEAR_OVERWRITE -> "Overwritten with zeros";
            case MEMORY_WIPE -> "Cleared from memory";
        };
    }

    /** How much this app can vouch for the result, without the A0/A1/A2/A3 code. */
    static String assuranceName(EvidenceRecord.AssuranceLevel level) {
        return switch (level) {
            // PLAN §5: a run whose control tests did not pass — or which did not
            // check memory at all — can never claim more than the weakest level.
            case A0 -> "Basic - the app checked its own work, but no independent check ran in this session";
            case A1 -> "Good - the app checked its own work and the control tests passed";
            case A2 -> "Strong - independently checked and confirmed unrecoverable";
            case A3 -> "Strongest - independently checked, confirmed, and countersigned by a second party";
        };
    }

    /** The canary result, in words. NOT_CHECKABLE is honest, not a failure. */
    static String canaryName(EvidenceRecord.CanaryResult r) {
        return switch (r) {
            case FOUND -> "Residue was found - the secret was still in memory";
            case NOT_FOUND -> "No residue was found in any place we could check";
            case NOT_CHECKABLE -> "Not checked in this run - this run destroyed a file, not memory";
            case INVALID -> "The check itself was not trustworthy, so no result can be claimed";
        };
    }

    /** The validation decision (a human/policy call), kept separate from verification. */
    static String decisionName(EvidenceRecord.ValidationDecision d) {
        return switch (d) {
            case ACCEPTED -> "A reviewer accepted this record";
            case REJECTED -> "A reviewer rejected this record";
            case PENDING -> "Waiting for a person to review and accept this record";
        };
    }

    /** The residual-risk text of the signed record, in words a person can act on. */
    static String residualRisk(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String lower = raw.toLowerCase();
        if (lower.contains("physical media") || lower.contains("not verified")) {
            return "Copies the drive keeps for itself cannot be checked from software. "
                    + "This is a limit of every destruction tool, not a fault of this one.";
        }
        return raw;
    }

    /** A readable timestamp in the reader's own time zone, with the UTC value kept alongside. */
    static String timestamp(java.time.Instant instant) {
        DateTimeFormatter local = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss")
                .withZone(ZoneId.systemDefault());
        return local.format(instant) + " (local time)";
    }

    /** Pads a label so the proof view reads as a two-column list. */
    static String pair(String label, String value) {
        String v = value == null ? "" : value;
        StringBuilder sb = new StringBuilder(label);
        while (sb.length() < 26) sb.append(' ');
        sb.append(v);
        return sb.toString();
    }
}
