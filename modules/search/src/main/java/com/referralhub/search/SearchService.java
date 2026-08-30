package com.referralhub.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.referralhub.search.config.SearchProperties;
import com.referralhub.search.embed.EmbeddingModel;
import com.referralhub.search.index.OpenSearchGateway;
import com.referralhub.search.query.Cursor;
import com.referralhub.search.query.QueryBuilder;
import com.referralhub.search.query.SearchHit;
import com.referralhub.search.query.SearchRequest;
import com.referralhub.search.query.SearchResults;
import com.referralhub.search.rank.FreshnessDecay;
import com.referralhub.search.rank.RankedId;
import com.referralhub.search.rank.ReciprocalRankFusion;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Hybrid retrieval: BM25 and kNN, fused by rank, then decayed by age.
 *
 * <p>The two retrievers fail in opposite directions, which is the reason to run both. BM25
 * cannot match "k8s" against "container orchestration" because they share no token. The vector
 * leg can, but it will happily return a merely thematically related job when the seeker typed an
 * exact company name or an exact technology and meant it literally. Fusing by rank keeps each
 * one's strength without letting either one's failure mode reach the top of the page.
 */
@Service
public class SearchService {

    private final OpenSearchGateway gateway;
    private final EmbeddingModel embeddingModel;
    private final SearchProperties properties;
    private final Timer searchTimer;

    public SearchService(OpenSearchGateway gateway,
                         EmbeddingModel embeddingModel,
                         SearchProperties properties,
                         MeterRegistry meters) {
        this.gateway = gateway;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.searchTimer = Timer.builder("referralhub.search.query")
                .description("End to end hybrid search latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
    }

    public SearchResults search(SearchRequest request) {
        long startedAt = System.nanoTime();
        Timer.Sample sample = Timer.start();

        int queryHash = queryHashOf(request);
        int offset = request.cursor() == null || request.cursor().isBlank()
                ? 0
                : Cursor.decode(request.cursor(), queryHash).offset();

        // Retrieval must reach at least as deep as the page being served, or page three is empty.
        int depth = Math.max(properties.getCandidateDepth(), offset + request.size());

        float[] embedding = embeddingModel.embed(
                request.query().isBlank() ? "software engineer" : request.query());

        JsonNode responses = gateway.multiSearch(
                QueryBuilder.multiSearchBody(gateway.indexName(), request, embedding, depth))
                .path("responses");

        List<RankedId> lexical = rankedIdsOf(responses.path(0));
        List<RankedId> vector = rankedIdsOf(responses.path(1));
        Map<UUID, JsonNode> sources = new HashMap<>();
        collectSources(responses.path(0), sources);
        collectSources(responses.path(1), sources);

        List<ReciprocalRankFusion.Contribution> fused = ReciprocalRankFusion.fuse(
                List.of(lexical, vector),
                List.of(properties.getLexicalWeight(), properties.getVectorWeight()),
                properties.getRrfK());

        Instant now = Instant.now();
        List<SearchHit> ranked = new ArrayList<>(fused.size());
        for (ReciprocalRankFusion.Contribution contribution : fused) {
            JsonNode source = sources.get(contribution.id());
            if (source == null) {
                continue;
            }
            Instant postedAt = parseInstant(source.path("posted_at").asText(null));
            double freshness = FreshnessDecay.boundedMultiplier(postedAt, now,
                    properties.getFreshnessHalfLife(), properties.getFreshnessMaxPenalty());

            ranked.add(new SearchHit(
                    contribution.id(),
                    source.path("title").asText(""),
                    source.path("company_name").asText(""),
                    source.path("location").asText(""),
                    source.path("remote").asBoolean(false),
                    source.path("level").asText(""),
                    source.path("role").asText(""),
                    postedAt,
                    source.path("source_count").asInt(0),
                    contribution.fusedScore() * freshness,
                    contribution.fusedScore(),
                    freshness,
                    contribution.lexicalRank(),
                    contribution.vectorRank()));
        }

        // Decay is applied after fusion, so the final order is not the fused order.
        ranked.sort(java.util.Comparator.comparingDouble(SearchHit::score).reversed()
                .thenComparing(SearchHit::canonicalJobId));

        List<SearchHit> page = ranked.stream().skip(offset).limit(request.size()).toList();
        String nextCursor = offset + request.size() < ranked.size()
                        && offset + request.size() <= Cursor.MAX_OFFSET
                ? new Cursor(offset + request.size(), queryHash).encode()
                : null;

        sample.stop(searchTimer);
        return new SearchResults(page, ranked.size(), nextCursor,
                (System.nanoTime() - startedAt) / 1_000_000);
    }

    private static List<RankedId> rankedIdsOf(JsonNode response) {
        List<RankedId> ids = new ArrayList<>();
        JsonNode hits = response.path("hits").path("hits");
        int rank = 1;
        for (JsonNode hit : hits) {
            String id = hit.path("_source").path("canonical_job_id").asText(null);
            if (id == null) {
                continue;
            }
            ids.add(new RankedId(UUID.fromString(id), rank++, hit.path("_score").asDouble()));
        }
        return ids;
    }

    private static void collectSources(JsonNode response, Map<UUID, JsonNode> into) {
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            String id = source.path("canonical_job_id").asText(null);
            if (id != null) {
                into.putIfAbsent(UUID.fromString(id), source);
            }
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** Binds a cursor to the exact query that produced it. */
    static int queryHashOf(SearchRequest request) {
        return java.util.Objects.hash(request.query(), request.location(), request.levels(),
                request.remote(), request.companySlug(), request.size());
    }
}
