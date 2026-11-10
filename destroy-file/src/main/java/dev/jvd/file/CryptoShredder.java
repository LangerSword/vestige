package dev.jvd.file;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Per-file crypto-shredding (Purge-like at the logical level).
 *
 * <p>Each file gets a fresh AES-256 data-encryption key (DEK). Content is
 * encrypted with AES-256-GCM; the DEK is wrapped with a caller-supplied
 * key-encryption key (KEK) using AES KeyWrap ("AESWrap"). The ciphertext file
 * contains only GCM ciphertext (IV travels in {@link ShredResult}).
 *
 * <p><strong>Shredding = destroying the wrapped DEK record.</strong> The caller
 * owns the {@link ShredResult}: wipe {@code wrappedDek} (and drop the record)
 * and the ciphertext becomes unrecoverable noise — the same model as K-7/K-12
 * prior art. This class never persists key material itself.
 */
public final class CryptoShredder {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int DEK_BYTES = 32;   // AES-256
    private static final int IV_BYTES = 12;    // GCM standard
    private static final int GCM_TAG_BITS = 128;

    private CryptoShredder() {}

    /** Result of an encryption: everything needed to decrypt, nothing persisted by this class. */
    public static final class ShredResult {
        public final Path ciphertext;
        public byte[] wrappedDek;
        public final byte[] iv;

        ShredResult(Path ciphertext, byte[] wrappedDek, byte[] iv) {
            this.ciphertext = ciphertext;
            this.wrappedDek = wrappedDek;
            this.iv = iv;
        }

        /** The shredding action: overwrite the wrapped DEK in place. */
        public void destroyKey() {
            if (wrappedDek != null) Arrays.fill(wrappedDek, (byte) 0);
        }
    }

    public static ShredResult encryptFile(Path in, Path out, byte[] kek)
            throws IOException, GeneralSecurityException {
        byte[] dek = new byte[DEK_BYTES];
        RNG.nextBytes(dek);
        byte[] iv = new byte[IV_BYTES];
        RNG.nextBytes(iv);
        try {
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = Files.readAllBytes(in);
            byte[] ct = gcm.doFinal(plain);
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.write(out, ct);

            Cipher wrap = Cipher.getInstance("AESWrap");
            wrap.init(Cipher.WRAP_MODE, new SecretKeySpec(kek, "AES"));
            byte[] wrapped = wrap.wrap(new SecretKeySpec(dek, "AES"));
            return new ShredResult(out, wrapped, iv);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /**
     * Decrypts a file produced by {@link #encryptFile}. Throws if the wrapped
     * DEK has been destroyed/altered (unwrap fails) or the ciphertext was
     * tampered with (GCM tag fails) — that throw <em>is</em> the post-commit
     * validation signal for a completed shred.
     */
    public static byte[] decrypt(Path ct, byte[] wrappedDek, byte[] kek, byte[] iv)
            throws IOException, GeneralSecurityException {
        Cipher wrap = Cipher.getInstance("AESWrap");
        wrap.init(Cipher.UNWRAP_MODE, new SecretKeySpec(kek, "AES"));
        SecretKeySpec dek = (SecretKeySpec) wrap.unwrap(wrappedDek, "AES", Cipher.SECRET_KEY);
        try {
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return gcm.doFinal(Files.readAllBytes(ct));
        } finally {
            try { dek.destroy(); } catch (Exception ignored) { /* JDK SecretKeySpec.destroy throws DestroyFailedException (SPIKES C-9); the DEK copy here goes out of scope regardless */ }
        }
    }
}
