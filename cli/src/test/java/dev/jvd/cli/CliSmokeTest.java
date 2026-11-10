package dev.jvd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class CliSmokeTest {

    @TempDir Path tmp;

    @Test
    void version() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{"version"}, new PrintStream(bos)));
        assertTrue(bos.toString().contains("0.1.0"));
    }

    @Test
    void destroyFileEndToEnd() throws Exception {
        Path src = tmp.resolve("victim.txt");
        Files.writeString(src, "end to end shred");
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        Path work = tmp.resolve("work");
        Path log = tmp.resolve("log");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int code = Main.run(new String[]{
                "destroy-file", src.toString(),
                "--kek-hex", HexFormat.of().formatHex(kek),
                "--work", work.toString(),
                "--log", log.toString()}, new PrintStream(bos));
        String out = bos.toString();
        System.out.println(out);
        assertEquals(0, code, out);
        assertFalse(Files.exists(src), "source destroyed");
        assertTrue(Files.exists(work.resolve("artifact.enc")), "shredded artefact kept");
        assertTrue(Files.exists(work.resolve("evidence-record.json")));
        assertTrue(Files.exists(log.resolve("leaves.jsonl")));
        assertTrue(Files.exists(log.resolve("checkpoint.txt")));
        assertTrue(out.contains("state=COMMITTED"), out);
        assertTrue(out.contains("postCommitDecryptFailed=true"), out);
    }
}
