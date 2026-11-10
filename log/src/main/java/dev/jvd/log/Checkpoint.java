package dev.jvd.log;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * A signed tree checkpoint, C2SP-style text format:
 * <pre>
 * jvd-log
 * &lt;tree size&gt;
 * &lt;root hash, base64&gt;
 * &lt;timestamp, epoch millis&gt;
 * signature: &lt;base64 Ed25519 by the log key&gt;
 * witness: &lt;base64 Ed25519 by the witness key&gt;   (optional)
 * </pre>
 * The signature covers the first four lines joined by '\n'. The witness
 * co-signature covers the same body plus the log signature line
 * (C2SP cosignature style: the witness attests to the signed checkpoint it saw).
 *
 * <p>Key independence (Assumption 8): {@link #witnessCosign} refuses to
 * co-sign when the witness public key equals the log public key — a
 * "witness" holding the operator's own key attests to nothing.
 */
public final class Checkpoint {

    public static final String ORIGIN = "jvd-log";

    public final long size;
    public final byte[] root;
    public final long timestampMillis;
    public String logSignature;       // base64
    public String witnessSignature;   // base64, nullable

    public Checkpoint(long size, byte[] root, long timestampMillis) {
        this.size = size;
        this.root = root.clone();
        this.timestampMillis = timestampMillis;
    }

    public String body() {
        return ORIGIN + "\n" + size + "\n" + Base64.getEncoder().encodeToString(root) + "\n" + timestampMillis;
    }

    public Checkpoint sign(PrivateKey logKey) {
        this.logSignature = edSign(body(), logKey);
        return this;
    }

    public Checkpoint witnessCosign(PrivateKey witnessKey, PublicKey witnessPublic, PublicKey logPublic) {
        if (logSignature == null) throw new IllegalStateException("checkpoint must be signed before cosigning");
        if (java.util.Arrays.equals(witnessPublic.getEncoded(), logPublic.getEncoded())) {
            throw new IllegalArgumentException(
                    "witness key equals log key: refusing to cosign (key independence required, Assumption 8)");
        }
        this.witnessSignature = edSign(body() + "\nsignature: " + logSignature, witnessKey);
        return this;
    }

    public boolean verifyLogSignature(PublicKey logPublic) {
        return logSignature != null && edVerify(body(), logSignature, logPublic);
    }

    public boolean verifyWitnessSignature(PublicKey witnessPublic) {
        return witnessSignature != null && logSignature != null
                && edVerify(body() + "\nsignature: " + logSignature, witnessSignature, witnessPublic);
    }

    public String toText() {
        StringBuilder sb = new StringBuilder(body());
        if (logSignature != null) sb.append("\nsignature: ").append(logSignature);
        if (witnessSignature != null) sb.append("\nwitness: ").append(witnessSignature);
        return sb.toString();
    }

    public static Checkpoint parse(String text) {
        String[] lines = text.strip().split("\n");
        if (lines.length < 4 || !lines[0].equals(ORIGIN)) throw new IllegalArgumentException("bad checkpoint origin");
        Checkpoint cp = new Checkpoint(
                Long.parseLong(lines[1].strip()),
                Base64.getDecoder().decode(lines[2].strip()),
                Long.parseLong(lines[3].strip()));
        for (int i = 4; i < lines.length; i++) {
            String ln = lines[i].strip();
            if (ln.startsWith("signature: ")) cp.logSignature = ln.substring("signature: ".length());
            else if (ln.startsWith("witness: ")) cp.witnessSignature = ln.substring("witness: ".length());
        }
        return cp;
    }

    private static String edSign(String data, PrivateKey key) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean edVerify(String data, String b64, PublicKey key) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(data.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(b64));
        } catch (Exception e) {
            return false;
        }
    }
}
