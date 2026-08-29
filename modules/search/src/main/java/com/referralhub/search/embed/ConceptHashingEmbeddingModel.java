package com.referralhub.search.embed;

import com.referralhub.common.text.TextNormalizer;
import com.referralhub.common.text.Tokens;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * A deterministic, offline embedding model.
 *
 * <p><b>What this is not:</b> it is not a neural sentence encoder. It cannot represent word
 * order, negation, or any similarity it has not been told about. Calling the output
 * "semantic" would be overselling it.
 *
 * <p><b>What it is:</b> a signed random projection over two feature spaces — the literal tokens
 * of the text, and the {@link JobDomainOntology} concepts those tokens map to, with concepts
 * weighted several times higher. That single design choice is what makes zero-overlap retrieval
 * work: "k8s platform work" and "container orchestration engineering" share no token, but both
 * project onto the {@code kubernetes} axis, so their vectors are close.
 *
 * <p>It was chosen over a hosted embedding API because the whole platform must run from
 * {@code docker compose up} with no API keys, and over a bundled ONNX model because a 90 MB
 * artifact in the repository is a bad trade for a portfolio project. The {@link EmbeddingModel}
 * interface exists so that swapping in either is a one-class change plus a reindex.
 */
@Component
public class ConceptHashingEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 256;

    /**
     * Concepts carry far more signal than raw tokens: a token match is often boilerplate, while
     * a concept match survived a curated lexicon. Weighting them equally would let a long
     * description's noise drown the handful of concepts that make it findable.
     */
    private static final float CONCEPT_WEIGHT = 4.0f;
    private static final float TOKEN_WEIGHT = 1.0f;

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public String modelId() {
        return "concept-hash-v1-d" + DIMENSIONS;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        String normalized = TextNormalizer.canonical(text);

        for (String concept : JobDomainOntology.conceptsIn(normalized)) {
            accumulate(vector, "concept:" + concept, CONCEPT_WEIGHT);
        }
        for (String token : Tokens.of(normalized)) {
            accumulate(vector, "token:" + token, TOKEN_WEIGHT);
        }
        return normalize(vector);
    }

    /**
     * Signed hashing: the sign bit of a second hash decides the direction.
     *
     * <p>Without the sign, every feature adds positively to its bucket and unrelated documents
     * drift toward a common direction, which flattens cosine similarity across the whole corpus.
     */
    private static void accumulate(float[] vector, String feature, float weight) {
        long hash = hash64(feature);
        int bucket = (int) Math.floorMod(hash, DIMENSIONS);
        float sign = ((hash >>> 63) & 1L) == 0L ? 1.0f : -1.0f;
        vector[bucket] += sign * weight;
    }

    private static float[] normalize(float[] vector) {
        double sumOfSquares = 0;
        for (float value : vector) {
            sumOfSquares += (double) value * value;
        }
        if (sumOfSquares == 0) {
            // An empty document must still be a valid unit vector or kNN scoring divides by zero.
            vector[0] = 1.0f;
            return vector;
        }
        float inverseNorm = (float) (1.0 / Math.sqrt(sumOfSquares));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= inverseNorm;
        }
        return vector;
    }

    /** Cosine similarity of two unit vectors, i.e. their dot product. */
    public static double cosine(float[] left, float[] right) {
        double dot = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
        }
        return dot;
    }

    private static long hash64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFF);
            hash *= 0x100000001b3L;
        }
        // Final avalanche so adjacent features do not land in adjacent buckets.
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        return hash;
    }

    /** Exposed for the indexer's dimension assertion and for benchmarks. */
    public List<String> conceptsOf(String text) {
        return JobDomainOntology.conceptsIn(TextNormalizer.canonical(text));
    }
}
