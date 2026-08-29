package com.referralhub.search.embed;

import static org.assertj.core.api.Assertions.assertThat;

import com.referralhub.common.text.Tokens;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The zero-overlap retrieval claim, proved at the level where it is actually decided.
 *
 * <p>Whether "k8s" finds "container orchestration" is a property of the vector space, not of
 * OpenSearch: if the vectors are close, HNSW will return them, and if they are not, no amount of
 * index tuning will help. Testing it here means the claim is verified on every build rather than
 * only when a Docker daemon happens to be available; the integration test then confirms the same
 * behaviour survives the round trip through the index.
 */
class ConceptEmbeddingTest {

    private final ConceptHashingEmbeddingModel model = new ConceptHashingEmbeddingModel();

    private static void assertNoTokenOverlap(String left, String right) {
        Set<String> leftTokens = new HashSet<>(Tokens.fromRaw(left));
        Set<String> rightTokens = new HashSet<>(Tokens.fromRaw(right));
        leftTokens.retainAll(rightTokens);
        assertThat(leftTokens)
                .as("the premise of this test is that these share no term")
                .isEmpty();
    }

    private double similarity(String left, String right) {
        return ConceptHashingEmbeddingModel.cosine(model.embed(left), model.embed(right));
    }

    @Test
    @DisplayName("'k8s' retrieves 'container orchestration' despite zero token overlap")
    void kubernetesAbbreviationMatchesExpansion() {
        String query = "k8s";
        String job = "container orchestration";
        assertNoTokenOverlap(query, job);

        assertThat(similarity(query, job))
                .as("both project onto the kubernetes concept axis")
                .isGreaterThan(0.9);
    }

    @Test
    @DisplayName("a paraphrased query outranks a lexically closer but unrelated job")
    void paraphraseBeatsLexicalDecoy() {
        String query = "k8s and terraform work";

        String rightJob = "Site Reliability Engineer. You will own container orchestration and "
                + "infrastructure as code for every product team.";
        String decoyJob = "Recruiting Coordinator. You will own interview scheduling and "
                + "candidate experience, and you will work with every product team.";

        assertThat(similarity(query, rightJob))
                .as("concept overlap must beat incidental word overlap")
                .isGreaterThan(similarity(query, decoyJob));
    }

    @Test
    @DisplayName("abbreviation, expansion and jargon all land near each other")
    void synonymFamiliesCluster() {
        assertThat(similarity("ML engineer", "deep learning")).isGreaterThan(0.5);
        assertThat(similarity("SRE", "site reliability engineering")).isGreaterThan(0.5);
        assertThat(similarity("golang", "go language")).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("unrelated domains do not become similar just because both are jobs")
    void unrelatedDomainsStayApart() {
        double related = similarity("kubernetes platform", "container orchestration");
        double unrelated = similarity("kubernetes platform", "product designer figma");

        assertThat(unrelated).isLessThan(0.4);
        assertThat(related).isGreaterThan(unrelated + 0.4);
    }

    @Test
    @DisplayName("every vector is unit length, because cosine scoring assumes it")
    void vectorsAreUnitLength() {
        for (String text : new String[] {"", "   ", "kubernetes", "a very long job description "
                .repeat(50)}) {
            float[] vector = model.embed(text);
            double norm = Math.sqrt(ConceptHashingEmbeddingModel.cosine(vector, vector));
            assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        }
    }

    @Test
    @DisplayName("the model is deterministic, or yesterday's index is unsearchable today")
    void embeddingIsDeterministic() {
        assertThat(model.embed("staff engineer kubernetes"))
                .isEqualTo(new ConceptHashingEmbeddingModel().embed("staff engineer kubernetes"));
    }

    @Test
    @DisplayName("dimensions and model id are stable, since the index mapping is pinned to them")
    void contractIsStable() {
        assertThat(model.dimensions()).isEqualTo(256);
        assertThat(model.modelId()).isEqualTo("concept-hash-v1-d256");
    }

    @Test
    @DisplayName("multi-word concepts are recognised, not just single tokens")
    void multiWordConceptsAreMatched() {
        assertThat(model.conceptsOf("we do learning to rank on a big corpus")).contains("ranking");
        assertThat(model.conceptsOf("infrastructure as code with terraform")).contains("iac");
        assertThat(model.conceptsOf("double entry ledger reconciliation")).contains("payments");
    }

    @Test
    @DisplayName("the longest surface form wins, so an SRE posting is not merely 'engineering'")
    void longestSurfaceFormWins() {
        assertThat(model.conceptsOf("site reliability engineer")).contains("reliability");
    }
}
