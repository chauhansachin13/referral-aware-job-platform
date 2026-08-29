package com.referralhub.search.api;

import com.referralhub.search.SearchService;
import com.referralhub.search.index.JobIndexer;
import com.referralhub.search.query.SearchRequest;
import com.referralhub.search.query.SearchResults;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code @Validated} is load-bearing: without it Spring never evaluates the {@code @Min} and
 * {@code @Max} on the method parameters below, and an oversized page is silently clamped by
 * {@link SearchRequest} instead of being refused. A validation annotation that does nothing is
 * worse than no annotation, because it reads like protection.
 *
 * <p>The clamp in {@code SearchRequest} stays as defence in depth for callers that do not arrive
 * over HTTP.
 */
@RestController
@Validated
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService searchService;
    private final JobIndexer indexer;

    public SearchController(SearchService searchService, JobIndexer indexer) {
        this.searchService = searchService;
        this.indexer = indexer;
    }

    @GetMapping
    public SearchResults search(
            @RequestParam(name = "q", required = false, defaultValue = "") String query,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) List<String> level,
            @RequestParam(required = false) Boolean remote,
            @RequestParam(required = false) String company,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String cursor) {

        return searchService.search(
                new SearchRequest(query, location, level, remote, company, size, cursor));
    }

    /** Forces a reindex of one job; used after a mapping or embedding-model change. */
    @PostMapping("/index/{canonicalJobId}")
    public void reindex(@PathVariable UUID canonicalJobId) {
        indexer.index(canonicalJobId);
    }
}
