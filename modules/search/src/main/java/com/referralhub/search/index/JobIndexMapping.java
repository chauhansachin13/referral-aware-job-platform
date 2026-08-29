package com.referralhub.search.index;

/**
 * The index definition, written out rather than generated.
 *
 * <p>Two things here are load-bearing. {@code index.knn} must be enabled at creation time — it
 * cannot be turned on later without a reindex. And the {@code knn_vector} dimension must match
 * {@link com.referralhub.search.embed.EmbeddingModel#dimensions()} exactly; a mismatch is
 * rejected per document at index time, which shows up as a slow trickle of failures rather than
 * an obvious startup error, so the indexer asserts on it up front.
 */
public final class JobIndexMapping {

    private JobIndexMapping() {
    }

    public static String create(int embeddingDimensions) {
        return """
                {
                  "settings": {
                    "index": {
                      "knn": true,
                      "number_of_shards": 1,
                      "number_of_replicas": 0,
                      "refresh_interval": "1s"
                    },
                    "analysis": {
                      "analyzer": {
                        "job_text": {
                          "type": "custom",
                          "tokenizer": "standard",
                          "filter": ["lowercase", "asciifolding", "english_stop", "english_stemmer"]
                        }
                      },
                      "filter": {
                        "english_stop":    { "type": "stop",    "stopwords": "_english_" },
                        "english_stemmer": { "type": "stemmer", "language": "english" }
                      }
                    }
                  },
                  "mappings": {
                    "properties": {
                      "canonical_job_id": { "type": "keyword" },
                      "company_id":       { "type": "keyword" },
                      "company_slug":     { "type": "keyword" },
                      "company_name":     { "type": "text", "analyzer": "job_text" },
                      "title":            { "type": "text", "analyzer": "job_text" },
                      "description":      { "type": "text", "analyzer": "job_text" },
                      "specialization":   { "type": "text", "analyzer": "job_text" },
                      "concepts":         { "type": "keyword" },
                      "role":             { "type": "keyword" },
                      "level":            { "type": "keyword" },
                      "location":         { "type": "text", "analyzer": "job_text",
                                            "fields": { "keyword": { "type": "keyword" } } },
                      "remote":           { "type": "boolean" },
                      "posted_at":        { "type": "date" },
                      "indexed_at":       { "type": "date" },
                      "source_count":     { "type": "integer" },
                      "model_id":         { "type": "keyword" },
                      "embedding": {
                        "type": "knn_vector",
                        "dimension": %d,
                        "method": {
                          "name": "hnsw",
                          "space_type": "cosinesimil",
                          "engine": "lucene",
                          "parameters": { "ef_construction": 128, "m": 16 }
                        }
                      }
                    }
                  }
                }
                """.formatted(embeddingDimensions);
    }
}
