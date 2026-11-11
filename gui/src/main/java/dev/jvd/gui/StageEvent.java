package dev.jvd.gui;

/**
 * One step of the destruction pipeline, in language a non-technical person can
 * read.
 *
 * <p>Every stage event originates from a <em>real</em> event emitted by the
 * destruction transaction (see {@code DestructionTransaction.setEvidenceSink})
 * or from a real point in the surrounding runner code. Nothing here is
 * simulated or time-shifted: if a stage appears on screen, that work really
 * happened.
 *
 * @param step   1-based position in the pipeline, so the UI can show
 *               "3. Destroying the original" and keep the numbering stable
 *               even when a stage is re-reported with a richer detail
 * @param title  the plain-language headline of the step
 * @param detail one friendly sentence explaining what just happened
 */
public record StageEvent(int step, String title, String detail) {

    /** The headline on its own, e.g. {@code "3. Destroying the original"}. */
    public String headline() {
        return step + ". " + title;
    }

    /** Headline plus detail, the form shown in the progress log. */
    public String line() {
        return detail == null || detail.isBlank() ? headline() : headline() + " — " + detail;
    }
}
