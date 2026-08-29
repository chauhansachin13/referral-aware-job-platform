package com.referralhub.search.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.referralhub.common.json.Json;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueryBuilderTest {

    private static final float[] EMBEDDING = new float[] {0.1f, 0.2f, 0.3f};

    private static SearchRequest request() {
        return new SearchRequest("kubernetes", "Berlin", List.of("senior", "staff"), true,
                "acme", 20, null);
    }

    private String[] lines() {
        return QueryBuilder.multiSearchBody("jobs_v1", request(), EMBEDDING, 50).split("\n");
    }

    @Test
    @DisplayName("the msearch body is four NDJSON lines: header, query, header, query")
    void bodyIsWellFormedNdjson() {
        String[] lines = lines();

        assertThat(lines).hasSize(4);
        assertThat(Json.tree(lines[0]).path("index").asText()).isEqualTo("jobs_v1");
        assertThat(Json.tree(lines[2]).path("index").asText()).isEqualTo("jobs_v1");
        assertThat(Json.tree(lines[1]).path("query").has("bool")).isTrue();
        assertThat(Json.tree(lines[3]).path("query").has("knn")).isTrue();
    }

    @Test
    @DisplayName("both retrieval legs carry the same filters")
    void filtersApplyToBothLegs() {
        String[] lines = lines();

        JsonNode lexicalFilters = Json.tree(lines[1]).path("query").path("bool").path("filter");
        JsonNode vectorFilters = Json.tree(lines[3]).path("query").path("knn").path("embedding")
                .path("filter").path("bool").path("filter");

        // If the kNN leg were unfiltered, a "remote only" search would fuse in on-site jobs the
        // user explicitly excluded.
        assertThat(lexicalFilters).hasSize(4);
        assertThat(vectorFilters).isEqualTo(lexicalFilters);
    }

    @Test
    @DisplayName("title outranks description in the field boosts")
    void fieldBoostsFavourTitle() {
        JsonNode fields = Json.tree(lines()[1]).path("query").path("bool").path("must")
                .path("multi_match").path("fields");

        assertThat(fields.toString()).contains("title^3").contains("specialization^2");
    }

    @Test
    @DisplayName("an empty query is a browse, not an error")
    void emptyQueryBecomesMatchAll() {
        String body = QueryBuilder.multiSearchBody("jobs_v1",
                new SearchRequest("", null, null, null, null, 10, null), EMBEDDING, 10);

        assertThat(Json.tree(body.split("\n")[1]).path("query").path("bool").path("must")
                .has("match_all")).isTrue();
    }

    @Test
    @DisplayName("levels are upper-cased to match the indexed keyword values")
    void levelsAreNormalized() {
        JsonNode filters = Json.tree(lines()[1]).path("query").path("bool").path("filter");

        assertThat(filters.toString()).contains("SENIOR").contains("STAFF");
    }

    @Test
    @DisplayName("an unfiltered search sends no filter clause on the kNN leg")
    void noFiltersMeansNoFilterClause() {
        String body = QueryBuilder.multiSearchBody("jobs_v1",
                new SearchRequest("go", null, null, null, null, 10, null), EMBEDDING, 10);

        assertThat(Json.tree(body.split("\n")[3]).path("query").path("knn").path("embedding")
                .has("filter")).isFalse();
    }

    @Test
    @DisplayName("page size is clamped so one request cannot ask for the whole corpus")
    void sizeIsClamped() {
        assertThat(new SearchRequest("x", null, null, null, null, 5_000, null).size())
                .isEqualTo(SearchRequest.MAX_SIZE);
        assertThat(new SearchRequest("x", null, null, null, null, 0, null).size()).isEqualTo(20);
    }
}
