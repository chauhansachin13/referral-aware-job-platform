package com.referralhub.search.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {

    private static final UUID AGREED = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID LEXICAL_ONLY = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID VECTOR_ONLY = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    @DisplayName("a document both retrievers rank well beats one only a single retriever loves")
    void agreementBeatsSingleRetrieverConfidence() {
        List<RankedId> lexical = List.of(
                new RankedId(LEXICAL_ONLY, 1, 42.0),
                new RankedId(AGREED, 2, 30.0));
        List<RankedId> vector = List.of(
                new RankedId(VECTOR_ONLY, 1, 0.98),
                new RankedId(AGREED, 2, 0.95));

        List<ReciprocalRankFusion.Contribution> fused =
                ReciprocalRankFusion.fuse(List.of(lexical, vector), List.of(1.0, 1.0));

        assertThat(fused.get(0).id()).isEqualTo(AGREED);
        assertThat(fused.get(0).lexicalRank()).isEqualTo(2);
        assertThat(fused.get(0).vectorRank()).isEqualTo(2);
    }

    @Test
    @DisplayName("fusion ignores raw scores, so an unbounded BM25 score cannot swamp cosine")
    void rawScoreMagnitudeIsIrrelevant() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        List<ReciprocalRankFusion.Contribution> modest = ReciprocalRankFusion.fuse(
                List.of(List.of(new RankedId(first, 1, 2.0), new RankedId(second, 2, 1.9)),
                        List.of(new RankedId(second, 1, 0.9))),
                List.of(1.0, 1.0));

        List<ReciprocalRankFusion.Contribution> enormous = ReciprocalRankFusion.fuse(
                List.of(List.of(new RankedId(first, 1, 9_000.0), new RankedId(second, 2, 8_999.0)),
                        List.of(new RankedId(second, 1, 0.9))),
                List.of(1.0, 1.0));

        assertThat(modest.get(0).id()).isEqualTo(enormous.get(0).id());
        assertThat(modest.get(0).fusedScore()).isEqualTo(enormous.get(0).fusedScore());
    }

    @Test
    @DisplayName("weighting a retriever up moves its exclusive hits up the fused list")
    void weightsShiftTheBlend() {
        List<RankedId> lexical = List.of(new RankedId(LEXICAL_ONLY, 1, 10.0));
        List<RankedId> vector = List.of(new RankedId(VECTOR_ONLY, 1, 0.9));

        assertThat(ReciprocalRankFusion.fuse(List.of(lexical, vector), List.of(5.0, 1.0)).get(0).id())
                .isEqualTo(LEXICAL_ONLY);
        assertThat(ReciprocalRankFusion.fuse(List.of(lexical, vector), List.of(1.0, 5.0)).get(0).id())
                .isEqualTo(VECTOR_ONLY);
    }

    @Test
    @DisplayName("k damps the gap between adjacent ranks")
    void dampingConstantFlattensTopRanks() {
        List<RankedId> single = List.of(new RankedId(AGREED, 1, 1), new RankedId(LEXICAL_ONLY, 2, 1));

        double smallK = gap(ReciprocalRankFusion.fuse(List.of(single), List.of(1.0), 1));
        double largeK = gap(ReciprocalRankFusion.fuse(List.of(single), List.of(1.0), 60));

        assertThat(largeK).isLessThan(smallK);
    }

    @Test
    @DisplayName("ties break deterministically so pagination cannot shuffle between pages")
    void tiesAreStable() {
        List<RankedId> a = List.of(new RankedId(AGREED, 1, 1.0));
        List<RankedId> b = List.of(new RankedId(LEXICAL_ONLY, 1, 1.0));

        List<UUID> first = ReciprocalRankFusion.fuse(List.of(a, b), List.of(1.0, 1.0))
                .stream().map(ReciprocalRankFusion.Contribution::id).toList();
        List<UUID> second = ReciprocalRankFusion.fuse(List.of(b, a), List.of(1.0, 1.0))
                .stream().map(ReciprocalRankFusion.Contribution::id).toList();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("an empty retriever contributes nothing rather than breaking fusion")
    void handlesEmptyLists() {
        List<ReciprocalRankFusion.Contribution> fused = ReciprocalRankFusion.fuse(
                List.of(List.of(new RankedId(AGREED, 1, 1.0)), List.of()), List.of(1.0, 1.0));

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).vectorRank()).isNull();
    }

    @Test
    @DisplayName("a weight per list is required")
    void rejectsMismatchedWeights() {
        assertThatThrownBy(() -> ReciprocalRankFusion.fuse(List.of(List.of(), List.of()), List.of(1.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static double gap(List<ReciprocalRankFusion.Contribution> fused) {
        return fused.get(0).fusedScore() - fused.get(1).fusedScore();
    }
}
