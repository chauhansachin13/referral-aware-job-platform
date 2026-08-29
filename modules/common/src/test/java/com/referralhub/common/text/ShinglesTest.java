package com.referralhub.common.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShinglesTest {

    @Test
    @DisplayName("k-shingles slide one token at a time")
    void producesSlidingWindows() {
        Set<String> shingles = Shingles.of(List.of("a", "b", "c", "d"), 2);
        assertThat(shingles).containsExactly("a b", "b c", "c d");
    }

    @Test
    @DisplayName("texts shorter than k still produce one shingle so titles compare")
    void shortTextsDegradeGracefully() {
        assertThat(Shingles.of(List.of("staff", "engineer"), 3)).containsExactly("staff engineer");
        assertThat(Shingles.of(List.of(), 3)).isEmpty();
    }

    @Test
    @DisplayName("jaccard of identical sets is 1 and of disjoint sets is 0")
    void jaccardBounds() {
        Set<String> a = Set.of("x y", "y z");
        assertThat(Shingles.jaccard(a, a)).isEqualTo(1.0);
        assertThat(Shingles.jaccard(a, Set.of("p q"))).isEqualTo(0.0);
        assertThat(Shingles.jaccard(Set.of(), Set.of())).isEqualTo(1.0);
    }

    @Test
    @DisplayName("jaccard is the exact intersection over union")
    void jaccardValue() {
        double j = Shingles.jaccard(Set.of("a", "b", "c"), Set.of("b", "c", "d"));
        assertThat(j).isEqualTo(2.0 / 4.0);
    }

    @Test
    @DisplayName("a nonsensical shingle size fails loudly")
    void rejectsInvalidK() {
        assertThatThrownBy(() -> Shingles.of(List.of("a"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
