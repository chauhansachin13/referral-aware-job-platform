package com.referralhub.search.query;

import java.util.List;

public record SearchResults(
        List<SearchHit> hits,
        long totalCandidates,
        String nextCursor,
        long tookMillis) {
}
