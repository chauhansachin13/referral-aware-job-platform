package com.referralhub.search.rank;

import java.time.Duration;
import java.time.Instant;

/**
 * Exponential decay on posting age, applied to the fused score.
 *
 * <p>Job search has an unusually strong recency prior: a role posted six months ago is usually
 * filled, and surfacing it wastes the seeker's application budget on something they cannot get.
 * That is a much stronger effect than in general web search, where an old document can be the
 * best answer indefinitely.
 *
 * <p>Parameterized by half-life rather than by a decay constant because half-life is the form a
 * product decision actually arrives in: "a two-week-old posting should be worth half a fresh
 * one" is a sentence someone can argue with.
 */
public final class FreshnessDecay {

    private FreshnessDecay() {
    }

    /**
     * @return a multiplier in (0, 1]; exactly 1.0 for a posting with no age or a future date
     */
    public static double multiplier(Instant postedAt, Instant now, Duration halfLife) {
        if (postedAt == null) {
            // Unknown age is not evidence of staleness; leave the retrieval score alone.
            return 1.0;
        }
        long ageMillis = Duration.between(postedAt, now).toMillis();
        if (ageMillis <= 0) {
            return 1.0;
        }
        double halfLives = (double) ageMillis / halfLife.toMillis();
        return Math.pow(0.5, halfLives);
    }

    public static double apply(double fusedScore, Instant postedAt, Instant now, Duration halfLife) {
        return fusedScore * multiplier(postedAt, now, halfLife);
    }
}
