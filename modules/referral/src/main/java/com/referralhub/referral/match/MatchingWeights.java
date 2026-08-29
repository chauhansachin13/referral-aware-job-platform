package com.referralhub.referral.match;

/**
 * How much each signal counts when pairing a request with a referrer.
 *
 * @param org            same team or department as the role
 * @param stack          overlap between the role's technologies and the referrer's
 * @param seniority      referrer at or above the role's level, so their referral carries weight
 * @param responsiveness the referrer's reputation score
 * @param fairnessPenalty how hard to push work away from someone already loaded up
 */
public record MatchingWeights(
        double org,
        double stack,
        double seniority,
        double responsiveness,
        double fairnessPenalty) {

    public static MatchingWeights defaults() {
        return new MatchingWeights(0.30, 0.25, 0.15, 0.30, 0.45);
    }

    public double affinitySum() {
        return org + stack + seniority + responsiveness;
    }
}
