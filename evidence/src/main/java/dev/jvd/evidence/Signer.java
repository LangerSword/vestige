package dev.jvd.evidence;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/** Ed25519 signing for {@link EvidenceRecord} (JDK built-in since Java 15). */
public final class Signer {

    private Signer() {}

    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Signs the record's canonical JSON and stores the Base64 signature on the record. */
    public static void sign(EvidenceRecord record, PrivateKey key) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(key);
            sig.update(record.toJson().getBytes(StandardCharsets.UTF_8));
            record.signature = Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** True iff the record carries a signature that verifies over its current canonical JSON. Any field edit breaks this. */
    public static boolean verify(EvidenceRecord record, PublicKey key) {
        if (record.signature == null) return false;
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(record.toJson().getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(record.signature));
        } catch (Exception e) {
            return false;
        }
    }
}
