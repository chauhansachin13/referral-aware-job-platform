package com.referralhub.dedup.minhash;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.Set;

/**
 * MinHash signatures over shingle sets.
 *
 * <p>Exact pairwise Jaccard is not an option at corpus scale: comparing one new posting against
 * 200,000 canonical jobs means 200,000 set intersections, and doing it for every ingested
 * posting is quadratic in the size of the corpus. A MinHash signature compresses a shingle set
 * of any size to a fixed {@code numHashes} integers whose agreement rate is an unbiased estimator
 * of Jaccard similarity, and — crucially — can be banded for candidate lookup.
 *
 * <p>The permutations are the standard universal family {@code h(x) = (a*x + b) mod p} with p a
 * Mersenne prime. Seeded deterministically: a signature computed today must still match one
 * computed last month, so the coefficients cannot be random per process.
 */
public final class MinHasher {

    /** 2^31 - 1. Every hash value fits in a positive int. */
    private static final long PRIME = 2_147_483_647L;

    private final int numHashes;
    private final long[] coefficientA;
    private final long[] coefficientB;

    public MinHasher(int numHashes, long seed) {
        if (numHashes < 1) {
            throw new IllegalArgumentException("numHashes must be positive");
        }
        this.numHashes = numHashes;
        this.coefficientA = new long[numHashes];
        this.coefficientB = new long[numHashes];

        Random random = new Random(seed);
        for (int i = 0; i < numHashes; i++) {
            // 'a' must not be 0 mod p, or the permutation collapses to a constant.
            coefficientA[i] = 1 + Math.floorMod(random.nextLong(), PRIME - 1);
            coefficientB[i] = Math.floorMod(random.nextLong(), PRIME);
        }
    }

    public int numHashes() {
        return numHashes;
    }

    /**
     * @return a signature of length {@code numHashes}; an empty shingle set yields all
     *         {@link Integer#MAX_VALUE}, which shares no band with any real document.
     */
    public int[] signature(Set<String> shingles) {
        int[] signature = new int[numHashes];
        java.util.Arrays.fill(signature, Integer.MAX_VALUE);
        if (shingles.isEmpty()) {
            return signature;
        }

        for (String shingle : shingles) {
            long base = Math.floorMod(fnv1a64(shingle), PRIME);
            for (int i = 0; i < numHashes; i++) {
                int candidate = (int) ((coefficientA[i] * base + coefficientB[i]) % PRIME);
                if (candidate < signature[i]) {
                    signature[i] = candidate;
                }
            }
        }
        return signature;
    }

    /** Fraction of positions where two signatures agree — the MinHash estimate of Jaccard. */
    public static double estimateJaccard(int[] left, int[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("signatures must be the same length");
        }
        int agreements = 0;
        for (int i = 0; i < left.length; i++) {
            if (left[i] == right[i]) {
                agreements++;
            }
        }
        return (double) agreements / left.length;
    }

    /** FNV-1a. Fast, dependency-free, and well distributed enough to seed the permutations. */
    static long fnv1a64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFF);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
