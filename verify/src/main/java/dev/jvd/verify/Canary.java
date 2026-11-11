package dev.jvd.verify;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A canary is a random pattern planted in a victim process and searched for
 * after a destruction attempt. It is always handled as a <em>family</em> of
 * encodings (deviation D6): Java secrets live in memory as raw bytes, as
 * UTF-16LE/UTF-16BE (String/char[]), or as ASCII hex — searching only the raw
 * form gives false NOT_FOUND for String-held secrets (SPIKES H4 measured 4–12
 * stray hex copies from argv delivery alone).
 *
 * <p>Family = {raw bytes, ASCII-hex bytes} × {identity, UTF-16LE, UTF-16BE}.
 */
public final class Canary {

    public static final int RANDOM_BYTES = 48;

    private Canary() {}

    public static byte[] randomCanary() {
        byte[] b = new byte[RANDOM_BYTES];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /** The searched pattern for the victim protocol: SHA-256 of the nonce string's UTF-8 bytes. */
    public static byte[] patternFromNonce(String nonce) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String randomNonce() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** All encoding variants of a pattern, keyed by variant name. */
    public static Map<String, byte[]> family(byte[] pattern) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        byte[] hex = HexFormat.of().formatHex(pattern).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.put("raw", pattern.clone());
        out.put("raw-utf16le", utf16(pattern, true));
        out.put("raw-utf16be", utf16(pattern, false));
        out.put("hex", hex);
        out.put("hex-utf16le", utf16(hex, true));
        out.put("hex-utf16be", utf16(hex, false));
        return out;
    }

    private static byte[] utf16(byte[] in, boolean littleEndian) {
        byte[] out = new byte[in.length * 2];
        for (int i = 0; i < in.length; i++) {
            if (littleEndian) {
                out[2 * i] = in[i];
                out[2 * i + 1] = 0;
            } else {
                out[2 * i] = 0;
                out[2 * i + 1] = in[i];
            }
        }
        return out;
    }
}
