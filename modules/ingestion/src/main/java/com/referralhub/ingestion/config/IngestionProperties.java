package com.referralhub.ingestion.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything about crawling that an operator might need to change without a redeploy. */
@ConfigurationProperties(prefix = "referralhub.ingestion")
public class IngestionProperties {

    /**
     * Sent on every request. A crawler that does not identify itself and offer a contact address
     * is indistinguishable from an attacker, and gets blocked like one.
     */
    private String userAgent =
            "ReferralHubBot/0.1 (+https://github.com/chauhansachin13/referral-aware-job-platform)";

    /** Whether the scheduled crawl loop runs in this process. */
    private boolean crawlEnabled = true;

    private final Crawl crawl = new Crawl();
    private final RateLimit defaultRateLimit = new RateLimit();

    /** Per-host overrides, keyed by hostname. */
    private Map<String, RateLimit> hostRateLimits = new LinkedHashMap<>();

    public RateLimit rateLimitFor(String host) {
        return hostRateLimits.getOrDefault(host, defaultRateLimit);
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public boolean isCrawlEnabled() {
        return crawlEnabled;
    }

    public void setCrawlEnabled(boolean crawlEnabled) {
        this.crawlEnabled = crawlEnabled;
    }

    public Crawl getCrawl() {
        return crawl;
    }

    public RateLimit getDefaultRateLimit() {
        return defaultRateLimit;
    }

    public Map<String, RateLimit> getHostRateLimits() {
        return hostRateLimits;
    }

    public void setHostRateLimits(Map<String, RateLimit> hostRateLimits) {
        this.hostRateLimits = hostRateLimits;
    }

    /** Bounds and gains for the adaptive scheduler. */
    public static class Crawl {

        /** Never crawl one board more often than this, however busy it looks. */
        private Duration minInterval = Duration.ofMinutes(5);

        /** Always crawl at least this often, however dead it looks. */
        private Duration maxInterval = Duration.ofHours(12);

        /** Aim to arrive when roughly this many new postings are waiting. */
        private double targetPostingsPerCrawl = 3.0;

        /** Interval multiplier applied per consecutive unchanged crawl. */
        private double backoffFactor = 1.6;

        /** Cap on how many times the backoff compounds. */
        private int maxBackoffSteps = 6;

        /** EWMA weight for the newest observation of a board's posting rate. */
        private double rateSmoothing = 0.3;

        /** Boards claimed per scheduler tick. */
        private int batchSize = 25;

        /** Threads that run crawls concurrently. */
        private int workers = 8;

        /** How long a crawl may block waiting for a rate-limit token. */
        private Duration rateLimitWait = Duration.ofSeconds(5);

        public Duration getMinInterval() {
            return minInterval;
        }

        public void setMinInterval(Duration minInterval) {
            this.minInterval = minInterval;
        }

        public Duration getMaxInterval() {
            return maxInterval;
        }

        public void setMaxInterval(Duration maxInterval) {
            this.maxInterval = maxInterval;
        }

        public double getTargetPostingsPerCrawl() {
            return targetPostingsPerCrawl;
        }

        public void setTargetPostingsPerCrawl(double targetPostingsPerCrawl) {
            this.targetPostingsPerCrawl = targetPostingsPerCrawl;
        }

        public double getBackoffFactor() {
            return backoffFactor;
        }

        public void setBackoffFactor(double backoffFactor) {
            this.backoffFactor = backoffFactor;
        }

        public int getMaxBackoffSteps() {
            return maxBackoffSteps;
        }

        public void setMaxBackoffSteps(int maxBackoffSteps) {
            this.maxBackoffSteps = maxBackoffSteps;
        }

        public double getRateSmoothing() {
            return rateSmoothing;
        }

        public void setRateSmoothing(double rateSmoothing) {
            this.rateSmoothing = rateSmoothing;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getWorkers() {
            return workers;
        }

        public void setWorkers(int workers) {
            this.workers = workers;
        }

        public Duration getRateLimitWait() {
            return rateLimitWait;
        }

        public void setRateLimitWait(Duration rateLimitWait) {
            this.rateLimitWait = rateLimitWait;
        }
    }

    /** One host's politeness budget. */
    public static class RateLimit {

        private double permitsPerSecond = 2.0;
        private double capacity = 10.0;

        public double getPermitsPerSecond() {
            return permitsPerSecond;
        }

        public void setPermitsPerSecond(double permitsPerSecond) {
            this.permitsPerSecond = permitsPerSecond;
        }

        public double getCapacity() {
            return capacity;
        }

        public void setCapacity(double capacity) {
            this.capacity = capacity;
        }
    }
}
