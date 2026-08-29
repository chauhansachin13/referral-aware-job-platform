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
    @DisplayName("a much better old job can still outrank a mediocre new one")
    void relevanceCanStillBeatRecency() {
        // Freshness is a multiplier, not an override: a 3-day-old perfect match should not be
        // buried by a same-day near miss.
        double excellentAndWeekOld = FreshnessDecay.apply(
                0.032, NOW.minus(Duration.ofDays(7)), NOW, HALF_LIFE);
        double mediocreAndFresh = FreshnessDecay.apply(0.016, NOW, NOW, HALF_LIFE);

        assertThat(excellentAndWeekOld).isGreaterThan(mediocreAndFresh);
    }
}
