package com.referralhub.dedup.minhash;

/**
 * Splits a MinHash signature into bands for candidate lookup.
 *
 * <p>Two documents become candidates when they agree on <em>every</em> row of at least one band.
 * With {@code b} bands of {@code r} rows, a pair with true Jaccard {@code s} is retrieved with
 * probability {@code 1 - (1 - s^r)^b} — an S-curve whose inflection sits near
 * {@code (1/b)^(1/r)}.
 *
 * <p>That knob is the entire precision/recall trade-off of this module, which is why bands and
 * rows are configuration rather than constants: more bands finds more real duplicates and more
 * junk to score, fewer bands is cheaper and misses reposts that were lightly edited.
 */
public record LshBanding(int bands, int rowsPerBand) {

    public LshBanding {
        if (bands < 1 || rowsPerBand < 1) {
            throw new IllegalArgumentException("bands and rowsPerBand must be positive");
        }
    }

    public static LshBanding of(int numHashes, int bands) {
        if (numHashes % bands != 0) {
            throw new IllegalArgumentException(
                    "numHashes (" + numHashes + ") must divide evenly into " + bands + " bands");
        }
        return new LshBanding(bands, numHashes / bands);
    }

    public int numHashes() {
        return bands * rowsPerBand;
    }

    /** One hash per band; two documents sharing any of these are candidates. */
    public long[] bandHashes(int[] signature) {
        if (signature.length != numHashes()) {
            throw new IllegalArgumentException(
                    "signature length " + signature.length + " does not match banding " + this);
        }
        long[] hashes = new long[bands];
        for (int band = 0; band < bands; band++) {
            long hash = 0xcbf29ce484222325L;
            int offset = band * rowsPerBand;
            for (int row = 0; row < rowsPerBand; row++) {
                hash ^= signature[offset + row];
                hash *= 0x100000001b3L;
            }
            // Mix the band index in so identical rows in different bands do not collide.
            hash ^= band;
            hash *= 0x100000001b3L;
            hashes[band] = hash;
        }
        return hashes;
    }

    /** Approximate Jaccard at which the retrieval S-curve crosses 50%. */
    public double similarityThreshold() {
        return Math.pow(1.0 / bands, 1.0 / rowsPerBand);
    }

    /** Probability that a pair with this true similarity becomes a candidate. */
    public double retrievalProbability(double jaccard) {
        return 1.0 - Math.pow(1.0 - Math.pow(jaccard, rowsPerBand), bands);
    }
}
