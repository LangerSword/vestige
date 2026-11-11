package dev.jvd.gui;

import java.nio.file.Path;

/**
 * The outcome of a destruction run, in the shape the UI needs.
 *
 * <p>Three outcomes are distinguished because they mean different things to
 * the person who pressed the button:
 * <ul>
 *   <li>{@link Status#DESTROYED} — the file is gone and the app verified it.</li>
 *   <li>{@link Status#ROLLED_BACK} — something went wrong before the
 *       destruction was made final, so the transaction restored the original
 *       file. <b>The user's data is still there.</b> This is the documented
 *       rollback path of the transaction module (PLAN §4): before COMMIT
 *       nothing is lost.</li>
 *   <li>{@link Status#FAILED} — the run failed and the file's state could not
 *       be confirmed either way. The UI says so plainly.</li>
 * </ul>
 */
public record DestroyResult(Status status,
                            String fileName,
                            boolean succeeded,
                            String headline,
                            String whatWasVerified,
                            String limitation,
                            Path evidenceRecordPath,
                            Path logDir,
                            ProofView proof,
                            java.util.List<StageEvent> events,
                            String errorMessage) {

    public enum Status { DESTROYED, ROLLED_BACK, FAILED }

    /** True when the file is really gone and the destruction was verified. */
    public boolean destroyed() {
        return status == Status.DESTROYED;
    }
}
