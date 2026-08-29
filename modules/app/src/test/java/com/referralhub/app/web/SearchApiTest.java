package com.referralhub.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.referralhub.search.SearchService;
import com.referralhub.search.api.SearchController;
import com.referralhub.search.index.JobIndexer;
import com.referralhub.search.query.SearchHit;
import com.referralhub.search.query.SearchRequest;
import com.referralhub.search.query.SearchResults;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The search HTTP contract.
 *
 * <p>Web-slice tests rather than full-context ones: what is being checked here is parameter
 * binding, clamping and the JSON shape clients depend on. Whether OpenSearch returns the right
 * documents is settled by {@code SearchServiceIT} against a real cluster.
 */
@WebMvcTest(controllers = SearchController.class)
class SearchApiTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SearchService searchService;
    @MockitoBean
    private JobIndexer indexer;

    private static SearchResults oneHit() {
        return new SearchResults(List.of(new SearchHit(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Staff Engineer, Payments", "Acme", "Berlin", true, "STAFF", "software engineer",
                Instant.parse("2026-08-01T00:00:00Z"), 2, 0.031, 0.033, 0.94, 1, 3)),
                1, "Y3Vyc29y", 7);
    }

    @Test
    @DisplayName("a search returns hits with their ranking arithmetic exposed")
    void returnsHitsWithScores() throws Exception {
        when(searchService.search(any())).thenReturn(oneHit());

        mvc.perform(get("/api/v1/search").param("q", "payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].title").value("Staff Engineer, Payments"))
                .andExpect(jsonPath("$.hits[0].lexicalRank").value(1))
                .andExpect(jsonPath("$.hits[0].vectorRank").value(3))
                .andExpect(jsonPath("$.hits[0].freshnessMultiplier").value(0.94))
                .andExpect(jsonPath("$.nextCursor").value("Y3Vyc29y"))
                .andExpect(jsonPath("$.tookMillis").value(7));
    }

    @Test
    @DisplayName("filters reach the service in the shape the domain expects")
    void bindsFilters() throws Exception {
        when(searchService.search(any())).thenReturn(oneHit());

        mvc.perform(get("/api/v1/search")
                        .param("q", "k8s")
                        .param("location", "Berlin")
                        .param("level", "SENIOR", "STAFF")
                        .param("remote", "true")
                        .param("company", "acme")
                        .param("size", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<SearchRequest> captured = ArgumentCaptor.forClass(SearchRequest.class);
        verify(searchService).search(captured.capture());
        SearchRequest request = captured.getValue();

        org.assertj.core.api.Assertions.assertThat(request.query()).isEqualTo("k8s");
        org.assertj.core.api.Assertions.assertThat(request.levels()).containsExactly("SENIOR", "STAFF");
        org.assertj.core.api.Assertions.assertThat(request.remote()).isTrue();
        org.assertj.core.api.Assertions.assertThat(request.companySlug()).isEqualTo("acme");
        org.assertj.core.api.Assertions.assertThat(request.size()).isEqualTo(5);
    }

    @Test
    @DisplayName("an absent query is a browse, not a 400")
    void emptyQueryIsAllowed() throws Exception {
        when(searchService.search(any())).thenReturn(oneHit());

        mvc.perform(get("/api/v1/search")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a page size beyond the ceiling is rejected rather than silently served")
    void oversizedPageIsRejected() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "x").param("size", "5000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a malformed cursor is a client error carrying the standard error shape")
    void malformedCursorIsABadRequest() throws Exception {
        when(searchService.search(any()))
                .thenThrow(new IllegalArgumentException("Malformed cursor"));

        mvc.perform(get("/api/v1/search").param("q", "x").param("cursor", "!!!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"))
                .andExpect(jsonPath("$.message").value("Malformed cursor"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("an unexpected failure never leaks internals to the caller")
    void internalErrorsAreOpaque() throws Exception {
        when(searchService.search(any()))
                .thenThrow(new IllegalStateException("jdbc:postgresql://prod-db:5432 refused"));

        mvc.perform(get("/api/v1/search").param("q", "x"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal_error"))
                .andExpect(jsonPath("$.message").value("Something went wrong"));
    }
}
