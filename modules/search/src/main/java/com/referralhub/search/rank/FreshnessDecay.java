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

    /**
     * How much of a document's score recency is allowed to take away, at most.
     *
     * <p>This bound is the whole point, and it exists because of how RRF scores are shaped.
     * Reciprocal rank fusion deliberately produces a nearly flat distribution: at {@code k = 60}
     * the gap between rank 1 and rank 2 is 1.6%. Multiplying that by a raw exponential decay,
     * which spans the entire range from 1.0 down to 0, lets recency override relevance without
     * limit — a six-month-old perfect match at rank 1 scores {@code 1/61 x 0.0026}, while a fresh
     * and largely irrelevant one at rank 50 scores {@code 1/111 x 0.98} and wins by two hundred
     * times.
     *
     * <p>With the bound, the worst a stale document can lose is this fraction of its retrieval
     * score, which at {@code k = 60} corresponds to a bounded number of positions. Relevance
     * still decides wide rank gaps; recency decides near-ties, which is what it is for.
     */
    public static final double DEFAULT_MAX_PENALTY = 0.4;

    private FreshnessDecay() {
    }

    /**
     * @return a multiplier in [0, 1]; exactly 1.0 for a posting with no age or a future date
     *
     * <p>The lower bound is 0 rather than an open interval: {@code 0.5^n} underflows to exactly
     * zero past roughly 1,075 half-lives, so a posting years older than the half-life scores 0
     * and is ordered only by the id tiebreak. That is the right outcome — such a posting is
     * certainly filled — but it is worth stating, because a score of exactly zero means recency
     * has fully overridden relevance rather than merely discounted it.
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

    /**
     * The bounded multiplier actually applied to a fused score.
     *
     * @param maxPenalty the largest fraction of the score recency may remove, in [0, 1]
     * @return a multiplier in {@code [1 - maxPenalty, 1]}
     */
    public static double boundedMultiplier(Instant postedAt, Instant now, Duration halfLife,
                                           double maxPenalty) {
        double penalty = Math.min(Math.max(maxPenalty, 0.0), 1.0);
        return 1.0 - penalty * (1.0 - multiplier(postedAt, now, halfLife));
    }

    public static double apply(double fusedScore, Instant postedAt, Instant now, Duration halfLife) {
        return apply(fusedScore, postedAt, now, halfLife, DEFAULT_MAX_PENALTY);
    }

    public static double apply(double fusedScore, Instant postedAt, Instant now, Duration halfLife,
                               double maxPenalty) {
        return fusedScore * boundedMultiplier(postedAt, now, halfLife, maxPenalty);
    }
}
