package com.referralhub.search.query;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.referralhub.common.json.Json;
import java.util.List;

/**
 * Builds the two retrieval queries and the {@code _msearch} body that carries them.
 *
 * <p>Both retrievers get the same filter clauses. That matters more than it looks: if the kNN
 * leg were unfiltered, a "remote only" search would fuse a filtered lexical list with an
 * unfiltered vector list and surface on-site jobs the user explicitly excluded.
 */
public final class QueryBuilder {

    private static final List<String> SOURCE_FIELDS = List.of(
            "canonical_job_id", "title", "company_name", "company_slug", "location", "remote",
            "level", "role", "posted_at", "source_count");

    private QueryBuilder() {
    }

    /** Filter clauses shared by both legs of the hybrid query. */
    static ArrayNode filters(SearchRequest request) {
        ArrayNode filters = Json.mapper().createArrayNode();

        if (request.remote() != null) {
            ObjectNode term = Json.mapper().createObjectNode();
            term.putObject("term").put("remote", request.remote());
            filters.add(term);
        }
        if (!request.levels().isEmpty()) {
            ObjectNode terms = Json.mapper().createObjectNode();
            ArrayNode values = terms.putObject("terms").putArray("level");
            request.levels().forEach(level -> values.add(level.toUpperCase(java.util.Locale.ROOT)));
            filters.add(terms);
        }
        if (request.companySlug() != null && !request.companySlug().isBlank()) {
            ObjectNode term = Json.mapper().createObjectNode();
            term.putObject("term").put("company_slug", request.companySlug());
            filters.add(term);
        }
        if (request.location() != null && !request.location().isBlank()) {
            // A match rather than a term: "San Francisco" must find "San Francisco, CA, USA".
            ObjectNode match = Json.mapper().createObjectNode();
            match.putObject("match").put("location", request.location());
            filters.add(match);
        }
        return filters;
    }

    /**
     * BM25 leg.
     *
     * <p>Field boosts encode what a job seeker means: a term in the title is far stronger
     * evidence than the same term buried in a benefits paragraph. Fuzziness is capped at AUTO so
     * "kubernets" still works without "engineer" matching "engineers" of a different sense.
     */
    static ObjectNode lexicalQuery(SearchRequest request, int size) {
        ObjectNode root = Json.mapper().createObjectNode();
        root.put("size", size);
        root.put("track_total_hits", true);

        ObjectNode bool = root.putObject("query").putObject("bool");
        if (request.query().isBlank()) {
            bool.putObject("must").putObject("match_all");
        } else {
            ObjectNode multiMatch = bool.putObject("must").putObject("multi_match");
            multiMatch.put("query", request.query());
            multiMatch.put("type", "best_fields");
            multiMatch.put("fuzziness", "AUTO");
            ArrayNode fields = multiMatch.putArray("fields");
            fields.add("title^3");
            fields.add("specialization^2");
            fields.add("company_name^1.5");
            fields.add("concepts^2");
            fields.add("description");
            fields.add("location");
        }
        bool.set("filter", filters(request));

        ArrayNode source = root.putArray("_source");
        SOURCE_FIELDS.forEach(source::add);
        return root;
    }

    /** kNN leg over the dense vector, carrying the same filters. */
    static ObjectNode vectorQuery(SearchRequest request, float[] embedding, int size) {
        ObjectNode root = Json.mapper().createObjectNode();
        root.put("size", size);

        ObjectNode knn = root.putObject("query").putObject("knn").putObject("embedding");
        ArrayNode vector = knn.putArray("vector");
        for (float value : embedding) {
            vector.add(value);
        }
        knn.put("k", size);

        ArrayNode filters = filters(request);
        if (!filters.isEmpty()) {
            knn.putObject("filter").putObject("bool").set("filter", filters);
        }

        ArrayNode source = root.putArray("_source");
        SOURCE_FIELDS.forEach(source::add);
        return root;
    }

    /**
     * The NDJSON body for {@code _msearch}: header line, query line, header line, query line.
     * One round trip for both retrievers.
     */
    public static String multiSearchBody(String indexName, SearchRequest request,
                                         float[] embedding, int size) {
        ObjectNode header = Json.mapper().createObjectNode();
        header.put("index", indexName);
        String headerLine = header.toString();

        return headerLine + "\n"
                + lexicalQuery(request, size) + "\n"
                + headerLine + "\n"
                + vectorQuery(request, embedding, size) + "\n";
    }
}
