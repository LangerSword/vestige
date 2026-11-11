package dev.jvd.gui;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The session's key-encryption key (KEK), generated fresh when the app starts
 * and never shown to the user.
 *
 * <p><b>Why this is not asked of the operator.</b> A KEK is an implementation
 * detail of the crypto-shred technique: the user asked for their file to be
 * destroyed, not for a key. Asking for one would push the "hood" onto them
 * and would be a guarantee of nothing — a KEK typed by hand is a KEK that
 * ends up in a password manager, a screenshot, or a chat window. So the app
 * generates a strong random one itself and shows only an abstract, masked
 * placeholder (see {@link #masked()}).
 *
 * <p><b>How it is stored.</b> The in-memory copy lives here and is handed to
 * the destruction transaction per run. The transaction module additionally
 * writes the KEK into {@code txn.meta} inside its working directory — that is
 * the transaction module's own documented behaviour and it is deliberate: it
 * is what lets a later process recover a job that was interrupted before it
 * finished (see {@code DestructionTransaction.recover} and the "Key custody
 * note" in that class's javadoc). The working directory is therefore a
 * trusted location in this app's threat model, exactly as the transaction
 * module documents it.
 *
 * <p>Two things limit the exposure of that on-disk copy:
 * <ul>
 *   <li>Once a file is destroyed, the key that could have opened its copy is
 *       destroyed too (that <em>is</em> the destruction), so the KEK on disk
 *       can no longer open anything.</li>
 *   <li>The in-memory copy is wiped with {@link #wipe()} when the session
 *       ends.</li>
 * </ul>
 *
 * <p>For a production deployment the honest answer is a KMS/HSM holding the
 * KEK; this app is a local, single-user tool and says so rather than implying
 * key custody it does not have.
 */
public final class SessionKey {

    /** AES-256: the key size the destruction technique expects. */
    public static final int KEY_BYTES = 32;

    private static final SecureRandom RNG = new SecureRandom();

    private final byte[] bytes;

    private SessionKey(byte[] bytes) {
        this.bytes = bytes;
    }

    /** Generates a new random session key from the platform's secure RNG. */
    public static SessionKey generate() {
        byte[] b = new byte[KEY_BYTES];
        RNG.nextBytes(b);
        return new SessionKey(b);
    }

    /** A defensive copy for handing to the transaction layer. */
    public byte[] copy() {
        return bytes.clone();
    }

    /** Overwrites the in-memory copy. Called when the session ends. */
    public void wipe() {
        Arrays.fill(bytes, (byte) 0);
    }

    /**
     * The abstract form shown on screen: a placeholder that conveys "there is
     * a key, and it is protected", without revealing anything.
     */
    public String masked() {
        return "•••••••• •••••••• •••••••• ••••••••";
    }
}
