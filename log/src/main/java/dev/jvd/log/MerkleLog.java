package dev.jvd.log;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only Merkle tree over byte-array leaves, hashed per <b>RFC 6962</b>
 * (deviation D2: the report said "RFC 9162 hashing", but C2SP checkpoints hash
 * over the RFC 6962 tree; RFC 9162 is the CT v2 framing, Experimental):
 * <pre>
 *   leafHash(data)     = SHA-256(0x00 || data)
 *   nodeHash(left,right) = SHA-256(0x01 || left || right)
 *   MTH of n leaves    = split at the largest power of two &lt; n (RFC 6962 §2.1)
 * </pre>
 *
 * <p>Inclusion proofs are RFC 6962 audit paths with explicit side information.
 * Consistency proofs list aligned subtree nodes (each tagged with its leaf
 * range) forming a partition of {@code [0, second)} that refines
 * {@code [0, first)}; verification recomputes both roots from the nodes by
 * stacking sibling pairs and folding the remaining peaks right-to-left.
 * Correctness is tested exhaustively against brute-force root computation for
 * all (first, second) pairs with second &le; 32.
 */
public final class MerkleLog {

    /** One inclusion-proof step: the sibling hash and whether the sibling sits to the left. */
    public record ProofNode(byte[] hash, boolean siblingIsLeft) {}

    /** One consistency-proof node: hash of the aligned subtree covering leaves {@code [start, start+len)}. */
    public record ConsistencyNode(int start, int len, byte[] hash) {}

    private final List<byte[]> leaves = new ArrayList<>();

    public int append(byte[] leaf) {
        leaves.add(leaf.clone());
        return leaves.size() - 1;
    }

    public int size() {
        return leaves.size();
    }

    public byte[] leaf(int index) {
        return leaves.get(index).clone();
    }

    public byte[] rootHash() {
        return mth(leafHashes(leaves));
    }

    /** Root hash of the tree over only the first {@code n} leaves (for checkpoint/fork comparison). */
    public byte[] rootHashForPrefix(int n) {
        if (n < 0 || n > leaves.size()) throw new IllegalArgumentException("prefix out of range: " + n);
        return mth(leafHashes(leaves.subList(0, n)));
    }

    // ---------- hashing ----------

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] leafHash(byte[] data) {
        return sha256(new byte[]{0x00}, data);
    }

    static byte[] nodeHash(byte[] left, byte[] right) {
        return sha256(new byte[]{0x01}, left, right);
    }

    private static List<byte[]> leafHashes(List<byte[]> data) {
        List<byte[]> out = new ArrayList<>(data.size());
        for (byte[] d : data) out.add(leafHash(d));
        return out;
    }

    /** RFC 6962 Merkle Tree Hash over leaf hashes. */
    static byte[] mth(List<byte[]> hashes) {
        int n = hashes.size();
        if (n == 0) return sha256(new byte[0]);
        if (n == 1) return hashes.get(0);
        int k = largestPow2Below(n);
        return nodeHash(mth(hashes.subList(0, k)), mth(hashes.subList(k, n)));
    }

    static int largestPow2Below(int n) {
        int k = 1;
        while (k * 2 < n) k *= 2;
        return k;
    }

    // ---------- inclusion proofs ----------

    public List<ProofNode> inclusionProof(int index) {
        if (index < 0 || index >= leaves.size()) throw new IllegalArgumentException("index out of range");
        List<ProofNode> out = new ArrayList<>();
        inclusion(leafHashes(leaves), index, out);
        return out;
    }

    private static void inclusion(List<byte[]> hashes, int index, List<ProofNode> out) {
        int n = hashes.size();
        if (n == 1) return;
        int k = largestPow2Below(n);
        if (index < k) {
            inclusion(hashes.subList(0, k), index, out);
            out.add(new ProofNode(mth(hashes.subList(k, n)), false));
        } else {
            inclusion(hashes.subList(k, n), index - k, out);
            out.add(new ProofNode(mth(hashes.subList(0, k)), true));
        }
    }

    public static boolean verifyInclusion(byte[] leaf, int index, int size, List<ProofNode> proof, byte[] root) {
        if (size <= 0 || index < 0 || index >= size) return false;
        byte[] acc = leafHash(leaf);
        for (ProofNode step : proof) {
            acc = step.siblingIsLeft() ? nodeHash(step.hash(), acc) : nodeHash(acc, step.hash());
        }
        return MessageDigest.isEqual(acc, root);
    }

    // ---------- consistency proofs ----------

    /**
     * Nodes partitioning {@code [0, second)} into aligned blocks that never
     * cross {@code first}: the frontier decomposition of {@code [0, first)}
     * followed by that of {@code [first, second)}. Given those blocks, both
     * roots are recomputable (see class javadoc).
     */
    public List<ConsistencyNode> consistencyProof(int first, int second) {
        if (first < 0 || second < first || second > leaves.size())
            throw new IllegalArgumentException("bad consistency range " + first + ".." + second);
        List<byte[]> hashes = leafHashes(leaves);
        List<ConsistencyNode> out = new ArrayList<>();
        frontier(hashes, 0, first, out);
        frontier(hashes, first, second, out);
        return out;
    }

    /** Greedy aligned decomposition of [from, to): largest aligned power-of-two blocks. */
    private static void frontier(List<byte[]> hashes, int from, int to, List<ConsistencyNode> out) {
        int p = from;
        while (p < to) {
            int maxByAlignment = (p == 0) ? Integer.highestOneBit(to - p) : Integer.lowestOneBit(p);
            int remaining = to - p;
            int size = Math.min(maxByAlignment, Integer.highestOneBit(remaining));
            out.add(new ConsistencyNode(p, size, mth(hashes.subList(p, p + size))));
            p += size;
        }
    }

    public static boolean verifyConsistency(byte[] firstRoot, int first, byte[] secondRoot, int second,
                                              List<ConsistencyNode> proof) {
        if (first < 0 || second < first) return false;
        if (first == second) return MessageDigest.isEqual(firstRoot, secondRoot);
        if (first == 0) return true; // empty tree is a prefix of every tree
        // Nodes must partition [0, second) exactly, in order, aligned, and
        // refine [0, first): no block may cross the first/second boundary.
        List<ConsistencyNode> nodes = new ArrayList<>(proof);
        nodes.sort((x, y) -> Integer.compare(x.start(), y.start()));
        int p = 0;
        List<ConsistencyNode> firstNodes = new ArrayList<>();
        for (ConsistencyNode nd : nodes) {
            if (nd.start() != p) return false;                       // gap or overlap
            if (nd.len() <= 0 || (nd.len() & (nd.len() - 1)) != 0) return false; // power of two
            if (nd.start() % nd.len() != 0) return false;            // aligned
            if (nd.start() < first && nd.start() + nd.len() > first) return false; // crosses boundary
            if (nd.start() + nd.len() <= first) firstNodes.add(nd);
            p += nd.len();
        }
        if (p != second) return false;
        byte[] computedSecond = foldRoot(nodes);
        if (!MessageDigest.isEqual(computedSecond, secondRoot)) return false;
        byte[] computedFirst = foldRoot(firstNodes);
        return MessageDigest.isEqual(computedFirst, firstRoot);
    }

    /**
     * Computes a Merkle root from an aligned partition: repeatedly merge
     * adjacent same-size blocks that are true siblings, then fold the
     * remaining peaks right-to-left (RFC 6962 split order).
     */
    private static byte[] foldRoot(List<ConsistencyNode> nodes) {
        List<ConsistencyNode> stack = new ArrayList<>();
        for (ConsistencyNode nd : nodes) {
            stack.add(nd);
            while (stack.size() >= 2) {
                ConsistencyNode right = stack.get(stack.size() - 1);
                ConsistencyNode left = stack.get(stack.size() - 2);
                if (left.len() == right.len() && left.start() % (2 * left.len()) == 0
                        && left.start() + left.len() == right.start()) {
                    stack.remove(stack.size() - 1);
                    stack.remove(stack.size() - 1);
                    stack.add(new ConsistencyNode(left.start(), left.len() * 2, nodeHash(left.hash(), right.hash())));
                } else break;
            }
        }
        byte[] acc = stack.get(stack.size() - 1).hash();
        for (int i = stack.size() - 2; i >= 0; i--) {
            acc = nodeHash(stack.get(i).hash(), acc);
        }
        return acc;
    }
}
