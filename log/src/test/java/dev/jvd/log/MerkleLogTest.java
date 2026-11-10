package dev.jvd.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MerkleLogTest {

    private static byte[] leaf(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static MerkleLog logOf(int n, long seed) {
        MerkleLog log = new MerkleLog();
        Random rnd = new Random(seed);
        for (int i = 0; i < n; i++) {
            byte[] b = new byte[24];
            rnd.nextBytes(b);
            log.append(b);
        }
        return log;
    }

    @Test
    void inclusionProofsVerifyForSizes1To20() {
        for (int n = 1; n <= 20; n++) {
            MerkleLog log = logOf(n, 1000 + n);
            byte[] root = log.rootHash();
            for (int i = 0; i < n; i++) {
                assertTrue(MerkleLog.verifyInclusion(log.leaf(i), i, n, log.inclusionProof(i), root),
                        "n=" + n + " i=" + i);
            }
        }
    }

    @Test
    void tamperedLeafFailsInclusionAndRoot() {
        MerkleLog log = logOf(7, 42);
        byte[] root = log.rootHash();
        byte[] tampered = log.leaf(3);
        tampered[0] ^= 0x01;
        assertFalse(MerkleLog.verifyInclusion(tampered, 3, 7, log.inclusionProof(3), root));
        MerkleLog other = logOf(7, 42);
        // rebuild with the tampered leaf: root must differ
        MerkleLog rebuilt = new MerkleLog();
        for (int i = 0; i < 7; i++) rebuilt.append(i == 3 ? tampered : log.leaf(i));
        assertFalse(Arrays.equals(root, rebuilt.rootHash()));
        assertEquals(7, other.size());
    }

    @Test
    void consistencyProofsVerifyForAllPairsUpTo32() {
        for (int n = 1; n <= 32; n++) {
            MerkleLog log = logOf(n, 777);
            for (int m = 0; m <= n; m++) {
                byte[] firstRoot = (m == 0) ? log.rootHashForPrefix(0) : log.rootHashForPrefix(m);
                byte[] secondRoot = log.rootHashForPrefix(n);
                assertTrue(MerkleLog.verifyConsistency(firstRoot, m, secondRoot, n, log.consistencyProof(m, n)),
                        "m=" + m + " n=" + n);
            }
        }
    }

    @Test
    void consistencyProofRejectsForkedPrefix() {
        MerkleLog a = logOf(8, 5);
        MerkleLog b = logOf(8, 5);
        // fork b at leaf 2 (same random stream, then diverge deterministically)
        MerkleLog fork = new MerkleLog();
        for (int i = 0; i < 8; i++) {
            byte[] lf = b.leaf(i);
            if (i == 2) lf[0] ^= 0x40;
            fork.append(lf);
        }
        // a's proof for (4, 8) must not validate against fork's roots
        assertFalse(MerkleLog.verifyConsistency(fork.rootHashForPrefix(4), 4, fork.rootHash(), 8,
                a.consistencyProof(4, 8)));
    }

    @Test
    void checkpointSignCosignAndKeyIndependence(@TempDir Path tmp) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
        KeyPair logKp = g.generateKeyPair();
        KeyPair witKp = g.generateKeyPair();
        MerkleLog log = logOf(5, 9);
        Checkpoint cp = new Checkpoint(log.size(), log.rootHash(), 1_700_000_000_000L).sign(logKp.getPrivate());
        assertTrue(cp.verifyLogSignature(logKp.getPublic()));
        cp.witnessCosign(witKp.getPrivate(), witKp.getPublic(), logKp.getPublic());
        assertTrue(cp.verifyWitnessSignature(witKp.getPublic()));

        // text roundtrip
        Checkpoint back = Checkpoint.parse(cp.toText());
        assertEquals(cp.size, back.size);
        assertArrayEquals(cp.root, back.root);
        assertTrue(back.verifyLogSignature(logKp.getPublic()));
        assertTrue(back.verifyWitnessSignature(witKp.getPublic()));

        // same-key witness is refused
        Checkpoint cp2 = new Checkpoint(log.size(), log.rootHash(), 1_700_000_000_001L).sign(logKp.getPrivate());
        assertThrows(IllegalArgumentException.class,
                () -> cp2.witnessCosign(logKp.getPrivate(), logKp.getPublic(), logKp.getPublic()));

        // tampered size breaks the signature
        Checkpoint cp3 = Checkpoint.parse(cp.toText());
        Checkpoint evil = new Checkpoint(cp3.size + 1, cp3.root, cp3.timestampMillis);
        evil.logSignature = cp3.logSignature;
        assertFalse(evil.verifyLogSignature(logKp.getPublic()));
    }

    @Test
    void persistentLogRoundtrip(@TempDir Path tmp) throws Exception {
        PersistentLog pl = PersistentLog.open(tmp);
        pl.append(leaf("record-1"));
        pl.append(leaf("record-2"));
        pl.append(leaf("record-3"));
        byte[] root = pl.log().rootHash();
        PersistentLog reopened = PersistentLog.open(tmp);
        assertEquals(3, reopened.log().size());
        assertArrayEquals(root, reopened.log().rootHash());
    }
}
