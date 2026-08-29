package com.referralhub.common.text;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Word k-shingles.
 *
 * <p>Shingling is what makes near-duplicate detection resistant to the way ATS boards reflow a
 * description: a company that adds one sentence to a posting changes almost none of its
 * shingles, while two genuinely different postings for the same title share very few.
 */
public final class Shingles {

    public static final int DEFAULT_K = 3;

    private Shingles() {
    }

    public static Set<String> of(List<String> tokens, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("shingle size must be >= 1");
        }
        Set<String> shingles = new LinkedHashSet<>();
        if (tokens.size() < k) {
            // Short texts (most titles) shingle as the whole token list, so they still compare.
            if (!tokens.isEmpty()) {
                shingles.add(String.join(" ", tokens));
            }
            return shingles;
        }
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i + k <= tokens.size(); i++) {
            buffer.setLength(0);
            for (int j = 0; j < k; j++) {
                if (j > 0) {
                    buffer.append(' ');
                }
                buffer.append(tokens.get(i + j));
            }
            shingles.add(buffer.toString());
        }
        return shingles;
    }

    public static Set<String> of(List<String> tokens) {
        return of(tokens, DEFAULT_K);
    }

    /** Jaccard similarity, computed exactly. Used to score LSH candidates, never to find them. */
    public static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 1.0;
        }
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = smaller == left ? right : left;
        int intersection = 0;
        for (String s : smaller) {
            if (larger.contains(s)) {
                intersection++;
            }
        }
        int union = left.size() + right.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }
}
