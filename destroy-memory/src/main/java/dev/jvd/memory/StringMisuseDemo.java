package dev.jvd.memory;

/**
 * Deliberate anti-pattern, kept for tests and teaching: a secret held in a
 * {@link String}. Strings are immutable, so the secret <em>cannot</em> be wiped;
 * {@link #attemptWipe()} can only drop the reference, and the character data
 * stays in the heap (and possibly in stale GC copies, intern pools and any
 * encoded copies) until overwritten by chance.
 *
 * <p>Assumption 13: this is a class with tests, not just a demo print — a test
 * is evidence. The verify module's STRING victim mode shows the canary held
 * this way being found in process memory after the "wipe attempt".
 */
public final class StringMisuseDemo {

    private String secret;

    public StringMisuseDemo(String secret) {
        this.secret = secret;
    }

    /** The best anyone can do with a String: drop the reference. The data is NOT wiped. */
    public void attemptWipe() {
        this.secret = null;
    }

    /** Test hook: the current field value (null after {@link #attemptWipe()}). */
    public String peekForTest() {
        return secret;
    }

    /** True if this object still holds a reference; says nothing about heap residue. */
    public boolean holdsReference() {
        return secret != null;
    }
}
