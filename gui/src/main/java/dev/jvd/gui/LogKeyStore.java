package dev.jvd.gui;

import dev.jvd.evidence.Signer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The two signing keys of the destruction log: the log's own key and the
 * independent witness key.
 *
 * <p>Why there are two: the log key signs the log; the witness key
 * co-signs the checkpoint with a <em>different</em> key, so a single
 * compromised operator cannot rewrite history unnoticed (Assumption 8). The
 * log module enforces the independence rule itself — it refuses to co-sign a
 * checkpoint when the witness key equals the log key.
 *
 * <p>Key custody note (same demo-grade caveat as the CLI): the private keys
 * are stored beside the log, Base64-encoded, in the log directory. They are
 * generated on first use and reused afterwards so that the log keeps a
 * continuous history across sessions. Production custody belongs in a
 * KMS/HSM; what the code enforces regardless is that the two keys differ.
 */
public final class LogKeyStore {

    public static final String LOG_PRIVATE = "log.key";
    public static final String LOG_PUBLIC = "log.pub";
    public static final String WITNESS_PRIVATE = "witness.key";
    public static final String WITNESS_PUBLIC = "witness.pub";

    private final Path dir;

    public LogKeyStore(Path dir) {
        this.dir = dir;
    }

    /** The log's signing keypair, created on first use. */
    public KeyPair logKeys() throws Exception {
        return loadOrCreate(LOG_PRIVATE, LOG_PUBLIC);
    }

    /** The independent witness keypair, created on first use. */
    public KeyPair witnessKeys() throws Exception {
        return loadOrCreate(WITNESS_PRIVATE, WITNESS_PUBLIC);
    }

    /** The log's public key, or null when the log has never been used. */
    public PublicKey logPublicKey() {
        return readPublic(LOG_PUBLIC);
    }

    /** The witness's public key, or null when the log has never been used. */
    public PublicKey witnessPublicKey() {
        return readPublic(WITNESS_PUBLIC);
    }

    private KeyPair loadOrCreate(String privName, String pubName) throws Exception {
        Path priv = dir.resolve(privName);
        Path pub = dir.resolve(pubName);
        if (Files.exists(priv) && Files.exists(pub)) {
            PrivateKey pk = KeyFactory.getInstance("Ed25519").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(priv).strip())));
            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(Files.readString(pub).strip())));
            return new KeyPair(publicKey, pk);
        }
        KeyPair kp = Signer.generateKeyPair();
        Files.createDirectories(dir);
        Files.writeString(priv, Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()),
                StandardCharsets.UTF_8);
        Files.writeString(pub, Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()),
                StandardCharsets.UTF_8);
        return kp;
    }

    private PublicKey readPublic(String name) {
        try {
            Path pub = dir.resolve(name);
            if (!Files.exists(pub)) return null;
            return KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(Files.readString(pub).strip())));
        } catch (Exception e) {
            return null;
        }
    }
}
