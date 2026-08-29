package com.referralhub.dedup.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.referralhub.common.json.Json;
import com.referralhub.dedup.config.DedupProperties;
import com.referralhub.dedup.minhash.LshBanding;
import com.referralhub.dedup.minhash.MinHasher;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The regression gate for duplicate detection quality.
 *
 * <p>A deduplicator can be broken in two directions and only one of them is visible from the
 * outside. Over-merging is loud — a seeker sees one listing where three companies were hiring —
 * so precision is gated hard. Under-merging is quiet, showing up only as a cluttered result
 * page, so recall is gated too, but lower.
 *
 * <p>The fixture set is deliberately adversarial rather than easy: every "distinct" pair shares
 * the same company boilerplate, and several share a title, a level or a location. Pairs that
 * differ in every field would prove nothing.
 *
 * <p>24 labelled pairs is a small sample and this test can be overfitted by tuning weights until
 * it passes. Treated as a regression gate on known-hard cases, not as a claim of production
 * accuracy — see docs/adr/0004-two-stage-deduplication.md.
 */
class DuplicateDetectionQualityTest {

    private final DedupProperties properties = new DedupProperties();
    private final DuplicateScorer scorer = new DuplicateScorer(properties);
    private final MinHasher hasher = new MinHasher(properties.getNumHashes(), properties.getHashSeed());

    private record LabelledPair(boolean duplicate, String reason,
                                JobFingerprint left, JobFingerprint right) {
    }

    private List<LabelledPair> loadPairs() {
        String json;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/duplicate-pairs.json")) {
            if (in == null) {
                throw new IllegalStateException("fixture set is missing");
            }
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        List<LabelledPair> pairs = new ArrayList<>();
        for (JsonNode node : Json.tree(json)) {
            pairs.add(new LabelledPair(
                    "duplicate".equals(node.path("label").asText()),
                    node.path("reason").asText(),
                    fingerprint(node.path("left")),
                    fingerprint(node.path("right"))));
        }
        return pairs;
    }

    /** Company slugs are mapped to stable ids so "acme" is the same company in both halves. */
    private JobFingerprint fingerprint(JsonNode node) {
        UUID companyId = UUID.nameUUIDFromBytes(
                node.path("company").asText().getBytes(StandardCharsets.UTF_8));
        return JobFingerprint.of(UUID.randomUUID(), companyId,
                node.path("title").asText(),
                node.path("description").asText(),
                node.path("location").asText(),
                node.path("remote").asBoolean(),
                hasher, properties.getShingleSize());
    }

    @Test
    @DisplayName("precision and recall on the labelled set clear their configured floors")
    void meetsPrecisionAndRecallTargets() {
        List<LabelledPair> pairs = loadPairs();
        double threshold = properties.getMatchThreshold();

        int truePositives = 0;
        int falsePositives = 0;
        int falseNegatives = 0;
        List<String> mistakes = new ArrayList<>();

        for (LabelledPair pair : pairs) {
            MatchScore score = scorer.score(pair.left(), pair.right());
            boolean predicted = score.isDuplicate(threshold);

            if (predicted && pair.duplicate()) {
                truePositives++;
            } else if (predicted) {
                falsePositives++;
                mistakes.add("FALSE MERGE (%.3f): %s".formatted(score.total(), pair.reason()));
            } else if (pair.duplicate()) {
                falseNegatives++;
                mistakes.add("MISSED (%.3f): %s".formatted(score.total(), pair.reason()));
            }
        }

        double precision = truePositives + falsePositives == 0
                ? 1.0 : (double) truePositives / (truePositives + falsePositives);
        double recall = truePositives + falseNegatives == 0
                ? 1.0 : (double) truePositives / (truePositives + falseNegatives);

        assertThat(precision)
                .as("precision on %d labelled pairs (threshold %.2f)%n  %s",
                        pairs.size(), threshold, String.join("%n  ".formatted(), mistakes))
                .isGreaterThanOrEqualTo(properties.getTargetPrecision());

        assertThat(recall)
                .as("recall on %d labelled pairs (threshold %.2f)%n  %s",
                        pairs.size(), threshold, String.join("%n  ".formatted(), mistakes))
                .isGreaterThanOrEqualTo(properties.getTargetRecall());
    }

    @Test
    @DisplayName("every labelled duplicate is retrieved as an LSH candidate before scoring")
    void lshRetrievesEveryTrueDuplicate() {
        LshBanding banding = LshBanding.of(properties.getNumHashes(), properties.getBands());
        List<String> missed = new ArrayList<>();

        for (LabelledPair pair : loadPairs()) {
            if (!pair.duplicate()) {
                continue;
            }
            long[] left = banding.bandHashes(pair.left().signature());
            long[] right = banding.bandHashes(pair.right().signature());

            boolean shareABand = false;
            for (int band = 0; band < left.length && !shareABand; band++) {
                shareABand = left[band] == right[band];
            }
            if (!shareABand) {
                missed.add(pair.reason());
            }
        }

        // Retrieval is the ceiling on recall: a pair that never becomes a candidate can never be
        // scored, however good the scorer is.
        assertThat(missed).as("true duplicates that LSH failed to retrieve").isEmpty();
    }

    @Test
    @DisplayName("a posting scored against itself is always a duplicate")
    void identityIsAlwaysADuplicate() {
        for (LabelledPair pair : loadPairs()) {
            MatchScore score = scorer.score(pair.left(), pair.left());
            assertThat(score.total()).isGreaterThanOrEqualTo(properties.getMatchThreshold());
        }
    }

    @Test
    @DisplayName("scoring is symmetric — merge decisions cannot depend on arrival order")
    void scoringIsSymmetric() {
        for (LabelledPair pair : loadPairs()) {
            double forward = scorer.score(pair.left(), pair.right()).total();
            double backward = scorer.score(pair.right(), pair.left()).total();
            assertThat(forward).isEqualTo(backward, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    @DisplayName("weights that do not sum to 1 are rejected at construction, not at scoring time")
    void rejectsMisconfiguredWeights() {
        DedupProperties broken = new DedupProperties();
        broken.getWeights().setJaccard(0.9);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DuplicateScorer(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sum to 1.0");
    }

    /** Prints the full score table; invaluable when a threshold change moves a decision. */
    @Test
    @DisplayName("score distribution separates the two classes")
    void classesAreSeparated() {
        List<LabelledPair> pairs = loadPairs();
        double worstDuplicate = pairs.stream().filter(LabelledPair::duplicate)
                .mapToDouble(p -> scorer.score(p.left(), p.right()).total()).min().orElseThrow();
        double bestDistinct = pairs.stream().filter(p -> !p.duplicate())
                .mapToDouble(p -> scorer.score(p.left(), p.right()).total()).max().orElseThrow();

        System.out.printf("worst duplicate = %.4f, best distinct = %.4f, threshold = %.2f%n",
                worstDuplicate, bestDistinct, properties.getMatchThreshold());

        assertThat(worstDuplicate)
                .as("the hardest true duplicate must still outscore the hardest false one")
                .isGreaterThan(bestDistinct);
    }

    @Test
    @DisplayName("MinHash estimates track exact Jaccard closely enough to rank candidates")
    void minHashEstimateTracksExactJaccard() {
        double worstError = 0;
        for (LabelledPair pair : loadPairs()) {
            double exact = com.referralhub.common.text.Shingles.jaccard(
                    pair.left().shingles(), pair.right().shingles());
            double estimate = MinHasher.estimateJaccard(
                    pair.left().signature(), pair.right().signature());
            worstError = Math.max(worstError, Math.abs(exact - estimate));
        }
        // Standard error of a 128-hash MinHash estimate is about 1/sqrt(128) ~= 0.088.
        assertThat(worstError).isLessThan(0.15);
        System.out.printf("worst |exact - minhash| over %d pairs = %.4f%n",
                loadPairs().size(), worstError);
    }

    @Test
    @DisplayName("signatures are deterministic across instances, or nothing indexed yesterday matches")
    void signaturesAreStableAcrossInstances() {
        MinHasher other = new MinHasher(properties.getNumHashes(), properties.getHashSeed());
        for (LabelledPair pair : loadPairs()) {
            assertThat(other.signature(pair.left().shingles()))
                    .isEqualTo(Arrays.copyOf(pair.left().signature(), properties.getNumHashes()));
        }
    }
}
