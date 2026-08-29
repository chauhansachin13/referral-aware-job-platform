package com.referralhub.ingestion.schedule;

import com.referralhub.ingestion.config.IngestionProperties;
import java.time.Duration;

/**
 * Decides how long to wait before crawling a board again.
 *
 * <p>A fixed interval is wrong in both directions at once: crawling a 4000-person company that
 * posts 30 roles a day every six hours means arriving 30 postings late, and crawling a
 * ten-person startup that posts twice a year at the same cadence spends 1460 requests to learn
 * nothing. So the interval is derived from the board's own observed posting rate and then backed
 * off while it keeps coming back unchanged.
 *
 * <p>This is a pure function of its inputs on purpose — the scheduling policy is the part most
 * likely to be tuned, and it can be tested exhaustively without Redis, Postgres or a clock.
 */
public final class AdaptiveInterval {

    private AdaptiveInterval() {
    }

    /**
     * @param postingsPerDay smoothed observation of how fast this board changes
     * @param consecutiveUnchanged how many crawls in a row produced no semantic change
     */
    public static Duration next(double postingsPerDay, int consecutiveUnchanged,
                                IngestionProperties.Crawl config) {
        Duration min = config.getMinInterval();
        Duration max = config.getMaxInterval();

        Duration base;
        if (postingsPerDay <= 0.0001) {
            base = max;
        } else {
            double secondsPerCrawl = 86_400.0 * config.getTargetPostingsPerCrawl() / postingsPerDay;
            base = Duration.ofMillis((long) Math.min(secondsPerCrawl * 1000.0, (double) max.toMillis()));
        }
        base = clamp(base, min, max);

        int steps = Math.min(Math.max(consecutiveUnchanged, 0), config.getMaxBackoffSteps());
        double multiplier = Math.pow(config.getBackoffFactor(), steps);
        Duration backedOff = Duration.ofMillis((long) Math.min(
                base.toMillis() * multiplier, (double) max.toMillis()));

        return clamp(backedOff, min, max);
    }

    /**
     * Folds the newest observation into the smoothed posting rate.
     *
     * <p>An exponentially weighted mean rather than a plain average because a board's hiring
     * rate is not stationary: a company that froze hiring last quarter should not be crawled on
     * the strength of what it did in January.
     */
    public static double updateRate(double currentPerDay, int changedPostings,
                                    Duration sinceLastCrawl, double smoothing) {
        double days = sinceLastCrawl.toMillis() / 86_400_000.0;
        if (days <= 0) {
            return currentPerDay;
        }
        double observed = changedPostings / days;
        double alpha = Math.min(Math.max(smoothing, 0.0), 1.0);
        return alpha * observed + (1 - alpha) * currentPerDay;
    }

    private static Duration clamp(Duration value, Duration min, Duration max) {
        if (value.compareTo(min) < 0) {
            return min;
        }
        if (value.compareTo(max) > 0) {
            return max;
        }
        return value;
    }
}
