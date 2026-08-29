package com.referralhub.search.embed;

/**
 * Turns text into a dense vector for kNN retrieval.
 *
 * <p>An interface rather than a concrete class because the vector source is the part of this
 * system most likely to be replaced: today it is a deterministic offline model (see
 * {@link ConceptHashingEmbeddingModel}), tomorrow it could be an ONNX sentence encoder or a
 * hosted embedding API. What must not change is the index mapping's dimension, so
 * {@link #dimensions()} is part of the contract and the indexer asserts on it.
 */
public interface EmbeddingModel {

    int dimensions();

    /** A unit-length vector. Callers rely on this: cosine similarity assumes it. */
    float[] embed(String text);

    /** Identifier written into the index so a model change is detectable, not silent. */
    String modelId();
}
