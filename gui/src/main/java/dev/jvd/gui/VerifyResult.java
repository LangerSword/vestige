package dev.jvd.gui;

/**
 * The outcome of an offline evidence check, in plain language.
 *
 * @param ok            true when the evidence is intact and consistent
 * @param headline      the one-line verdict for the user
 * @param explanation   what was checked, in ordinary words
 * @param rawOutput     the verifier's own technical output (kept for the
 *                      "details" area; never shown by default)
 */
public record VerifyResult(boolean ok, String headline, String explanation, String rawOutput) {

    /** "Everything checks out" / "Something is wrong" / "We could not check". */
    public enum Outcome { OK, TAMPERED, NOT_CHECKABLE }
}
