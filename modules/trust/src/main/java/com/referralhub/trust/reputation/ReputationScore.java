package com.referralhub.trust.reputation;

/**
 * Reputation as a pure function of counters.
 *
 * <p>Naive rates are unusable at low volume: a referrer who accepted the one request they ever
 * received would score 1.0 and outrank someone who completed 95 of 100. The matcher would then
 * route everything to the least proven person on the platform.
 *
 * <p>So each rate is a Wilson score interval lower bound — the rate you can be 95% confident the
 * person is at least as good as. It converges to the observed rate with volume and stays near a
 * neutral prior when there is no evidence, which is exactly the behaviour a routing signal needs.
 */
public final class ReputationScore {

    /** 95% one-sided confidence. */
    private static final double Z = 1.96;

    /** What someone with no history is assumed to be worth. */
    public static final double PRIOR = 0.5;

    private static final double RESPONSE_WEIGHT = 0.4;
    private static final double COMPLETION_WEIGHT = 0.6;

    private ReputationScore() {
    }

    public record Counters(
            int requestsReceived,
            int requestsResponded,
            int requestsAccepted,
            int requestsCompleted,
            int requestsExpired) {

        public static Counters empty() {
            return new Counters(0, 0, 0, 0, 0);
        }
    }

    /**
     * Lower bound of the Wilson score interval.
     *
     * @return a value in [0, 1]; {@code 0} when there are no trials
     */
    public static double wilsonLowerBound(int successes, int trials) {
        if (trials <= 0) {
            return 0.0;
        }
        int bounded = Math.min(Math.max(successes, 0), trials);
        double p = (double) bounded / trials;
        double zSquaredOverN = (Z * Z) / trials;
        double denominator = 1 + zSquaredOverN;
        double centre = p + zSquaredOverN / 2;
        double margin = Z * Math.sqrt((p * (1 - p) + (Z * Z) / (4.0 * trials)) / trials);
        return Math.max(0.0, (centre - margin) / denominator);
    }

    /**
     * How reliably this person answers at all. Silence is the failure mode seekers feel most:
     * an ignored request wastes a slot that a responsive referrer would have used.
     */
    public static double responseRate(Counters counters) {
        if (counters.requestsReceived() == 0) {
            return PRIOR;
        }
        return wilsonLowerBound(counters.requestsResponded(), counters.requestsReceived());
    }

    /** Of the requests they accepted, how many they actually submitted. */
    public static double completionRate(Counters counters) {
        if (counters.requestsAccepted() == 0) {
            return PRIOR;
        }
        return wilsonLowerBound(counters.requestsCompleted(), counters.requestsAccepted());
    }

    /** Combined score in [0, 1]. */
    public static double of(Counters counters) {
        return RESPONSE_WEIGHT * responseRate(counters)
                + COMPLETION_WEIGHT * completionRate(counters);
    }
}
