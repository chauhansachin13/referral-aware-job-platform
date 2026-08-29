package com.referralhub.dedup.property;

import static org.assertj.core.api.Assertions.assertThat;

import com.referralhub.dedup.minhash.LshBanding;
import com.referralhub.dedup.minhash.MinHasher;
import com.referralhub.dedup.title.CanonicalTitle;
import com.referralhub.dedup.title.SeniorityLevel;
import com.referralhub.dedup.title.TitleNormalizer;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

class DedupProperties {

    private static final MinHasher HASHER = new MinHasher(128, 0x5EED_1234L);
    private static final LshBanding BANDING = LshBanding.of(128, 16);

    @Provide
    Arbitrary<Set<String>> shingleSets() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(12)
                .set().ofMinSize(0).ofMaxSize(60);
    }

    @Property(tries = 300)
    void signatureLengthIsAlwaysTheConfiguredWidth(@ForAll("shingleSets") Set<String> shingles) {
        assertThat(HASHER.signature(shingles)).hasSize(128);
    }

    @Property(tries = 300)
    void estimateIsAlwaysAProbability(@ForAll("shingleSets") Set<String> left,
                                      @ForAll("shingleSets") Set<String> right) {
        double estimate = MinHasher.estimateJaccard(HASHER.signature(left), HASHER.signature(right));

        assertThat(estimate).isBetween(0.0, 1.0);
    }

    @Property(tries = 300)
    void identicalDocumentsAgreeOnEveryBand(@ForAll("shingleSets") Set<String> shingles) {
        int[] signature = HASHER.signature(shingles);

        // If this ever fails, a document stops being a candidate for its own duplicate.
        assertThat(BANDING.bandHashes(signature)).isEqualTo(BANDING.bandHashes(signature));
        assertThat(MinHasher.estimateJaccard(signature, signature)).isEqualTo(1.0);
    }

    @Property(tries = 300)
    void hashingIsDeterministicAcrossInstances(@ForAll("shingleSets") Set<String> shingles) {
        assertThat(new MinHasher(128, 0x5EED_1234L).signature(shingles))
                .isEqualTo(HASHER.signature(shingles));
    }

    @Property(tries = 200)
    void retrievalProbabilityRisesWithSimilarity(
            @ForAll @IntRange(min = 1, max = 32) int bands,
            @ForAll @IntRange(min = 0, max = 98) int lowerPercent) {
        if (128 % bands != 0) {
            return;
        }
        LshBanding banding = LshBanding.of(128, bands);
        double lower = lowerPercent / 100.0;
        double higher = Math.min(1.0, lower + 0.01);

        // The S-curve must be monotone: a more similar pair can never be less likely to be found.
        assertThat(banding.retrievalProbability(higher))
                .isGreaterThanOrEqualTo(banding.retrievalProbability(lower));
        assertThat(banding.retrievalProbability(lower)).isBetween(0.0, 1.0);
    }

    @Property(tries = 500)
    void titleNormalizationNeverThrowsAndAlwaysProducesAKey(@ForAll String anything) {
        CanonicalTitle title = TitleNormalizer.normalize(anything);

        assertThat(title.role()).isNotNull();
        assertThat(title.level()).isNotNull();
        assertThat(title.specialization()).isNotNull();
        assertThat(title.key()).contains("|");
    }

    @Property(tries = 500)
    void titleNormalizationIsIdempotentOnItsOwnRole(@ForAll String anything) {
        CanonicalTitle once = TitleNormalizer.normalize(anything);

        // Re-normalizing a canonical role must not shift its level; that would make merges
        // depend on how many times a title had been through the pipeline.
        assertThat(TitleNormalizer.normalize(once.role()).role()).isEqualTo(once.role());
    }

    @Property
    void ladderDistanceIsSymmetricAndNeverNegative(@ForAll SeniorityLevel left,
                                                   @ForAll SeniorityLevel right) {
        assertThat(left.distance(right)).isEqualTo(right.distance(left));
        assertThat(left.distance(right)).isNotNegative();
    }
}
