package dev.jvd.verify;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Child victim JVM for canary verification (SPIKES H4 design: the parent
 * scanner holds the pattern; the child derives it from a nonce received over
 * stdin — never argv, whose hex copies polluted earlier spikes).
 *
 * <p>Protocol: read one line (nonce hex string) from stdin; the secret under
 * test is {@code SHA-256(nonce UTF-8 bytes)} (see {@link Canary#patternFromNonce}).
 * Modes:
 * <ul>
 *   <li>{@code WIPE} — hold the secret in a byte[], Arrays.fill it with 0, drop it.</li>
 *   <li>{@code NOWIPE} — hold it, drop it without filling (positive control: must be FOUND).</li>
 *   <li>{@code STRING} — hold it as a hex String, null the reference (can never be wiped).</li>
 * </ul>
 * Then print {@code READY}, sleep so the parent can scan, and exit.
 */
public final class VictimMain {

    /** Backstop sleep: the parent scans, then kills the child; this only bounds a leaked child. */
    public static final long SLEEP_MS = 20_000;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "NOWIPE";
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String nonce = in.readLine();
        if (nonce == null) return;
        byte[] secret = Canary.patternFromNonce(nonce.strip());
        switch (mode) {
            case "WIPE" -> {
                byte[] held = secret.clone();
                Arrays.fill(held, (byte) 0);
                held = null;
            }
            case "STRING" -> {
                String s = HexFormat.of().formatHex(secret);
                s = null;
            }
            default -> { // NOWIPE: drop without wiping (positive control)
                byte[] held = secret.clone();
                held = null;
            }
        }
        Arrays.fill(secret, (byte) 0);
        System.out.println("READY");
        System.out.flush();
        Thread.sleep(SLEEP_MS);
    }
}
