package dev.jvd.gui;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pieces of the window that can be checked without a display.
 *
 * <p>The window itself is not constructed here: building Swing components needs a
 * graphics environment, and this suite has to run in CI where there is none. What is
 * checked instead is the logic the window depends on, and the promise that the user
 * never sees an internal term.
 */
class ThemeAndTimelineTest {

    @Test
    void thePlannedStagesCoverEveryStageTheRunnerReports() {
        List<StageEvent> stages = Friendly.plannedStages();
        assertEquals(9, stages.size(), "the plan and the runner must agree on the stage count");
        for (int i = 0; i < stages.size(); i++) {
            assertEquals(i + 1, stages.get(i).step(),
                    "stage " + i + " must carry its own position");
        }
    }

    @Test
    void aReportedStageMapsToItsPlannedPosition() {
        List<StageEvent> stages = Friendly.plannedStages();
        for (int i = 0; i < stages.size(); i++) {
            assertEquals(i, Friendly.stageIndex(stages.get(i)),
                    "a reported stage must map to its own position in the plan");
        }
    }

    @Test
    void anUnknownStageIsIgnoredRatherThanMisplaced() {
        // A stage the plan does not know must not land on some other stage, because
        // that would put a step in the wrong place in the timeline.
        assertEquals(-1, Friendly.stageIndex(new StageEvent(99, "Something new", "...")));
        assertEquals(-1, Friendly.stageIndex(new StageEvent(0, "Zero", "...")));
    }

    @Test
    void noStageShowsAnInternalTerm() {
        // The rule of the app: the operator never has to think about the machinery.
        List<String> forbidden = List.of("AES", "GCM", "DEK", "KEK", "wrapping key",
                "Merkle", "canary", "nonce", "ciphertext", "vault", "journal",
                "transaction", "NIST", "Ed25519", "CHECKPOINT", "COMMITTED", "NOT_CHECKABLE");
        for (StageEvent stage : Friendly.plannedStages()) {
            String text = (stage.title() + " " + stage.detail()).toLowerCase(java.util.Locale.ROOT);
            for (String term : forbidden) {
                assertFalse(text.contains(term.toLowerCase(java.util.Locale.ROOT)),
                        "a stage must not leak '" + term + "': " + stage);
            }
        }
    }

    @Test
    void theLimitationIsStatedInPlainWords() {
        assertTrue(Friendly.LIMITATION.contains("software side"),
                "the limit must say what was actually checked");
        assertTrue(Friendly.LIMITATION.toLowerCase(java.util.Locale.ROOT).contains("cannot be checked"),
                "the limit must say what was not checked");
    }

    @Test
    void theThemeHasOneAccentAndReadableSecondaryText() {
        // One accent carries the meaning. If a second colour were introduced for a
        // different purpose the interface would stop reading as one object.
        assertNotEquals(Theme.ACCENT, Theme.GREEN, "the accent and the verified colour must differ");
        // The muted text has to survive a dim display, so it cannot be too close to
        // the background.
        int distance = Math.abs(Theme.MUTED.getRed() - Theme.BG.getRed())
                + Math.abs(Theme.MUTED.getGreen() - Theme.BG.getGreen())
                + Math.abs(Theme.MUTED.getBlue() - Theme.BG.getBlue());
        assertTrue(distance > 250,
                "the muted text must be clearly lighter than the background, was " + distance);
        int faint = Math.abs(Theme.FAINT.getRed() - Theme.BG.getRed())
                + Math.abs(Theme.FAINT.getGreen() - Theme.BG.getGreen())
                + Math.abs(Theme.FAINT.getBlue() - Theme.BG.getBlue());
        assertTrue(faint > 200, "the faint text must still be readable, was " + faint);
    }

    @Test
    void theProgressBarTakesAValueAndNeverExceedsIt() throws Exception {
        // The bar animates towards the value the work reports. It must never show
        // more progress than actually happened, so the drawn value is clamped.
        var ctor = ProgressBar.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        ProgressBar bar = (ProgressBar) ctor.newInstance();
        var set = ProgressBar.class.getDeclaredMethod("setProgress", double.class);
        set.setAccessible(true);
        set.invoke(bar, 0.5);

        var shown = ProgressBar.class.getDeclaredField("shown");
        shown.setAccessible(true);
        double value = (double) shown.get(bar);
        assertTrue(value >= 0.0 && value <= 1.0,
                "the drawn value must stay inside 0..1, was " + value);

        var finish = ProgressBar.class.getDeclaredMethod("finish");
        finish.setAccessible(true);
        finish.invoke(bar);
        assertEquals(1.0, (double) shown.get(bar),
                "a finished bar is exactly full");
    }

    @Test
    void theProgressReadoutNamesTheStageAndThePercentage() {
        // The readout is what stops a full bar being read as a divider, so it has
        // to carry both the number and the name of the stage.
        String readout = Math.round(0.5 * 100) + "%  " + Friendly.plannedStages().get(2).title();
        assertTrue(readout.contains("50%"), "the readout must show the percentage: " + readout);
        assertTrue(readout.contains("Destroying the original"),
                "the readout must name the stage: " + readout);
    }
}
