package com.referralhub.search.rank;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FreshnessDecayTest {

    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");
    private static final Duration HALF_LIFE = Duration.ofDays(14);

    @Test
    @DisplayName("of two equally relevant jobs, the newer one ranks higher")
    void newerJobWinsAtEqualRelevance() {
        double fused = 0.031;

        double fresh = FreshnessDecay.apply(fused, NOW.minus(Duration.ofDays(1)), NOW, HALF_LIFE);
        double stale = FreshnessDecay.apply(fused, NOW.minus(Duration.ofDays(60)), NOW, HALF_LIFE);

        assertThat(fresh).isGreaterThan(stale);
    }

    @Test
    @DisplayName("one half-life halves the score, by definition")
    void halfLifeHalvesTheScore() {
        assertThat(FreshnessDecay.multiplier(NOW.minus(HALF_LIFE), NOW, HALF_LIFE))
                .isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(FreshnessDecay.multiplier(NOW.minus(HALF_LIFE.multipliedBy(2)), NOW, HALF_LIFE))
                .isCloseTo(0.25, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("a job posted this instant is undamped")
    void freshJobsAreUndamped() {
        assertThat(FreshnessDecay.multiplier(NOW, NOW, HALF_LIFE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an unknown posting date is neutral, not treated as infinitely old")
    void unknownAgeIsNeutral() {
        assertThat(FreshnessDecay.multiplier(null, NOW, HALF_LIFE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a clock-skewed future timestamp does not amplify a score above its fused value")
    void futureDatesAreClamped() {
        assertThat(FreshnessDecay.multiplier(NOW.plus(Duration.ofDays(5)), NOW, HALF_LIFE))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("decay is monotonic and never reaches zero")
    void decayIsMonotonic() {
        double previous = 1.1;
        for (int days = 0; days <= 365; days += 5) {
            double multiplier = FreshnessDecay.multiplier(
                    NOW.minus(Duration.ofDays(days)), NOW, HALF_LIFE);
            assertThat(multiplier).isLessThanOrEqualTo(previous).isGreaterThan(0.0);
            previous = multiplier;
        }
    }

    @Test
    @DisplayName("the bounded multiplier never removes more than the configured fraction")
    void boundedMultiplierRespectsItsCeiling() {
        for (long days : new long[] {0, 1, 14, 90, 365, 3_650}) {
            double bounded = FreshnessDecay.boundedMultiplier(
                    NOW.minus(Duration.ofDays(days)), NOW, HALF_LIFE, 0.4);

            assertThat(bounded).isBetween(0.6, 1.0);
        }
    }

    @Test
    @DisplayName("a relevant but stale job is not buried by a fresh irrelevant one")
    void recencyCannotOverrideRelevance() {
        // The case that motivated the bound. RRF is deliberately flat: rank 1 scores 1/61 and
        // rank 50 scores 1/111. With a raw decay the six-month-old rank-1 match scores
        // 1/61 x 0.0026 and loses to the fresh rank-50 match by two hundred times.
        double rankOne = 1.0 / 61;
        double rankFifty = 1.0 / 111;

        double staleButRelevant = FreshnessDecay.apply(
                rankOne, NOW.minus(Duration.ofDays(180)), NOW, HALF_LIFE);
        double freshButIrrelevant = FreshnessDecay.apply(rankFifty, NOW, NOW, HALF_LIFE);

        assertThat(staleButRelevant).isGreaterThan(freshButIrrelevant);

        // Without the bound it goes the other way, which is what the bound exists to prevent.
        assertThat(rankOne * FreshnessDecay.multiplier(
                NOW.minus(Duration.ofDays(180)), NOW, HALF_LIFE))
                .isLessThan(rankFifty * FreshnessDecay.multiplier(NOW, NOW, HALF_LIFE));
    }

    @Test
    @DisplayName("recency still decides between two adjacent, equally relevant results")
    void recencyBreaksNearTies() {
        // Adjacent RRF ranks differ by 1.6%; the bounded decay still has room to reorder them.
        double fresh = FreshnessDecay.apply(1.0 / 61, NOW.minus(Duration.ofDays(1)), NOW, HALF_LIFE);
        double stale = FreshnessDecay.apply(1.0 / 62, NOW.minus(Duration.ofDays(120)), NOW, HALF_LIFE);

        assertThat(fresh).isGreaterThan(stale);
    }

    @Test
    @DisplayName("a zero penalty disables the decay entirely")
    void aZeroPenaltyIsANoOp() {
        assertThat(FreshnessDecay.boundedMultiplier(
                NOW.minus(Duration.ofDays(3_650)), NOW, HALF_LIFE, 0.0)).isEqualTo(1.0);
    }
}
