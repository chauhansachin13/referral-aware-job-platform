package com.referralhub.common.events;

/** Topic names, kept in one place so producers and consumers cannot disagree. */
public final class Topics {

    public static final String JOBS_INGESTED = "jobs.ingested.v1";
    public static final String JOBS_CANONICALIZED = "jobs.canonicalized.v1";
    public static final String REFERRALS_LIFECYCLE = "referrals.lifecycle.v1";
    public static final String TRUST_VERIFICATION = "trust.verification.v1";
    public static final String NOTIFICATIONS = "notifications.v1";

    /** Suffix appended to a topic name to form its dead letter topic. */
    public static final String DLQ_SUFFIX = ".dlq";

    private Topics() {
    }

    public static String dlqFor(String topic) {
        return topic + DLQ_SUFFIX;
    }
}
