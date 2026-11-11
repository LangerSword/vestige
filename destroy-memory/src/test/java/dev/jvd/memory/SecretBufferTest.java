package dev.jvd.memory;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SecretBufferTest {

    @Test
    void writeReadRoundtrip() {
        byte[] data = "top-secret-canary".getBytes(StandardCharsets.UTF_8);
        try (SecretBuffer buf = new SecretBuffer(64)) {
            buf.write(data);
            byte[] back = buf.read();
            assertEquals(64, back.length);
            assertArrayEquals(data, Arrays.copyOf(back, data.length));
            assertNotNull(buf.hardeningReport());
            assertTrue(buf.hardeningReport().contains("mlock="), buf.hardeningReport());
        }
    }

    @Test
    void readAfterDestroyThrows() {
        SecretBuffer buf = new SecretBuffer(32);
        buf.write(new byte[]{1, 2, 3});
        buf.destroy();
        assertTrue(buf.isDestroyed());
        assertThrows(IllegalStateException.class, buf::read);
        assertThrows(IllegalStateException.class, () -> buf.write(new byte[]{9}));
        buf.destroy(); // idempotent
    }

    @Test
    void heapWipeZeroesLiveArrays() {
        byte[] b = {5, 6, 7, 8};
        HeapWipe.wipe(b);
        assertArrayEquals(new byte[4], b);
        char[] c = {'a', 'b'};
        HeapWipe.wipe(c);
        assertArrayEquals(new char[2], c);
        HeapWipe.wipe((byte[]) null); // must not throw
    }

    @Test
    void stringMisuseCannotWipe() {
        StringMisuseDemo demo = new StringMisuseDemo("cant-wipe-this");
        assertTrue(demo.holdsReference());
        demo.attemptWipe();
        // The reference is gone but attemptWipe() offers no wipe: the String
        // object itself is untouched (immutability). All this test can assert
        // is the API-level truth; process-memory evidence lives in the
        // verify module's STRING victim test.
        assertFalse(demo.holdsReference());
        assertNull(demo.peekForTest());
    }
}
