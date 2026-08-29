package com.referralhub.trust.reputation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReputationScoreTest {

    @Test
    @DisplayName("one lucky success does not outrank a long good record")
    void lowVolumePerfectionDoesNotWin() {
        double oneForOne = ReputationScore.wilsonLowerBound(1, 1);
        double ninetyFiveOfHundred = ReputationScore.wilsonLowerBound(95, 100);

        // A naive rate would score these 1.00 and 0.95 and route everything to the newcomer.
        assertThat(oneForOne).isLessThan(ninetyFiveOfHundred);
    }

    @Test
    @DisplayName("the bound converges upward toward the observed rate as evidence accumulates")
    void convergesWithVolume() {
        double small = ReputationScore.wilsonLowerBound(9, 10);
        double medium = ReputationScore.wilsonLowerBound(90, 100);
        double large = ReputationScore.wilsonLowerBound(900, 1_000);

        assertThat(small).isLessThan(medium);
        assertThat(medium).isLessThan(large);
        assertThat(large).isLessThan(0.9);
        assertThat(large).isGreaterThan(0.87);
    }

    @Test
    @DisplayName("no evidence yields a neutral prior, not a zero that freezes newcomers out")
    void newUsersGetAPrior() {
        ReputationScore.Counters empty = ReputationScore.Counters.empty();

        assertThat(ReputationScore.responseRate(empty)).isEqualTo(ReputationScore.PRIOR);
        assertThat(ReputationScore.completionRate(empty)).isEqualTo(ReputationScore.PRIOR);
        assertThat(ReputationScore.of(empty)).isEqualTo(ReputationScore.PRIOR);
    }

    @Test
    @DisplayName("the bound is always within [0, 1]")
    void boundIsWellBehaved() {
        assertThat(ReputationScore.wilsonLowerBound(0, 0)).isZero();
        assertThat(ReputationScore.wilsonLowerBound(0, 50)).isZero();
        assertThat(ReputationScore.wilsonLowerBound(50, 50)).isBetween(0.9, 1.0);
        // Nonsensical counters are clamped rather than producing a score above 1.
        assertThat(ReputationScore.wilsonLowerBound(80, 50)).isBetween(0.0, 1.0);
        assertThat(ReputationScore.wilsonLowerBound(-5, 50)).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("someone who answers and follows through outscores someone who does neither")
    void combinedScoreOrdersReferrersSensibly() {
        ReputationScore.Counters reliable = new ReputationScore.Counters(100, 98, 60, 55, 2);
        ReputationScore.Counters ghost = new ReputationScore.Counters(100, 12, 8, 2, 88);

        assertThat(ReputationScore.of(reliable)).isGreaterThan(ReputationScore.of(ghost));
        assertThat(ReputationScore.of(reliable)).isBetween(0.0, 1.0);
        assertThat(ReputationScore.of(ghost)).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("completion is weighted above mere responsiveness")
    void completionMattersMoreThanResponding() {
        ReferenceCase respondsNeverDelivers = new ReferenceCase(
                new ReputationScore.Counters(100, 100, 50, 5, 0));
        ReferenceCase respondsLessDeliversMore = new ReferenceCase(
                new ReputationScore.Counters(100, 70, 50, 45, 0));

        assertThat(respondsLessDeliversMore.score()).isGreaterThan(respondsNeverDelivers.score());
    }

    private record ReferenceCase(ReputationScore.Counters counters) {
        double score() {
            return ReputationScore.of(counters);
        }
    }
}
