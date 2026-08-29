package com.referralhub.search.rank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fuses several ranked lists by rank rather than by score.
 *
 * <p>BM25 scores are unbounded and corpus-dependent; kNN cosine scores live in a fixed, narrow
 * band. Adding them — even after min-max normalization — makes the blend depend on the score
 * spread of whichever query happened to be run, so the same weighting behaves differently for
 * a one-word query and a pasted job description.
 *
 * <p>Reciprocal rank fusion sidesteps that entirely by using only ordinal position:
 * {@code score(d) = sum over retrievers of weight / (k + rank(d))}. A document ranked highly by
 * both retrievers beats one ranked highly by only one, which is exactly the behaviour hybrid
 * retrieval is for.
 */
public final class ReciprocalRankFusion {

    /**
     * The standard damping constant from the original RRF paper.
     *
     * <p>It flattens the difference between ranks 1 and 2 relative to the difference between
     * ranks 1 and 50, so a single retriever's confident top hit cannot dominate a document that
     * both retrievers liked.
     */
    public static final int DEFAULT_K = 60;

    private ReciprocalRankFusion() {
    }

    public record Contribution(UUID id, double fusedScore, Integer lexicalRank, Integer vectorRank) {
    }

    /**
     * @param lists ranked lists, each already ordered best-first
     * @param weights one weight per list, in the same order
     */
    public static List<Contribution> fuse(List<List<RankedId>> lists, List<Double> weights, int k) {
        if (lists.size() != weights.size()) {
            throw new IllegalArgumentException("one weight is required per ranked list");
        }
        Map<UUID, double[]> scores = new LinkedHashMap<>();
        Map<UUID, Integer[]> ranks = new LinkedHashMap<>();

        for (int listIndex = 0; listIndex < lists.size(); listIndex++) {
            List<RankedId> list = lists.get(listIndex);
            double weight = weights.get(listIndex);
            for (RankedId entry : list) {
                scores.computeIfAbsent(entry.id(), id -> new double[1])[0] +=
                        weight / (k + entry.rank());
                Integer[] perList = ranks.computeIfAbsent(entry.id(),
                        id -> new Integer[lists.size()]);
                perList[listIndex] = entry.rank();
            }
        }

        List<Contribution> fused = new ArrayList<>(scores.size());
        for (Map.Entry<UUID, double[]> entry : scores.entrySet()) {
            Integer[] perList = ranks.get(entry.getKey());
            fused.add(new Contribution(
                    entry.getKey(),
                    entry.getValue()[0],
                    perList.length > 0 ? perList[0] : null,
                    perList.length > 1 ? perList[1] : null));
        }
        // Ties broken by id so pagination is stable across identical queries.
        fused.sort(Comparator.comparingDouble(Contribution::fusedScore).reversed()
                .thenComparing(Contribution::id));
        return fused;
    }

    public static List<Contribution> fuse(List<List<RankedId>> lists, List<Double> weights) {
        return fuse(lists, weights, DEFAULT_K);
    }
}
