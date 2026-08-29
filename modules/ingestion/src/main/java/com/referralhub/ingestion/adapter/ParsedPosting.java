package com.referralhub.ingestion.adapter;

import java.time.Instant;

/**
 * One posting as the adapter understood it.
 *
 * <p>{@code rawJson} is the adapter's slice of the original response. Keeping it means a parser
 * bug can be fixed and replayed from the database instead of being re-crawled from a board that
 * may have already taken the posting down.
 */
public record ParsedPosting(
        String externalId,
        String title,
        String descriptionHtml,
        String location,
        boolean remote,
        String department,
        String applyUrl,
        Instant postedAt,
        String rawJson) {
}
