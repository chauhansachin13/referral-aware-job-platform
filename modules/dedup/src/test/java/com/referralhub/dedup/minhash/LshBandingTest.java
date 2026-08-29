package com.referralhub.dedup.minhash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LshBandingTest {

    @Test
    @DisplayName("the configured banding puts its 50% crossover near 0.71")
    void thresholdMatchesTheConfiguration() {
        LshBanding banding = LshBanding.of(128, 16);

        assertThat(banding.rowsPerBand()).isEqualTo(8);
        assertThat(banding.similarityThreshold())
                .isCloseTo(0.707, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("the retrieval curve is the S-curve that makes LSH worth using")
    void retrievalCurveIsSteep() {
        LshBanding banding = LshBanding.of(128, 16);

        assertThat(banding.retrievalProbability(0.4)).isLessThan(0.02);
        assertThat(banding.retrievalProbability(0.9)).isGreaterThan(0.99);
        // 1 - (1 - 0.7^8)^16 = 0.613: just past the crossover, as the curve requires.
        assertThat(banding.retrievalProbability(0.7)).isBetween(0.55, 0.70);
    }

    @Test
    @DisplayName("more bands trades work for recall, in that direction")
    void moreBandsLowersTheThreshold() {
        assertThat(LshBanding.of(128, 32).similarityThreshold())
                .isLessThan(LshBanding.of(128, 8).similarityThreshold());
    }

    @Test
    @DisplayName("identical signatures agree on every band; unrelated ones on none")
    void bandHashesSeparateDocuments() {
        LshBanding banding = LshBanding.of(128, 16);
        MinHasher hasher = new MinHasher(128, 42L);

        long[] a = banding.bandHashes(hasher.signature(shingles("payments ledger", 200)));
        long[] b = banding.bandHashes(hasher.signature(shingles("payments ledger", 200)));
        long[] c = banding.bandHashes(hasher.signature(shingles("react accessibility", 200)));

        assertThat(a).isEqualTo(b);

        Set<Long> bandsOfA = new HashSet<>();
        for (long hash : a) {
            bandsOfA.add(hash);
        }
        long shared = java.util.Arrays.stream(c).filter(bandsOfA::contains).count();
        assertThat(shared).isZero();
    }

    @Test
    @DisplayName("a band hash mixes in its index, so identical rows in different bands differ")
    void bandIndexIsMixedIn() {
        long[] hashes = LshBanding.of(8, 2).bandHashes(new int[] {7, 7, 7, 7, 7, 7, 7, 7});

        assertThat(hashes[0]).isNotEqualTo(hashes[1]);
    }

    @Test
    @DisplayName("misconfiguration fails at construction, not at query time")
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> LshBanding.of(128, 17))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("divide evenly");
        assertThatThrownBy(() -> new LshBanding(0, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LshBanding.of(128, 16).bandHashes(new int[10]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match banding");
    }

    private static Set<String> shingles(String prefix, int count) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i < count; i++) {
            set.add(prefix + " " + i);
        }
        return set;
    }
}
