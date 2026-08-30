package com.referralhub.ingestion.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.referralhub.ingestion.config.IngestionProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scheduling policy is a pure function, so it can be pinned down exactly — no Redis, no
 * clock, no waiting. These are the cases that would otherwise only show up as a bill from an
 * ATS or a complaint about stale listings.
 */
class AdaptiveIntervalTest {

    private final IngestionProperties.Crawl config = new IngestionProperties.Crawl();

    @Test
    @DisplayName("a board posting 30 roles a day is crawled far more often than one posting 1")
    void busyBoardsAreCrawledMoreOften() {
        Duration busy = AdaptiveInterval.next(30.0, 0, config);
        Duration quiet = AdaptiveInterval.next(1.0, 0, config);

        assertThat(busy).isLessThan(quiet);
        // 3 postings per crawl at 30/day => every 2.4 hours... clamped by nothing here.
        assertThat(busy).isEqualTo(Duration.ofMinutes(144));
    }

    @Test
    @DisplayName("a board that never posts settles at the maximum interval, not at zero")
    void deadBoardsFallBackToMaxInterval() {
        assertThat(AdaptiveInterval.next(0.0, 0, config)).isEqualTo(config.getMaxInterval());
        assertThat(AdaptiveInterval.next(-5.0, 0, config)).isEqualTo(config.getMaxInterval());
    }

    @Test
    @DisplayName("an implausibly busy board is still never crawled faster than the floor")
    void respectsMinimumInterval() {
        assertThat(AdaptiveInterval.next(100_000.0, 0, config)).isEqualTo(config.getMinInterval());
    }

    @Test
    @DisplayName("consecutive unchanged crawls compound the interval")
    void backoffCompounds() {
        Duration first = AdaptiveInterval.next(30.0, 0, config);
        Duration second = AdaptiveInterval.next(30.0, 1, config);
        Duration third = AdaptiveInterval.next(30.0, 2, config);

        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
    }

    @Test
    @DisplayName("backoff stops compounding at the configured ceiling and never exceeds max")
    void backoffIsBounded() {
        Duration atCap = AdaptiveInterval.next(30.0, config.getMaxBackoffSteps(), config);
        Duration wayPastCap = AdaptiveInterval.next(30.0, 10_000, config);

        assertThat(wayPastCap).isEqualTo(atCap);
        assertThat(wayPastCap).isLessThanOrEqualTo(config.getMaxInterval());
    }

    @Test
    @DisplayName("a negative unchanged count cannot shrink the interval below the base")
    void negativeUnchangedIsTreatedAsZero() {
        assertThat(AdaptiveInterval.next(30.0, -3, config))
                .isEqualTo(AdaptiveInterval.next(30.0, 0, config));
    }

    @Test
    @DisplayName("one crawl cannot move the cadence by more than a factor of two")
    void adaptationIsDamped() {
        Duration current = Duration.ofHours(1);

        // A board with the default rate prior would otherwise jump straight to the 12h ceiling
        // on its first unchanged crawl, which is a 12x move on a single observation.
        Duration afterQuietCrawl = AdaptiveInterval.next(1.0, 1, config, current);
        assertThat(afterQuietCrawl).isEqualTo(Duration.ofHours(2));

        // And the same bound applies in the other direction.
        Duration afterBusyCrawl = AdaptiveInterval.next(10_000.0, 0, config, current);
        assertThat(afterBusyCrawl).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("repeated quiet crawls still reach the ceiling, just not in one step")
    void dampingDelaysButDoesNotPrevent() {
        Duration interval = Duration.ofHours(1);
        for (int crawl = 1; crawl <= 10; crawl++) {
            interval = AdaptiveInterval.next(1.0, crawl, config, interval);
        }
        assertThat(interval).isEqualTo(config.getMaxInterval());
    }

    @Test
    @DisplayName("damping never pushes the interval outside its configured bounds")
    void dampingRespectsTheClamps() {
        assertThat(AdaptiveInterval.next(1.0, 0, config, Duration.ofHours(11)))
                .isLessThanOrEqualTo(config.getMaxInterval());
        assertThat(AdaptiveInterval.next(100_000.0, 0, config, Duration.ofMinutes(6)))
                .isGreaterThanOrEqualTo(config.getMinInterval());
    }

    @Test
    @DisplayName("the observed rate moves toward new evidence without jumping to it")
    void rateIsSmoothed() {
        double updated = AdaptiveInterval.updateRate(10.0, 20, Duration.ofDays(1), 0.3);

        // 0.3 * 20 + 0.7 * 10 = 13
        assertThat(updated).isEqualTo(13.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(updated).isGreaterThan(10.0).isLessThan(20.0);
    }

    @Test
    @DisplayName("a zero-length interval cannot produce an infinite rate")
    void guardsAgainstZeroElapsed() {
        assertThat(AdaptiveInterval.updateRate(4.0, 9, Duration.ZERO, 0.3)).isEqualTo(4.0);
    }

    @Test
    @DisplayName("a board that goes quiet decays toward zero rather than staying hot forever")
    void rateDecaysWhenNothingPosts() {
        double rate = 30.0;
        for (int i = 0; i < 20; i++) {
            rate = AdaptiveInterval.updateRate(rate, 0, Duration.ofDays(1), 0.3);
        }
        assertThat(rate).isLessThan(0.1);
    }
}
