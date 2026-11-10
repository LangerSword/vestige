package dev.jvd.txn;

import java.nio.file.Path;
import java.util.HexFormat;

/**
 * Crash-injection driver for kill -9 tests: runs the destruction flow and
 * halts the JVM ({@code Runtime.halt(137)}, equivalent to SIGKILL — no
 * shutdown hooks, no cleanup) immediately after the journal entry for the
 * requested state has been fsynced.
 *
 * <p>Args: {@code <workDir> <stopAfterState> <sourceFile> <kekHex>}.
 * With stopAfterState {@code NONE} the flow runs to COMMIT and exits normally.
 */
public final class CrashMain {

    public static void main(String[] args) throws Exception {
        Path workDir = Path.of(args[0]);
        String stopAfter = args[1];
        Path source = Path.of(args[2]);
        byte[] kek = HexFormat.of().parseHex(args[3]);

        DestructionTransaction txn = new DestructionTransaction(workDir, source, kek);
        txn.stage();
        haltIf(stopAfter, "STAGED");
        txn.vault();
        haltIf(stopAfter, "VAULTED");
        txn.scrub();
        haltIf(stopAfter, "SCRUBBED");
        txn.validatePreCommit();
        haltIf(stopAfter, "VALIDATED_PRECOMMIT");
        txn.commit();
        haltIf(stopAfter, "COMMITTED");
        System.out.println("FLOW COMPLETE state=" + txn.state());
    }

    private static void haltIf(String stopAfter, String state) {
        if (stopAfter.equals(state)) {
            System.out.println("HALTING after " + state);
            System.out.flush();
            Runtime.getRuntime().halt(137);
        }
    }
}
