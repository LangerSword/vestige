package dev.jvd.gui;

import java.nio.file.Path;

/**
 * Where the desktop app keeps its working files.
 *
 * <p>Two directories, with different jobs:
 * <ul>
 *   <li>{@code workRoot} — scratch space for the destruction transaction
 *       (the encrypted vault, the write-ahead journal, the evidence record).
 *       One fresh sub-directory per run, so a run never inherits another run's
 *       journal state.</li>
 *   <li>{@code logDir} — the append-only, witnessed log of destruction
 *       records. This one is <em>shared across sessions on purpose</em>: the
 *       value of the log is that it grows over time and that a later offline
 *       verifier can re-check the whole history (PLAN §3, module {@code log}).</li>
 * </ul>
 *
 * <p>Default location is {@code ~/.jvd}. The user is never asked to choose it:
 * the point of the GUI is that the operator does not have to think about where
 * the machinery lives.
 */
public record AppPaths(Path workRoot, Path logDir) {

    /** The folder under the user's home that holds everything this app writes. */
    public static final String DEFAULT_HOME_DIR = ".jvd";

    public AppPaths {
        workRoot = workRoot.toAbsolutePath();
        logDir = logDir.toAbsolutePath();
    }

    /** The standard location: {@code ~/.jvd/work} and {@code ~/.jvd/log}. */
    public static AppPaths defaultPaths() {
        Path home = Path.of(System.getProperty("user.home"));
        Path base = home.resolve(DEFAULT_HOME_DIR);
        return new AppPaths(base.resolve("work"), base.resolve("log"));
    }
}
