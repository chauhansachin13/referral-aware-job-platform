package com.referralhub.search.query;

import java.time.Instant;
import java.util.UUID;

/**
 * One result, with its ranking arithmetic exposed.
 *
 * <p>The component scores are returned rather than hidden because "why is this job third?" is
 * the first question anyone asks of a ranker, and answering it from logs alone is miserable.
 */
public record SearchHit(
        UUID canonicalJobId,
        String title,
        String companyName,
        String location,
        boolean remote,
        String level,
        String role,
        Instant postedAt,
        int sourceCount,
        double score,
        double fusedScore,
        double freshnessMultiplier,
        Integer lexicalRank,
        Integer vectorRank) {
}
