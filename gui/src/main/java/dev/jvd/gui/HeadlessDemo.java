package dev.jvd.gui;

import dev.jvd.evidence.EvidenceRecord;
import dev.jvd.evidence.Signer;
import dev.jvd.log.Checkpoint;
import dev.jvd.log.PersistentLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A headless end-to-end demonstration of the desktop app's service layer.
 *
 * <p>It exists so the destruction path can be proven outside a graphical
 * session: it runs the exact same {@link DestroyRunner} the Swing layer calls,
 * on a real temporary file, and prints what happened — including the
 * evidence record and the offline verification result.
 *
 * <p>Run it with:
 * <pre>
 *   java -cp gui/target/gui-0.1.0.jar dev.jvd.gui.HeadlessDemo [fileToDestroy]
 * </pre>
 * With no argument it creates its own temporary file. It never needs a
 * display, so it is safe to run in CI.
 */
public final class HeadlessDemo {

    private HeadlessDemo() {}

    public static void main(String[] args) throws Exception {
        Path workRoot;
        Path logDir;
        Path source;
        boolean temporary;

        if (args.length > 0) {
            source = Path.of(args[0]).toAbsolutePath();
            workRoot = Files.createTempDirectory("jvd-gui-demo-work");
            logDir = Files.createTempDirectory("jvd-gui-demo-log");
            temporary = false;
        } else {
            Path dir = Files.createTempDirectory("jvd-gui-demo");
            source = dir.resolve("my-private-notes.txt");
            Files.writeString(source, """
                    This is the file the user wanted gone.
                    It holds something they would rather not keep.
                    """);
            workRoot = dir.resolve("work");
            logDir = dir.resolve("log");
            temporary = true;
        }

        System.out.println("=== jvd desktop app - headless demonstration ===");
        System.out.println("file to destroy : " + source);
        System.out.println("file exists     : " + Files.exists(source));
        System.out.println("file size       : " + (Files.exists(source) ? Files.size(source) : 0) + " bytes");
        System.out.println();

        AppPaths paths = new AppPaths(workRoot, logDir);
        SessionKey key = SessionKey.generate();
        System.out.println("session key     : " + key.masked() + "   (generated automatically, never shown)");
        System.out.println();

        DestroyRunner runner = new DestroyRunner(paths, key);

        System.out.println("--- what the user sees in the progress area ---");
        DestroyResult result = runner.destroy(source, e -> System.out.println("  " + e.line()));
        System.out.println();

        System.out.println("--- result panel ---");
        System.out.println("  File:            " + result.fileName());
        System.out.println("  Headline:        " + result.headline());
        System.out.println("  Verified:        " + result.whatWasVerified());
        System.out.println("  Limitation:      " + result.limitation());
        System.out.println("  Status:          " + result.status());
        if (result.errorMessage() != null) {
            System.out.println("  Error:           " + result.errorMessage());
        }
        System.out.println();

        System.out.println("--- proof panel ---");
        System.out.println(result.proof() == null ? "  (no proof - the run did not complete)" : result.proof().asText());
        System.out.println();

        System.out.println("--- what actually happened on disk ---");
        System.out.println("  source file still exists : " + Files.exists(source) + "   (must be false)");
        Path recordPath = result.evidenceRecordPath();
        System.out.println("  evidence record          : " + recordPath);
        System.out.println("  evidence record exists   : " + Files.exists(recordPath));
        Path workDir = recordPath.getParent();
        System.out.println("  kept ciphertext artefact : " + Files.exists(workDir.resolve("artifact.enc")));
        System.out.println("  key file (must be gone)  : " + Files.exists(workDir.resolve("vault.key")));
        System.out.println("  work files                : " + Files.list(workDir).map(p -> p.getFileName().toString()).sorted().toList());
        System.out.println();

        System.out.println("--- the signed record, as stored ---");
        String raw = Files.readString(recordPath, StandardCharsets.UTF_8);
        System.out.println("  " + raw);
        System.out.println();

        System.out.println("--- independent offline check ---");
        LogKeyStore store = new LogKeyStore(logDir);
        EvidenceRecord parsed = EvidenceRecord.parse(raw);
        System.out.println("  record signature valid  : " + Signer.verify(parsed, store.logPublicKey()));

        PersistentLog pl = PersistentLog.open(logDir);
        Checkpoint cp = pl.loadCheckpoint();
        System.out.println("  log size                 : " + pl.log().size());
        System.out.println("  checkpoint size          : " + (cp == null ? "none" : cp.size));
        System.out.println("  checkpoint signature ok  : " + (cp != null && cp.verifyLogSignature(store.logPublicKey())));
        System.out.println("  witness co-signature ok  : " + (cp != null && cp.verifyWitnessSignature(store.witnessPublicKey())));

        EvidenceVerifier verifier = new EvidenceVerifier(logDir);
        VerifyResult vr = verifier.verify(store.logPublicKey(), store.witnessPublicKey());
        System.out.println("  verdict                  : " + vr.headline());
        System.out.println("  explanation              : " + vr.explanation());
        System.out.println();

        // ---- tamper check: prove the check actually catches an edit ----
        System.out.println("--- now we tamper with the stored record ---");
        Path leaves = logDir.resolve(PersistentLog.LEAVES_FILE);
        String line = Files.readString(leaves, StandardCharsets.UTF_8).strip();
        EvidenceRecord rec = EvidenceRecord.parse(
                new String(java.util.Base64.getDecoder().decode(line), StandardCharsets.UTF_8));
        rec.subjectHash = "0000000000000000000000000000000000000000000000000000000000000000";
        Files.writeString(leaves, java.util.Base64.getEncoder().encodeToString(
                rec.toSignedJson().getBytes(StandardCharsets.UTF_8)) + "\n", StandardCharsets.UTF_8);
        VerifyResult bad = verifier.verify(store.logPublicKey());
        System.out.println("  verdict                  : " + bad.headline());
        System.out.println("  explanation              : " + bad.explanation());
        System.out.println();

        if (temporary) {
            deleteTree(source.getParent());
        }
    }

    /** Removes the temporary demo directory, ignoring failures. */
    private static void deleteTree(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { /* best effort */ }
            });
        } catch (Exception ignored) { /* best effort */ }
    }
}
