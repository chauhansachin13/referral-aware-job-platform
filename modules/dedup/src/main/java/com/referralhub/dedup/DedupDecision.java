package com.referralhub.dedup;

import java.util.UUID;

/** What the deduplicator decided about one posting, and on what evidence. */
public record DedupDecision(
        UUID canonicalJobId,
        UUID rawPostingId,
        boolean createdNewCanonical,
        double matchScore,
        int candidatesRetrieved,
        int candidatesScored) {
}
