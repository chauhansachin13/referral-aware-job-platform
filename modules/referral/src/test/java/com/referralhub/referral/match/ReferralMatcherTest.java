package com.referralhub.referral.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReferralMatcherTest {

    private static final MatchingWeights WEIGHTS = MatchingWeights.defaults();
    private static final Instant BASE = Instant.parse("2026-08-01T00:00:00Z");

    private static ReferralMatcher.PendingRequest request(int index, String department,
                                                          String level, Set<String> stack) {
        return new ReferralMatcher.PendingRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), department, level, stack, BASE.plusSeconds(index));
    }

    private static ReferralMatcher.EligibleReferrer referrer(String department, String level,
                                                             Set<String> stack, double reputation,
                                                             int capacity) {
        return new ReferralMatcher.EligibleReferrer(UUID.randomUUID(), department, level, stack,
                reputation, capacity);
    }

    @Test
    @DisplayName("400 requests against 3 referrers never breaches a capacity limit")
    void capacityIsNeverBreached() {
        List<ReferralMatcher.PendingRequest> requests = IntStream.range(0, 400)
                .mapToObj(i -> request(i, "Payments", "SENIOR", Set.of("java", "kafka")))
                .toList();

        ReferralMatcher.EligibleReferrer alice = referrer("Payments", "STAFF",
                Set.of("java", "kafka"), 0.95, 5);
        ReferralMatcher.EligibleReferrer bob = referrer("Payments", "SENIOR",
                Set.of("java"), 0.60, 3);
        ReferralMatcher.EligibleReferrer carol = referrer("Search", "SENIOR",
                Set.of("python"), 0.40, 2);
        List<ReferralMatcher.EligibleReferrer> referrers = List.of(alice, bob, carol);

        List<ReferralMatcher.Assignment> assignments =
                ReferralMatcher.assign(requests, referrers, WEIGHTS);

        Map<UUID, Integer> perReferrer = new HashMap<>();
        assignments.forEach(a -> perReferrer.merge(a.referrerId(), 1, Integer::sum));

        for (ReferralMatcher.EligibleReferrer referrer : referrers) {
            assertThat(perReferrer.getOrDefault(referrer.id(), 0))
                    .as("referrer %s has capacity %d", referrer.id(), referrer.remainingCapacity())
                    .isLessThanOrEqualTo(referrer.remainingCapacity());
        }

        // Total capacity is 10; the other 390 requests stay pending rather than being dropped
        // into somebody's overflowing queue.
        assertThat(assignments).hasSize(10);
        assertThat(assignments).extracting(ReferralMatcher.Assignment::requestId)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("work is spread rather than saturating the single best referrer")
    void fairnessSpreadsLoad() {
        // Alice is a better match on every axis. Greedy matching would give her all four.
        ReferralMatcher.EligibleReferrer alice = referrer("Payments", "STAFF",
                Set.of("java", "kafka", "postgres"), 0.99, 4);
        ReferralMatcher.EligibleReferrer bob = referrer("Payments", "SENIOR",
                Set.of("java"), 0.70, 4);

        List<ReferralMatcher.PendingRequest> requests = IntStream.range(0, 4)
                .mapToObj(i -> request(i, "Payments", "SENIOR", Set.of("java", "kafka")))
                .toList();

        List<ReferralMatcher.Assignment> assignments =
                ReferralMatcher.assign(requests, List.of(alice, bob), WEIGHTS);

        Map<UUID, Integer> perReferrer = new HashMap<>();
        assignments.forEach(a -> perReferrer.merge(a.referrerId(), 1, Integer::sum));

        assertThat(perReferrer).hasSize(2);
        assertThat(perReferrer.get(bob.id()))
                .as("the weaker match must still receive work, or the strong one burns out")
                .isPositive();
        assertThat(perReferrer.get(alice.id()))
                .as("but the better match should still get more")
                .isGreaterThanOrEqualTo(perReferrer.get(bob.id()));
    }

    @Test
    @DisplayName("a referrer with no remaining capacity is never assigned anything")
    void fullReferrersAreSkipped() {
        ReferralMatcher.EligibleReferrer full = referrer("Payments", "STAFF",
                Set.of("java"), 0.99, 0);
        ReferralMatcher.EligibleReferrer available = referrer("Search", "MID",
                Set.of("python"), 0.10, 3);

        List<ReferralMatcher.Assignment> assignments = ReferralMatcher.assign(
                List.of(request(0, "Payments", "SENIOR", Set.of("java"))),
                List.of(full, available), WEIGHTS);

        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).referrerId()).isEqualTo(available.id());
    }

    @Test
    @DisplayName("the longest-waiting request is placed first")
    void oldestRequestWinsScarceCapacity() {
        ReferralMatcher.PendingRequest oldest = request(0, "Payments", "SENIOR", Set.of("java"));
        ReferralMatcher.PendingRequest newest = request(500, "Payments", "SENIOR", Set.of("java"));

        List<ReferralMatcher.Assignment> assignments = ReferralMatcher.assign(
                List.of(newest, oldest),
                List.of(referrer("Payments", "STAFF", Set.of("java"), 0.9, 1)), WEIGHTS);

        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).requestId()).isEqualTo(oldest.id());
    }

    @Test
    @DisplayName("a same-team, same-stack, responsive referrer outscores a distant one")
    void affinityRewardsTheRightSignals() {
        ReferralMatcher.PendingRequest pending = request(0, "Payments", "SENIOR",
                Set.of("java", "kafka"));

        double near = ReferralMatcher.affinity(pending,
                referrer("Payments", "STAFF", Set.of("java", "kafka"), 0.95, 5), WEIGHTS);
        double far = ReferralMatcher.affinity(pending,
                referrer("Design", "ENTRY", Set.of("figma"), 0.10, 5), WEIGHTS);

        assertThat(near).isGreaterThan(far);
        assertThat(near).isBetween(0.0, 1.0);
        assertThat(far).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("a referrer at or one rung above the role fits best; far above is worse")
    void seniorityFitPeaksJustAboveTheRole() {
        assertThat(ReferralMatcher.seniorityFit("SENIOR", "SENIOR")).isEqualTo(1.0);
        assertThat(ReferralMatcher.seniorityFit("SENIOR", "STAFF")).isEqualTo(1.0);
        assertThat(ReferralMatcher.seniorityFit("SENIOR", "PRINCIPAL")).isEqualTo(0.75);
        assertThat(ReferralMatcher.seniorityFit("ENTRY", "VP")).isEqualTo(0.25);
        assertThat(ReferralMatcher.seniorityFit("STAFF", "ENTRY")).isEqualTo(0.0);
        // Unknown ladders are neutral, not penalised.
        assertThat(ReferralMatcher.seniorityFit("SENIOR", "UNSPECIFIED")).isEqualTo(0.5);
    }

    @Test
    @DisplayName("an empty pool or an empty queue produces no assignments, not an exception")
    void degenerateInputsAreSafe() {
        assertThat(ReferralMatcher.assign(List.of(), List.of(
                referrer("Payments", "STAFF", Set.of(), 0.5, 5)), WEIGHTS)).isEmpty();
        assertThat(ReferralMatcher.assign(
                List.of(request(0, "Payments", "SENIOR", Set.of())), List.of(), WEIGHTS)).isEmpty();
    }

    @Test
    @DisplayName("assignment is deterministic for identical inputs")
    void assignmentIsDeterministic() {
        List<ReferralMatcher.PendingRequest> requests = IntStream.range(0, 20)
                .mapToObj(i -> request(i, "Payments", "SENIOR", Set.of("java"))).toList();
        List<ReferralMatcher.EligibleReferrer> referrers = List.of(
                referrer("Payments", "STAFF", Set.of("java"), 0.9, 4),
                referrer("Payments", "SENIOR", Set.of("java"), 0.8, 4));

        assertThat(ReferralMatcher.assign(requests, referrers, WEIGHTS))
                .isEqualTo(ReferralMatcher.assign(requests, referrers, WEIGHTS));
    }

    @Test
    @DisplayName("stack overlap is case and whitespace insensitive")
    void stackComparisonIsNormalized() {
        assertThat(ReferralMatcher.jaccard(Set.of("Java", " Kafka "), Set.of("java", "kafka")))
                .isEqualTo(1.0);
        assertThat(ReferralMatcher.jaccard(Set.of(), Set.of("java"))).isZero();
    }
}
