package dev.jvd.gui;

/**
 * One line of the "show me the proof" view: a friendly label and the value
 * behind it.
 *
 * @param label what the value is, in plain language
 * @param value the value, also in plain language (never a raw signature blob)
 */
public record ProofLine(String label, String value) {
}
