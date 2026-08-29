package com.referralhub.search.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.referralhub.common.json.Json;
import com.referralhub.search.config.SearchProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * A thin REST client for OpenSearch, speaking its query DSL as explicit JSON.
 *
 * <p>Chosen over the generated typed client on purpose. The typed client's builder API has
 * changed shape across 2.x and 3.x, it pulls a large transitive tree, and — most importantly —
 * it hides the query behind a fluent facade at exactly the point where the query <em>is</em> the
 * design. Hybrid retrieval, kNN filters and RRF are worth reading as the JSON that actually goes
 * over the wire. See docs/adr/0005-hand-written-opensearch-client.md.
 */
@Component
public class OpenSearchGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchGateway.class);

    private final RestClient client;
    private final SearchProperties properties;

    public OpenSearchGateway(SearchProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(properties.getRequestTimeout());
        this.client = RestClient.builder()
                .baseUrl(properties.getOpensearchUri())
                .requestFactory(factory)
                .build();
    }

    public boolean indexExists() {
        return Boolean.TRUE.equals(client.get()
                .uri("/{index}", properties.getIndexName())
                .exchange((request, response) -> response.getStatusCode().is2xxSuccessful(), false));
    }

    /** Creates the index if it is missing. Safe to call on every startup. */
    public void ensureIndex(int embeddingDimensions) {
        if (indexExists()) {
            return;
        }
        String mapping = JobIndexMapping.create(embeddingDimensions);
        client.put()
                .uri("/{index}", properties.getIndexName())
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapping)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        // A concurrent replica may have won the race; that is not an error.
                        log.info("Index creation returned {} (likely created concurrently)",
                                response.getStatusCode());
                    }
                    return null;
                }, false);
        log.info("Ensured OpenSearch index {}", properties.getIndexName());
    }

    public void indexDocument(String id, String documentJson) {
        client.put()
                .uri("/{index}/_doc/{id}", properties.getIndexName(), id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(documentJson)
                .retrieve()
                .toBodilessEntity();
    }

    /** One newline-delimited bulk request. Payload is already NDJSON, trailing newline included. */
    public JsonNode bulk(String ndjson) {
        String body = client.post()
                .uri("/_bulk")
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(ndjson)
                .retrieve()
                .body(String.class);
        return Json.tree(body == null ? "{}" : body);
    }

    public void deleteDocument(String id) {
        client.delete()
                .uri("/{index}/_doc/{id}", properties.getIndexName(), id)
                .exchange((request, response) -> null, false);
    }

    /**
     * Runs several searches in one round trip.
     *
     * <p>The lexical and vector retrievers are independent, so issuing them as two sequential
     * HTTP calls would pay the network latency twice for no reason. {@code _msearch} makes
     * hybrid retrieval cost the same number of round trips as a single-retriever search.
     */
    public JsonNode multiSearch(String ndjson) {
        String body = client.post()
                .uri("/_msearch")
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(ndjson)
                .retrieve()
                .body(String.class);
        return Json.tree(body == null ? "{}" : body);
    }

    public void refresh() {
        client.post()
                .uri("/{index}/_refresh", properties.getIndexName())
                .exchange((request, response) -> null, false);
    }

    public void deleteIndex() {
        client.delete()
                .uri("/{index}", properties.getIndexName())
                .exchange((request, response) -> null, false);
    }

    public long documentCount() {
        String body = client.get()
                .uri("/{index}/_count", properties.getIndexName())
                .exchange((request, response) -> response.getStatusCode().is2xxSuccessful()
                        ? new String(response.getBody().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8)
                        : "{\"count\":0}", false);
        return Json.tree(body == null ? "{\"count\":0}" : body).path("count").asLong();
    }

    public String indexName() {
        return properties.getIndexName();
    }
}
