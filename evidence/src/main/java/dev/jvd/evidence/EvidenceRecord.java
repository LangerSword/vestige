package dev.jvd.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * One destruction-evidence record (report §6.3, with Phase 1 corrections).
 *
 * <p>NIST SP 800-88 Rev.2 terminology is kept exact: <b>verification</b> holds
 * machine results (did the technique complete, what did the canary verifier
 * find, were the controls valid); <b>validation</b> holds the separate
 * risk-based <em>decision</em> over those results (Assumption 5 / deviation
 * D1). The library never silently merges the two.
 *
 * <p>Rev.2's Certificate of Sanitization (Appendix C) is media-centric
 * (manufacturer, model, serial, media type...). This record operates on
 * logical subjects, so the fields are mapped to logical analogues and the
 * mapping is stated in {@link #mediaAnalogueNote} (deviation D10):
 * media serial → {@code subjectHash}, media type → method, tool+version →
 * {@code toolVersion}, verifying/validating personnel → {@code decider}.
 *
 * <p>JSON is hand-rolled (Assumption 4). The canonical form used for signing
 * is {@link #toJson()} without the {@code signature} field; field order is
 * fixed, so a parsed record re-serialises to the identical canonical bytes.
 */
public final class EvidenceRecord {

    public enum Method { CLEAR_OVERWRITE, CRYPTO_SHRED, MEMORY_WIPE }
    public enum VerificationStatus { COMPLETED, FAILED }
    public enum CanaryResult { FOUND, NOT_FOUND, NOT_CHECKABLE, INVALID }
    public enum ValidationDecision { ACCEPTED, REJECTED, PENDING }
    public enum AssuranceLevel { A0, A1, A2, A3 }

    public static final int SCHEMA_VERSION = 1;
    public static final String MEDIA_ANALOGUE_NOTE =
            "Appendix C mapping: mediaSerial->subjectHash (salted SHA-256 of the logical subject), " +
            "mediaType->method, toolAndVersion->toolVersion, verifyingPersonnel->verification, " +
            "validatingPersonnel->validation.decider. Physical media fields have no logical equivalent; " +
            "physical media is NOT verified by this tool.";

    public int schemaVersion = SCHEMA_VERSION;
    public String eventId = UUID.randomUUID().toString();
    public Instant timestampUtc = Instant.now();
    /** Salted SHA-256 hex of the destroyed subject's identity. */
    public String subjectHash = "";
    public Method method = Method.CRYPTO_SHRED;
    public String toolVersion = "jvd-0.1.0";

    // verification (machine results)
    public VerificationStatus verificationStatus = VerificationStatus.COMPLETED;
    public CanaryResult canaryResult = CanaryResult.NOT_CHECKABLE;
    public boolean controlsValid = false;
    public String verificationDetail = "";

    // validation (human/policy decision over the verification results)
    public ValidationDecision validationDecision = ValidationDecision.PENDING;
    public String validationDecider = null;
    public String validationRationale = "";

    public String residualRisk = "physical media NOT verified";
    public AssuranceLevel assuranceLevel = AssuranceLevel.A0;
    public String mediaAnalogueNote = MEDIA_ANALOGUE_NOTE;

    /** Base64 Ed25519 signature over the canonical JSON (record without this field). Not part of the canonical bytes. */
    public String signature = null;

    /** Salted subject hash helper: SHA-256(salt || subject). */
    public static String subjectHash(String salt, String subject) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest(subject.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Canonical JSON (never includes the signature). Deterministic field order. */
    public String toJson() {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        kv(sb, "schemaVersion", Integer.toString(schemaVersion)); sb.append(',');
        kv(sb, "eventId", q(eventId)); sb.append(',');
        kv(sb, "timestampUtc", q(timestampUtc.toString())); sb.append(',');
        kv(sb, "subjectHash", q(subjectHash)); sb.append(',');
        kv(sb, "method", q(method.name())); sb.append(',');
        kv(sb, "toolVersion", q(toolVersion)); sb.append(',');
        sb.append("\"verification\":{");
        kv(sb, "status", q(verificationStatus.name())); sb.append(',');
        kv(sb, "canaryResult", q(canaryResult.name())); sb.append(',');
        kv(sb, "controlsValid", Boolean.toString(controlsValid)); sb.append(',');
        kv(sb, "detail", q(verificationDetail));
        sb.append("},");
        sb.append("\"validation\":{");
        kv(sb, "decision", q(validationDecision.name())); sb.append(',');
        kv(sb, "decider", validationDecider == null ? "null" : q(validationDecider)); sb.append(',');
        kv(sb, "rationale", q(validationRationale));
        sb.append("},");
        kv(sb, "residualRisk", q(residualRisk)); sb.append(',');
        kv(sb, "assuranceLevel", q(assuranceLevel.name())); sb.append(',');
        kv(sb, "mediaAnalogue", q(mediaAnalogueNote));
        sb.append('}');
        return sb.toString();
    }

    /** JSON including the signature field, when present. This is the stored/transmitted form. */
    public String toSignedJson() {
        String base = toJson();
        if (signature == null) return base;
        return base.substring(0, base.length() - 1) + ",\"signature\":" + q(signature) + "}";
    }

    private static String q(String v) { return JsonMini.esc(v == null ? "" : v); }
    private static void kv(StringBuilder sb, String key, String rawValue) {
        sb.append(JsonMini.esc(key)).append(':').append(rawValue);
    }

    /** Parses a record produced by {@link #toJson()} or {@link #toSignedJson()}. Throws on malformed input. */
    @SuppressWarnings("unchecked")
    public static EvidenceRecord parse(String json) {
        Object root = JsonMini.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("record JSON must be an object");
        Map<String, Object> m = (Map<String, Object>) root;
        EvidenceRecord r = new EvidenceRecord();
        r.schemaVersion = ((Number) m.get("schemaVersion")).intValue();
        r.eventId = (String) m.get("eventId");
        r.timestampUtc = Instant.parse((String) m.get("timestampUtc"));
        r.subjectHash = (String) m.get("subjectHash");
        r.method = Method.valueOf((String) m.get("method"));
        r.toolVersion = (String) m.get("toolVersion");
        Map<String, Object> ver = (Map<String, Object>) m.get("verification");
        r.verificationStatus = VerificationStatus.valueOf((String) ver.get("status"));
        r.canaryResult = CanaryResult.valueOf((String) ver.get("canaryResult"));
        r.controlsValid = Boolean.TRUE.equals(ver.get("controlsValid"));
        r.verificationDetail = (String) ver.get("detail");
        Map<String, Object> val = (Map<String, Object>) m.get("validation");
        r.validationDecision = ValidationDecision.valueOf((String) val.get("decision"));
        r.validationDecider = (String) val.get("decider");
        r.validationRationale = (String) val.get("rationale");
        r.residualRisk = (String) m.get("residualRisk");
        r.assuranceLevel = AssuranceLevel.valueOf((String) m.get("assuranceLevel"));
        r.mediaAnalogueNote = (String) m.get("mediaAnalogue");
        Object sig = m.get("signature");
        r.signature = sig instanceof String ? (String) sig : null;
        return r;
    }
}
