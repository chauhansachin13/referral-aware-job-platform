package com.referralhub.dedup.canonical;

import java.util.UUID;

/** The minimum a candidate needs to be ranked before it earns an exact comparison. */
public record CandidateRow(
        UUID id,
        UUID companyId,
        String title,
        String descriptionHtml,
        String location,
        boolean remote,
        int[] signature,
        int matchedBands) {
}
