package com.referralhub.referral.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The matcher's safety properties, over arbitrary marketplaces.
 *
 * <p>The example-based test proves 400 requests against 3 referrers respects capacity. These
 * prove it for *any* combination of queue length, pool size, capacities and affinities — which
 * matters because capacity breach is the failure mode that silently over-commits a real person,
 * and it would only show up under an input shape nobody wrote a test for.
 */
class ReferralMatcherProperties {

    private static final MatchingWeights WEIGHTS = MatchingWeights.defaults();
    private static final Instant BASE = Instant.parse("2026-08-01T00:00:00Z");
    private static final List<String> DEPARTMENTS = List.of("Payments", "Search", "Infra", "");
    private static final List<String> LEVELS =
            List.of("INTERN", "ENTRY", "MID", "SENIOR", "STAFF", "PRINCIPAL", "MANAGER", "");
    private static final List<String> TECH = List.of("java", "go", "kubernetes", "kafka", "react");

    @Provide
    Arbitrary<ReferralMatcher.PendingRequest> requests() {
        return Combinators.combine(
                        Arbitraries.integers().between(0, 5_000),
                        Arbitraries.of(DEPARTMENTS),
                        Arbitraries.of(LEVELS),
                        Arbitraries.of(TECH).set().ofMinSize(0).ofMaxSize(3))
                .as((offset, department, level, stack) -> new ReferralMatcher.PendingRequest(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        department, level, stack, BASE.plusSeconds(offset)));
    }

    @Provide
    Arbitrary<ReferralMatcher.EligibleReferrer> referrers() {
        return Combinators.combine(
                        Arbitraries.of(DEPARTMENTS),
                        Arbitraries.of(LEVELS),
                        Arbitraries.of(TECH).set().ofMinSize(0).ofMaxSize(3),
                        Arbitraries.doubles().between(0.0, 1.0),
                        // Includes zero and negative capacity: both must be treated as "full".
                        Arbitraries.integers().between(-2, 8))
                .as((department, level, stack, reputation, capacity) ->
                        new ReferralMatcher.EligibleReferrer(UUID.randomUUID(), department, level,
                                stack, reputation, capacity));
    }

    /** A List parameter needs a provider of Arbitrary<List<..>>, not of the element type. */
    @Provide
    Arbitrary<List<ReferralMatcher.PendingRequest>> requestQueues() {
        return requests().list().ofMaxSize(60);
    }

    @Provide
    Arbitrary<List<ReferralMatcher.EligibleReferrer>> referrerPools() {
        return referrers().list().ofMaxSize(8);
    }

    @Property(tries = 300)
    void noReferrerIsEverAssignedBeyondCapacity(
            @ForAll("requestQueues") List<ReferralMatcher.PendingRequest> requests,
            @ForAll("referrerPools") List<ReferralMatcher.EligibleReferrer> referrers) {

        List<ReferralMatcher.Assignment> assignments =
                ReferralMatcher.assign(requests, referrers, WEIGHTS);

        Map<UUID, Integer> assigned = new HashMap<>();
        assignments.forEach(a -> assigned.merge(a.referrerId(), 1, Integer::sum));

        for (ReferralMatcher.EligibleReferrer referrer : referrers) {
            assertThat(assigned.getOrDefault(referrer.id(), 0))
                    .isLessThanOrEqualTo(Math.max(0, referrer.remainingCapacity()));
        }
    }

    @Property(tries = 300)
    void everyRequestIsPlacedAtMostOnce(
            @ForAll("requestQueues") List<ReferralMatcher.PendingRequest> requests,
            @ForAll("referrerPools") List<ReferralMatcher.EligibleReferrer> referrers) {

        List<ReferralMatcher.Assignment> assignments =
                ReferralMatcher.assign(requests, referrers, WEIGHTS);

        Set<UUID> placed = new HashSet<>();
        assignments.forEach(a -> assertThat(placed.add(a.requestId()))
                .as("request %s was assigned twice", a.requestId()).isTrue());

        assertThat(assignments.size()).isLessThanOrEqualTo(requests.size());
    }

    @Property(tries = 300)
    void assignmentsOnlyReferToInputs(
            @ForAll("requestQueues") List<ReferralMatcher.PendingRequest> requests,
            @ForAll("referrerPools") List<ReferralMatcher.EligibleReferrer> referrers) {

        Set<UUID> requestIds = new HashSet<>();
        requests.forEach(r -> requestIds.add(r.id()));
        Set<UUID> referrerIds = new HashSet<>();
        referrers.forEach(r -> referrerIds.add(r.id()));

        for (ReferralMatcher.Assignment assignment :
                ReferralMatcher.assign(requests, referrers, WEIGHTS)) {
            assertThat(requestIds).contains(assignment.requestId());
            assertThat(referrerIds).contains(assignment.referrerId());
        }
    }

    @Property(tries = 300)
    void affinityIsAlwaysABoundedScore(
            @ForAll("requests") ReferralMatcher.PendingRequest request,
            @ForAll("referrers") ReferralMatcher.EligibleReferrer referrer) {

        // Affinity feeds a comparison; a NaN or an out-of-range value would silently corrupt the
        // ordering rather than failing.
        double affinity = ReferralMatcher.affinity(request, referrer, WEIGHTS);

        assertThat(affinity).isBetween(0.0, 1.0);
        assertThat(Double.isFinite(affinity)).isTrue();
    }

    @Property(tries = 200)
    void totalPlacementsNeverExceedTotalCapacity(
            @ForAll("requestQueues") List<ReferralMatcher.PendingRequest> requests,
            @ForAll("referrerPools") List<ReferralMatcher.EligibleReferrer> referrers) {

        int totalCapacity = referrers.stream()
                .mapToInt(r -> Math.max(0, r.remainingCapacity())).sum();

        assertThat(ReferralMatcher.assign(requests, referrers, WEIGHTS).size())
                .isLessThanOrEqualTo(Math.min(totalCapacity, requests.size()));
    }
}
