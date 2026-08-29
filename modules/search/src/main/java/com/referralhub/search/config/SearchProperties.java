package com.referralhub.search.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "referralhub.search")
public class SearchProperties {

    private String opensearchUri = "http://localhost:9200";
    private String indexName = "jobs_v1";

    /** Whether this process consumes canonicalization events and writes to the index. */
    private boolean indexerEnabled = true;

    /** Documents each retriever returns before fusion. */
    private int candidateDepth = 200;

    /** RRF damping constant. */
    private int rrfK = 60;

    private double lexicalWeight = 1.0;
    private double vectorWeight = 1.0;

    /** Age at which a posting is worth half as much as an identical fresh one. */
    private Duration freshnessHalfLife = Duration.ofDays(14);

    private Duration requestTimeout = Duration.ofSeconds(5);

    public String getOpensearchUri() {
        return opensearchUri;
    }

    public void setOpensearchUri(String opensearchUri) {
        this.opensearchUri = opensearchUri;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public boolean isIndexerEnabled() {
        return indexerEnabled;
    }

    public void setIndexerEnabled(boolean indexerEnabled) {
        this.indexerEnabled = indexerEnabled;
    }

    public int getCandidateDepth() {
        return candidateDepth;
    }

    public void setCandidateDepth(int candidateDepth) {
        this.candidateDepth = candidateDepth;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public double getLexicalWeight() {
        return lexicalWeight;
    }

    public void setLexicalWeight(double lexicalWeight) {
        this.lexicalWeight = lexicalWeight;
    }

    public double getVectorWeight() {
        return vectorWeight;
    }

    public void setVectorWeight(double vectorWeight) {
        this.vectorWeight = vectorWeight;
    }

    public Duration getFreshnessHalfLife() {
        return freshnessHalfLife;
    }

    public void setFreshnessHalfLife(Duration freshnessHalfLife) {
        this.freshnessHalfLife = freshnessHalfLife;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }
}
