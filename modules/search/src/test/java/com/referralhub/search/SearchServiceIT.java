package com.referralhub.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.referralhub.common.ids.Ids;
import com.referralhub.common.testing.PlatformContainers;
import com.referralhub.common.testing.RequiresDocker;
import com.referralhub.search.config.SearchProperties;
import com.referralhub.search.embed.ConceptHashingEmbeddingModel;
import com.referralhub.search.index.JobDocument;
import com.referralhub.search.index.OpenSearchGateway;
import com.referralhub.search.query.SearchHit;
import com.referralhub.search.query.SearchRequest;
import com.referralhub.search.query.SearchResults;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Hybrid retrieval end to end against a real OpenSearch.
 *
 * <p>The unit tests prove the vector space has the right shape and that fusion and decay do the
 * right arithmetic. This proves those properties survive the round trip: the mapping accepts the
 * vectors, HNSW returns the neighbours the cosine geometry implies, and the filters reach both
 * legs of the query.
 */
@Tag("integration")
@RequiresDocker
class SearchServiceIT {

    private static final UUID COMPANY = Ids.next();
    private static final UUID K8S_JOB = Ids.next();
    private static final UUID FRESH_JOB = Ids.next();
    private static final UUID STALE_JOB = Ids.next();
    private static final UUID ONSITE_JOB = Ids.next();

    private static OpenSearchGateway gateway;
    private static SearchService searchService;
    private static final ConceptHashingEmbeddingModel MODEL = new ConceptHashingEmbeddingModel();

    @BeforeAll
    static void indexCorpus() {
        SearchProperties properties = new SearchProperties();
        properties.setOpensearchUri(PlatformContainers.openSearchUri());
        properties.setIndexName("jobs_it_" + System.currentTimeMillis());
        properties.setFreshnessHalfLife(Duration.ofDays(14));

        gateway = new OpenSearchGateway(properties);
        gateway.ensureIndex(MODEL.dimensions());
        searchService = new SearchService(gateway, MODEL, properties, new SimpleMeterRegistry());

        Instant now = Instant.now();
        index(K8S_JOB, "Site Reliability Engineer",
                "You will own container orchestration and infrastructure as code for every team.",
                "Berlin, Germany", false, "SENIOR", now.minus(Duration.ofDays(3)));
        index(ONSITE_JOB, "Recruiting Coordinator",
                "You will own interview scheduling and candidate experience with every team.",
                "Berlin, Germany", false, "UNSPECIFIED", now.minus(Duration.ofDays(3)));
        // Two jobs with identical text so relevance is equal and only age can separate them.
        index(FRESH_JOB, "Backend Engineer, Payments",
                "You will own the double entry ledger and money movement APIs.",
                "Remote", true, "MID", now.minus(Duration.ofDays(1)));
        index(STALE_JOB, "Backend Engineer, Payments",
                "You will own the double entry ledger and money movement APIs.",
                "Remote", true, "MID", now.minus(Duration.ofDays(120)));

        gateway.refresh();
    }

    private static void index(UUID id, String title, String description, String location,
                              boolean remote, String level, Instant postedAt) {
        String embeddable = title + " " + description + " " + location;
        JobDocument document = new JobDocument(id, COMPANY, "acme", "Acme", title, description,
                "", MODEL.conceptsOf(embeddable), "software engineer", level, location, remote,
                postedAt, 1, MODEL.modelId(), MODEL.embed(embeddable));
        gateway.indexDocument(id.toString(), document.toJson());
    }

    @Test
    @DisplayName("a query with zero token overlap still retrieves the right job")
    void zeroOverlapQueryRetrievesTheRightJob() {
        // "k8s" appears nowhere in the corpus; the job says "container orchestration".
        SearchResults results = searchService.search(
                new SearchRequest("k8s", null, null, null, null, 10, null));

        assertThat(results.hits()).isNotEmpty();
        assertThat(results.hits().get(0).canonicalJobId()).isEqualTo(K8S_JOB);
        assertThat(results.hits().get(0).vectorRank())
                .as("the vector leg is what found it; BM25 cannot match k8s here")
                .isNotNull();
    }

    @Test
    @DisplayName("given two equally relevant jobs, the newer one ranks higher")
    void newerJobOutranksOlderAtEqualRelevance() {
        SearchResults results = searchService.search(
                new SearchRequest("double entry ledger money movement", null, null, null, null,
                        10, null));

        List<UUID> ids = results.hits().stream().map(SearchHit::canonicalJobId).toList();
        assertThat(ids).contains(FRESH_JOB, STALE_JOB);
        assertThat(ids.indexOf(FRESH_JOB)).isLessThan(ids.indexOf(STALE_JOB));

        SearchHit fresh = hit(results, FRESH_JOB);
        SearchHit stale = hit(results, STALE_JOB);
        assertThat(fresh.fusedScore())
                .as("retrieval relevance is equal; only the decay separates them")
                .isCloseTo(stale.fusedScore(), org.assertj.core.data.Offset.offset(0.005));
        assertThat(fresh.freshnessMultiplier()).isGreaterThan(stale.freshnessMultiplier());
    }

    @Test
    @DisplayName("an exact lexical query is served by the lexical leg")
    void exactTermsUseTheLexicalLeg() {
        SearchResults results = searchService.search(
                new SearchRequest("Recruiting Coordinator", null, null, null, null, 10, null));

        assertThat(results.hits().get(0).canonicalJobId()).isEqualTo(ONSITE_JOB);
        assertThat(results.hits().get(0).lexicalRank()).isNotNull();
    }

    @Test
    @DisplayName("the remote filter reaches the kNN leg, not just BM25")
    void filtersApplyToBothLegs() {
        SearchResults results = searchService.search(
                new SearchRequest("engineer", null, null, true, null, 20, null));

        assertThat(results.hits()).isNotEmpty();
        assertThat(results.hits()).allMatch(SearchHit::remote);
        assertThat(results.hits().stream().map(SearchHit::canonicalJobId))
                .doesNotContain(K8S_JOB, ONSITE_JOB);
    }

    @Test
    @DisplayName("cursor pagination walks the result set without repeating a hit")
    void cursorPaginationIsStable() {
        SearchRequest first = new SearchRequest("engineer", null, null, null, null, 2, null);
        SearchResults page1 = searchService.search(first);

        assertThat(page1.hits()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();

        SearchResults page2 = searchService.search(new SearchRequest("engineer", null, null, null,
                null, 2, page1.nextCursor()));

        assertThat(page2.hits().stream().map(SearchHit::canonicalJobId))
                .doesNotContainAnyElementsOf(
                        page1.hits().stream().map(SearchHit::canonicalJobId).toList());
    }

    @Test
    @DisplayName("a level filter narrows to that rung")
    void levelFilterNarrows() {
        SearchResults results = searchService.search(
                new SearchRequest("engineer", null, List.of("senior"), null, null, 10, null));

        assertThat(results.hits()).isNotEmpty();
        assertThat(results.hits()).allMatch(h -> "SENIOR".equals(h.level()));
    }

    private static SearchHit hit(SearchResults results, UUID id) {
        return results.hits().stream().filter(h -> h.canonicalJobId().equals(id))
                .findFirst().orElseThrow();
    }
}
