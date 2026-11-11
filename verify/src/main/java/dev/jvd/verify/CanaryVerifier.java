package dev.jvd.verify;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the three-child canary verification protocol (report §6.2, SPIKES H4):
 * <ol>
 *   <li><b>Target</b> child in the requested mode, scanned while it sleeps after READY.</li>
 *   <li><b>Positive control</b>: a NOWIPE child with the same nonce — its canary
 *       MUST be found, otherwise the scanner proved nothing.</li>
 *   <li><b>Negative control</b>: a child with a different nonce, scanned for the
 *       target pattern — it MUST NOT be found.</li>
 * </ol>
 * A run with a failed control is {@link Status#INVALID} and yields no
 * assurance (Assurance caps it at A0). "Found nothing" without controls is
 * meaningless — that is gap G2 in the report, enforced here in code.
 */
public final class CanaryVerifier {

    public enum Result { FOUND, NOT_FOUND, NOT_CHECKABLE }
    public enum Status { VALID, INVALID }

    public record VerificationOutcome(Result canaryResult,
                                      Status status,
                                      boolean positiveControlOk,
                                      boolean negativeControlOk,
                                      boolean controlsValid,
                                      String detail) {}

    /**
     * Test hook: when true, the positive control is scanned for a wrong
     * (random) pattern, deterministically breaking it so tests can exercise
     * the INVALID path. Package-private on purpose.
     */
    boolean sabotagePositiveControl = false;

    public VerificationOutcome run(String gc, String mode) {
        String nonce = Canary.randomNonce();
        byte[] pattern = Canary.patternFromNonce(nonce);

        // Target child
        Result targetResult;
        String detail;
        try {
            Map<String, Integer> counts = scanChild(gc, mode, nonce, pattern);
            int total = ProcScanner.totalCount(counts);
            targetResult = total > 0 ? Result.FOUND : Result.NOT_FOUND;
            detail = "target(" + mode + ") counts=" + counts;
        } catch (ProcScanner.ScanException e) {
            targetResult = Result.NOT_CHECKABLE;
            detail = "target scan NOT_CHECKABLE: " + e.getMessage();
        } catch (Exception e) {
            targetResult = Result.NOT_CHECKABLE;
            detail = "target child failed: " + e;
        }

        // Positive control: NOWIPE child, same nonce, MUST be found
        boolean positiveOk;
        try {
            byte[] scanPattern = sabotagePositiveControl ? Canary.randomCanary() : pattern;
            Map<String, Integer> counts = scanChild(gc, "NOWIPE", nonce, scanPattern);
            positiveOk = ProcScanner.totalCount(counts) > 0;
            detail += "; positiveControl counts=" + counts;
        } catch (Exception e) {
            positiveOk = false;
            detail += "; positiveControl failed: " + e.getMessage();
        }

        // Negative control: different nonce, target pattern MUST NOT be found
        boolean negativeOk;
        try {
            String otherNonce = Canary.randomNonce();
            Map<String, Integer> counts = scanChild(gc, "NOWIPE", otherNonce, pattern);
            negativeOk = ProcScanner.totalCount(counts) == 0;
            detail += "; negativeControl counts=" + counts;
        } catch (Exception e) {
            negativeOk = false;
            detail += "; negativeControl failed: " + e.getMessage();
        }

        boolean controlsValid = positiveOk && negativeOk;
        Status status = controlsValid ? Status.VALID : Status.INVALID;
        if (!controlsValid && targetResult != Result.NOT_CHECKABLE) {
            detail += "; controls INVALID -> result carries no assurance";
        }
        return new VerificationOutcome(targetResult, status, positiveOk, negativeOk, controlsValid, detail);
    }

    /** Launches a victim child, waits for READY, scans it while it sleeps, waits for exit. */
    private Map<String, Integer> scanChild(String gc, String mode, String nonce, byte[] pattern) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = classpathOfVictim();
        ProcessBuilder pb = new ProcessBuilder(javaBin, gcFlag(gc), "-Xmx64m", "-cp", classpath,
                VictimMain.class.getName(), mode);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try {
            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write((nonce + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            BufferedReader stdout = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            boolean ready = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            String line;
            while (System.nanoTime() < deadline && (line = stdout.readLine()) != null) {
                if (line.contains("READY")) { ready = true; break; }
            }
            if (!ready) throw new IllegalStateException("victim child did not print READY");
            Map<String, Integer> counts = ProcScanner.scanPid(proc.pid(), pattern);
            proc.destroyForcibly(); // scan complete; do not wait out the child's backstop sleep
            proc.waitFor(10, TimeUnit.SECONDS);
            return counts;
        } finally {
            proc.destroyForcibly();
        }
    }

    static String gcFlag(String gc) {
        if (gc == null || gc.isBlank() || gc.equalsIgnoreCase("default")) return "-XX:+UseG1GC";
        if (gc.startsWith("-XX:")) return gc;
        return switch (gc.toUpperCase()) {
            case "G1" -> "-XX:+UseG1GC";
            case "SERIAL" -> "-XX:+UseSerialGC";
            case "PARALLEL" -> "-XX:+UseParallelGC";
            case "ZGC" -> "-XX:+UseZGC";
            case "SHENANDOAH" -> "-XX:+UseShenandoahGC";
            default -> "-XX:+UseG1GC";
        };
    }

    /** Classpath entry (dir or jar) that contains VictimMain, so child JVMs work under Surefire and from jars. */
    static String classpathOfVictim() throws Exception {
        URL loc = VictimMain.class.getProtectionDomain().getCodeSource().getLocation();
        return Path.of(loc.toURI()).toString();
    }
}
