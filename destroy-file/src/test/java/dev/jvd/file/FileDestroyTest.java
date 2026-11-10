package dev.jvd.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class FileDestroyTest {

    @TempDir Path tmp;

    private static byte[] randomKek() {
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        return kek;
    }

    @Test
    void overwriteDestroyRemovesFile() throws Exception {
        Path f = tmp.resolve("secret.txt");
        Files.writeString(f, "classified canary content");
        long n = OverwriteDestroyer.destroy(f);
        assertTrue(n > 0);
        assertFalse(Files.exists(f));
    }

    @Test
    void cryptoRoundtrip() throws Exception {
        Path in = tmp.resolve("plain.bin");
        byte[] data = "per-file crypto shredding works".getBytes(StandardCharsets.UTF_8);
        Files.write(in, data);
        byte[] kek = randomKek();
        CryptoShredder.ShredResult r = CryptoShredder.encryptFile(in, tmp.resolve("ct.bin"), kek);
        assertArrayEquals(data, CryptoShredder.decrypt(r.ciphertext, r.wrappedDek, kek, r.iv));
    }

    @Test
    void decryptFailsAfterKeyDestroyed() throws Exception {
        Path in = tmp.resolve("plain2.bin");
        Files.writeString(in, "shred me");
        byte[] kek = randomKek();
        CryptoShredder.ShredResult r = CryptoShredder.encryptFile(in, tmp.resolve("ct2.bin"), kek);
        r.destroyKey(); // the shred
        assertThrows(Exception.class, () -> CryptoShredder.decrypt(r.ciphertext, r.wrappedDek, kek, r.iv));
    }

    @Test
    void decryptFailsWithWrongKek() throws Exception {
        Path in = tmp.resolve("plain3.bin");
        Files.writeString(in, "shred me too");
        CryptoShredder.ShredResult r = CryptoShredder.encryptFile(in, tmp.resolve("ct3.bin"), randomKek());
        byte[] wrongKek = randomKek();
        assertThrows(Exception.class, () -> CryptoShredder.decrypt(r.ciphertext, r.wrappedDek, wrongKek, r.iv));
    }
}
