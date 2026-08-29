package com.referralhub.search.property;

import static org.assertj.core.api.Assertions.assertThat;

import com.referralhub.search.embed.ConceptHashingEmbeddingModel;
import com.referralhub.search.query.Cursor;
import com.referralhub.search.rank.FreshnessDecay;
import com.referralhub.search.rank.RankedId;
import com.referralhub.search.rank.ReciprocalRankFusion;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

class RankingProperties {

    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");
    private static final ConceptHashingEmbeddingModel MODEL = new ConceptHashingEmbeddingModel();

    @Provide
    Arbitrary<List<Integer>> rankLists() {
        return Arbitraries.integers().between(0, 40).list().ofMaxSize(40).uniqueElements();
    }

    private static List<RankedId> ranked(List<Integer> ids) {
        List<RankedId> list = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            list.add(new RankedId(new UUID(0L, ids.get(i)), i + 1, 1.0 / (i + 1)));
        }
        return list;
    }

    @Property(tries = 300)
    void fusionOutputsEachDocumentExactlyOnce(@ForAll("rankLists") List<Integer> lexical,
                                              @ForAll("rankLists") List<Integer> vector) {
        List<ReciprocalRankFusion.Contribution> fused = ReciprocalRankFusion.fuse(
                List.of(ranked(lexical), ranked(vector)), List.of(1.0, 1.0));

        Set<UUID> seen = new HashSet<>();
        fused.forEach(c -> assertThat(seen.add(c.id())).isTrue());

        Set<Integer> union = new HashSet<>(lexical);
        union.addAll(vector);
        assertThat(fused).hasSize(union.size());
    }

    @Property(tries = 300)
    void fusedResultsAreOrderedByScore(@ForAll("rankLists") List<Integer> lexical,
                                       @ForAll("rankLists") List<Integer> vector) {
        List<ReciprocalRankFusion.Contribution> fused = ReciprocalRankFusion.fuse(
                List.of(ranked(lexical), ranked(vector)), List.of(1.0, 1.0));

        for (int i = 1; i < fused.size(); i++) {
            assertThat(fused.get(i - 1).fusedScore())
                    .isGreaterThanOrEqualTo(fused.get(i).fusedScore());
        }
    }

    @Property(tries = 300)
    void fusionIsStableUnderRetrieverOrder(@ForAll("rankLists") List<Integer> lexical,
                                           @ForAll("rankLists") List<Integer> vector) {
        List<UUID> forward = ReciprocalRankFusion
                .fuse(List.of(ranked(lexical), ranked(vector)), List.of(1.0, 1.0))
                .stream().map(ReciprocalRankFusion.Contribution::id).toList();
        List<UUID> reversed = ReciprocalRankFusion
                .fuse(List.of(ranked(vector), ranked(lexical)), List.of(1.0, 1.0))
                .stream().map(ReciprocalRankFusion.Contribution::id).toList();

        // Pagination re-runs retrieval; an unstable order would repeat or skip results.
        assertThat(forward).containsExactlyElementsOf(reversed);
    }

    @Property(tries = 500)
    void decayIsAlwaysAMultiplierBetweenZeroAndOne(
            @ForAll @LongRange(min = -365, max = 3_650) long ageDays,
            @ForAll @IntRange(min = 1, max = 365) int halfLifeDays) {

        double multiplier = FreshnessDecay.multiplier(NOW.minus(Duration.ofDays(ageDays)), NOW,
                Duration.ofDays(halfLifeDays));

        // Not an open lower bound: extreme ages underflow to exactly zero. See FreshnessDecay.
        assertThat(multiplier).isBetween(0.0, 1.0);
    }

    @Property(tries = 500)
    void olderIsNeverWorthMore(@ForAll @LongRange(min = 0, max = 3_650) long ageDays) {
        Duration halfLife = Duration.ofDays(14);

        double newer = FreshnessDecay.multiplier(NOW.minus(Duration.ofDays(ageDays)), NOW, halfLife);
        double older = FreshnessDecay.multiplier(
                NOW.minus(Duration.ofDays(ageDays + 1)), NOW, halfLife);

        assertThat(older).isLessThanOrEqualTo(newer);
    }

    @Property(tries = 500)
    void cursorsRoundTripAndRefuseForeignQueries(
            @ForAll @IntRange(min = 0, max = Cursor.MAX_OFFSET) int offset,
            @ForAll int queryHash,
            @ForAll int otherHash) {

        Cursor cursor = new Cursor(offset, queryHash);
        assertThat(Cursor.decode(cursor.encode(), queryHash)).isEqualTo(cursor);

        if (otherHash != queryHash) {
            assertThat(org.assertj.core.api.Assertions
                    .catchThrowable(() -> Cursor.decode(cursor.encode(), otherHash)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Property(tries = 300)
    void everyEmbeddingIsAUnitVectorOfTheDeclaredWidth(@ForAll String text) {
        float[] vector = MODEL.embed(text);

        assertThat(vector).hasSize(MODEL.dimensions());
        double norm = Math.sqrt(ConceptHashingEmbeddingModel.cosine(vector, vector));
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Property(tries = 300)
    void cosineSimilarityStaysWithinItsRange(@ForAll String left, @ForAll String right) {
        double cosine = ConceptHashingEmbeddingModel.cosine(MODEL.embed(left), MODEL.embed(right));

        assertThat(cosine).isBetween(-1.0001, 1.0001);
        assertThat(Double.isFinite(cosine)).isTrue();
    }
}
