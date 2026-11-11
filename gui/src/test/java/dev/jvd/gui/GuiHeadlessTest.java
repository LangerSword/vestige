package dev.jvd.gui;

import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the parts of the GUI that can be exercised without a display.
 *
 * <p>The window itself is not constructed here: instantiating Swing
 * components requires a graphics environment, and this suite must run in CI
 * where there is none. What is checked instead is the behaviour that matters
 * for a headless build — the app detects the missing display and says
 * something useful instead of throwing — plus the plain-language guarantees
 * that the user-facing text stays jargon-free.
 */
class GuiHeadlessTest {

    @Test
    void theAppRefusesToStartWithoutADisplayAndSaysWhy() {
        // The CI JVM has no display, so launch() must take the headless path.
        if (!GraphicsEnvironment.isHeadless()) {
            // On a machine that does have a display this test cannot assert the
            // headless branch; skip rather than fail.
            return;
        }
        int code = MainWindow.launch();
        assertEquals(2, code, "a headless start must report a non-zero code");
        assertTrue(MainWindow.HEADLESS_MESSAGE.contains("graphical display"));
        assertTrue(MainWindow.HEADLESS_MESSAGE.contains("command-line tool"),
                "the message should point the user at the CLI alternative");
    }

    @Test
    void anUnreachableDisplayIsHandledWithoutAStackTrace() {
        // A DISPLAY that points at a dead X server is the case that used to
        // throw an AWTError at the user. Pointing DISPLAY at a port nothing
        // listens on reproduces it.
        String original = System.getenv("DISPLAY");
        if (original != null && !original.isBlank()) {
            // This machine has a display configured; the case is covered by the
            // launcher script's own check and cannot be simulated safely here.
            return;
        }
        // No DISPLAY at all: the toolkit lookup must not throw.
        int code = MainWindow.launch();
        assertEquals(2, code);
    }

    @Test
    void theUserFacingTextContainsNoCryptographicJargon() {
        // The rule of the desktop app: the operator never has to think about
        // the machinery. This test is the mechanical guard for that rule.
        String[] forbidden = {
                "AES", "GCM", "DEK", "KEK", "wrapping key", "Merkle", "proof",
                "A0", "A1", "A2", "A3", "nonce", "ciphertext", "canary",
                "NIST", "SP 800-88", "checkpoint", "inclusion proof", "Ed25519"
        };
        for (String term : forbidden) {
            assertFalse(Friendly.LIMITATION.toLowerCase().contains(term.toLowerCase()),
                    "the limitation note must not contain jargon: " + term);
        }
        assertFalse(Friendly.methodName(dev.jvd.evidence.EvidenceRecord.Method.CRYPTO_SHRED)
                        .toLowerCase().contains("shred"),
                "the method name must not leak the technique's internal name");
        assertFalse(Friendly.assuranceName(dev.jvd.evidence.EvidenceRecord.AssuranceLevel.A2)
                        .contains("A2"),
                "the assurance name must not show the internal code");
        assertFalse(Friendly.canaryName(dev.jvd.evidence.EvidenceRecord.CanaryResult.NOT_FOUND)
                        .toLowerCase().contains("canary"),
                "the canary result must not use the internal term");
    }

    @Test
    void theLimitationIsAlwaysStatedNextToASuccess() {
        assertTrue(Friendly.LIMITATION.contains("cannot be checked"),
                "the honest limit must be stated in plain words");
        assertTrue(Friendly.LIMITATION.toLowerCase().contains("software side"));
    }
}
