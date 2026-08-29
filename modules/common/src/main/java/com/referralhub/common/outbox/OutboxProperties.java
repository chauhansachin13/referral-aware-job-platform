package com.referralhub.common.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tuning knobs for the outbox relay. All of these are environment-overridable. */
@ConfigurationProperties(prefix = "referralhub.outbox")
public class OutboxProperties {

    /** Whether the relay runs in this process. Disabled in tests that only assert on rows. */
    private boolean relayEnabled = true;

    /** Rows claimed per relay pass. */
    private int batchSize = 200;

    /** Delay between relay passes, in milliseconds. */
    private long pollIntervalMillis = 500;

    /** Give up (and leave the row for an operator) after this many failed publish attempts. */
    private int maxAttempts = 10;

    /** How long to wait for a broker ack before treating the send as failed. */
    private Duration sendTimeout = Duration.ofSeconds(10);

    /** Published rows older than this are deleted by the reaper. */
    private Duration retention = Duration.ofDays(7);

    public boolean isRelayEnabled() {
        return relayEnabled;
    }

    public void setRelayEnabled(boolean relayEnabled) {
        this.relayEnabled = relayEnabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getPollIntervalMillis() {
        return pollIntervalMillis;
    }

    public void setPollIntervalMillis(long pollIntervalMillis) {
        this.pollIntervalMillis = pollIntervalMillis;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }
}
