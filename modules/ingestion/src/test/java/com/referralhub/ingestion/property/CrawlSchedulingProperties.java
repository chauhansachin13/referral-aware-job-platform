package com.referralhub.ingestion.property;

import static org.assertj.core.api.Assertions.assertThat;

import com.referralhub.ingestion.config.IngestionProperties;
import com.referralhub.ingestion.schedule.AdaptiveInterval;
import java.time.Duration;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * The scheduler's safety properties.
 *
 * <p>A crawl interval that escapes its bounds is either a self-inflicted denial of service
 * against an ATS or a board that is never crawled again. Neither is discoverable from a handful
 * of chosen examples, because the inputs that break the arithmetic are the extreme ones.
 */
class CrawlSchedulingProperties {

    private final IngestionProperties.Crawl config = new IngestionProperties.Crawl();

    @Property(tries = 1000)
    void theIntervalAlwaysStaysWithinItsBounds(
            @ForAll @DoubleRange(min = -1_000, max = 1_000_000) double postingsPerDay,
            @ForAll @IntRange(min = -50, max = 10_000) int consecutiveUnchanged) {

        Duration interval = AdaptiveInterval.next(postingsPerDay, consecutiveUnchanged, config);

        assertThat(interval).isGreaterThanOrEqualTo(config.getMinInterval());
        assertThat(interval).isLessThanOrEqualTo(config.getMaxInterval());
    }

    @Property(tries = 500)
    void backingOffNeverShortensTheInterval(
            @ForAll @DoubleRange(min = 0.01, max = 10_000) double postingsPerDay,
            @ForAll @IntRange(min = 0, max = 20) int unchanged) {

        Duration sooner = AdaptiveInterval.next(postingsPerDay, unchanged, config);
        Duration later = AdaptiveInterval.next(postingsPerDay, unchanged + 1, config);

        assertThat(later).isGreaterThanOrEqualTo(sooner);
    }

    @Property(tries = 500)
    void abusierBoardIsNeverCrawledLessOften(
            @ForAll @DoubleRange(min = 0.01, max = 500) double slower,
            @ForAll @DoubleRange(min = 0.01, max = 500) double faster) {

        if (slower >= faster) {
            return;
        }
        assertThat(AdaptiveInterval.next(faster, 0, config))
                .isLessThanOrEqualTo(AdaptiveInterval.next(slower, 0, config));
    }

    @Property(tries = 1000)
    void theSmoothedRateIsNeverNegativeOrInfinite(
            @ForAll @DoubleRange(min = 0, max = 10_000) double current,
            @ForAll @IntRange(min = 0, max = 5_000) int changed,
            @ForAll @LongRange(min = 0, max = 90 * 86_400_000L) long elapsedMillis,
            @ForAll @DoubleRange(min = 0, max = 1) double smoothing) {

        double updated = AdaptiveInterval.updateRate(current, changed,
                Duration.ofMillis(elapsedMillis), smoothing);

        assertThat(updated).isNotNegative();
        assertThat(Double.isFinite(updated)).isTrue();
    }

    @Property(tries = 300)
    void aBoardThatPostsNothingCanOnlyDecay(
            @ForAll @DoubleRange(min = 0, max = 1_000) double current,
            @ForAll @DoubleRange(min = 0.01, max = 1) double smoothing) {

        double updated = AdaptiveInterval.updateRate(current, 0, Duration.ofDays(1), smoothing);

        assertThat(updated).isLessThanOrEqualTo(current);
    }
}
