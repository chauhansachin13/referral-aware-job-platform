package com.referralhub.dedup.minhash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.referralhub.common.text.Shingles;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MinHasherTest {

    private final MinHasher hasher = new MinHasher(128, 0x5EED_1234L);

    @Test
    @DisplayName("the estimate tracks true Jaccard within MinHash's expected error")
    void estimateApproximatesJaccard() {
        // Standard error of a k-hash estimate is about 1/sqrt(k); 128 hashes gives ~0.088.
        double worstError = 0;
        for (int overlap = 0; overlap <= 100; overlap += 10) {
            Set<String> left = range(0, 100);
            Set<String> right = range(100 - overlap, 200 - overlap);
            double exact = Shingles.jaccard(left, right);
            double estimate = MinHasher.estimateJaccard(
                    hasher.signature(left), hasher.signature(right));
            worstError = Math.max(worstError, Math.abs(exact - estimate));
        }
        assertThat(worstError).isLessThan(0.15);
    }

    @Test
    @DisplayName("identical sets produce identical signatures")
    void identicalSetsAgreeEverywhere() {
        Set<String> shingles = range(0, 50);
        assertThat(MinHasher.estimateJaccard(hasher.signature(shingles), hasher.signature(shingles)))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("signatures do not depend on iteration order")
    void orderIndependent() {
        Set<String> forward = new LinkedHashSet<>();
        Set<String> backward = new LinkedHashSet<>();
        for (int i = 0; i < 60; i++) {
            forward.add("token " + i);
        }
        for (int i = 59; i >= 0; i--) {
            backward.add("token " + i);
        }
        assertThat(hasher.signature(forward)).isEqualTo(hasher.signature(backward));
    }

    @Test
    @DisplayName("the same seed gives the same permutations in a different process")
    void seedingIsDeterministic() {
        assertThat(new MinHasher(128, 0x5EED_1234L).signature(range(0, 40)))
                .isEqualTo(hasher.signature(range(0, 40)));
    }

    @Test
    @DisplayName("a different seed gives different permutations")
    void differentSeedsDiffer() {
        assertThat(new MinHasher(128, 99L).signature(range(0, 40)))
                .isNotEqualTo(hasher.signature(range(0, 40)));
    }

    @Test
    @DisplayName("an empty document shares no band with anything")
    void emptySetIsInert() {
        int[] empty = hasher.signature(Set.of());

        assertThat(empty).containsOnly(Integer.MAX_VALUE);
        assertThat(MinHasher.estimateJaccard(empty, hasher.signature(range(0, 10)))).isZero();
    }

    @Test
    @DisplayName("comparing signatures of different lengths is a programming error, not a result")
    void rejectsMismatchedLengths() {
        assertThatThrownBy(() -> MinHasher.estimateJaccard(new int[8], new int[16]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MinHasher(0, 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Set<String> range(int from, int toExclusive) {
        Set<String> set = new HashSet<>();
        for (int i = from; i < toExclusive; i++) {
            set.add("shingle " + i);
        }
        return set;
    }
}
