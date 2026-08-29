package com.referralhub.dedup.match;

/**
 * A duplicate decision with its reasons attached.
 *
 * <p>The components are kept rather than collapsed into one number so that a wrong merge can be
 * explained after the fact: "these scored 0.86 on a 0.93 Jaccard and a 0.31 title match" is
 * actionable, "these scored 0.86" is not.
 */
public record MatchScore(
        double total,
        double jaccard,
        double titleScore,
        double companyScore,
        double locationScore) {

    public boolean isDuplicate(double threshold) {
        return total >= threshold;
    }

    public static MatchScore zero() {
        return new MatchScore(0, 0, 0, 0, 0);
    }
}
