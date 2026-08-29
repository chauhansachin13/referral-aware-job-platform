package com.referralhub.referral.match;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Assigns pending referral requests to verified referrers.
 *
 * <p>A pure function over its inputs — no repository, no clock, no Spring. That is deliberate:
 * this is the piece of the marketplace whose behaviour is hardest to reason about and easiest to
 * get subtly wrong, and it needs to be testable with 400 requests and three referrers in a
 * millisecond rather than through a database fixture.
 *
 * <p>The interesting requirement is not "pick the best referrer". Pure greedy matching does that
 * and produces a system where the single most responsive person at each company receives every
 * request until they burn out and stop answering, at which point the platform has no supply at
 * all. So the score carries a fairness penalty proportional to how much of their capacity a
 * referrer has already been given in this round; a strong match still wins, but only until they
 * are carrying their share.
 *
 * <p>Capacity is a hard constraint, never a soft one. A referrer at zero remaining capacity is
 * not scored at all.
 */
public final class ReferralMatcher {

    private ReferralMatcher() {
    }

    public record PendingRequest(
            UUID id,
            UUID seekerId,
            UUID canonicalJobId,
            String department,
            String level,
            Set<String> stack,
            Instant requestedAt) {
    }

    public record EligibleReferrer(
            UUID id,
            String department,
            String level,
            Set<String> stack,
            double reputation,
            int remainingCapacity) {
    }

    public record Assignment(UUID requestId, UUID referrerId, double score, double affinity) {
    }

    /** Ladder order shared with the dedup module's seniority ladder. */
    private static final List<String> LADDER = List.of(
            "INTERN", "ENTRY", "MID", "SENIOR", "STAFF", "PRINCIPAL", "MANAGER", "DIRECTOR", "VP");

    /**
     * @return one assignment per request that could be placed; requests left unplaced (because
     *         every eligible referrer is full) are simply absent, and stay pending for the next
     *         round rather than being silently dropped or over-assigned
     */
    public static List<Assignment> assign(List<PendingRequest> requests,
                                          List<EligibleReferrer> referrers,
                                          MatchingWeights weights) {
        if (requests.isEmpty() || referrers.isEmpty()) {
            return List.of();
        }

        Map<UUID, Integer> remaining = new HashMap<>();
        Map<UUID, Integer> capacity = new HashMap<>();
        Map<UUID, Integer> assignedThisRound = new HashMap<>();
        for (EligibleReferrer referrer : referrers) {
            remaining.put(referrer.id(), Math.max(0, referrer.remainingCapacity()));
            capacity.put(referrer.id(), Math.max(1, referrer.remainingCapacity()));
            assignedThisRound.put(referrer.id(), 0);
        }

        // Oldest request first: someone who has been waiting three days should not keep losing
        // to whoever asked most recently.
        List<PendingRequest> ordered = new ArrayList<>(requests);
        ordered.sort(Comparator.comparing(PendingRequest::requestedAt)
                .thenComparing(PendingRequest::id));

        List<Assignment> assignments = new ArrayList<>();
        for (PendingRequest request : ordered) {
            EligibleReferrer best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            double bestAffinity = 0;

            for (EligibleReferrer referrer : referrers) {
                if (remaining.get(referrer.id()) <= 0) {
                    continue;
                }
                double affinity = affinity(request, referrer, weights);
                double load = (double) assignedThisRound.get(referrer.id())
                        / capacity.get(referrer.id());
                double score = affinity - weights.fairnessPenalty() * load;

                if (score > bestScore) {
                    bestScore = score;
                    bestAffinity = affinity;
                    best = referrer;
                }
            }

            if (best == null) {
                // Everyone is full. Leave it pending; the next round will have capacity again.
                continue;
            }
            remaining.merge(best.id(), -1, Integer::sum);
            assignedThisRound.merge(best.id(), 1, Integer::sum);
            assignments.add(new Assignment(request.id(), best.id(), bestScore, bestAffinity));
        }
        return assignments;
    }

    /** Weighted affinity in [0, 1] before any fairness adjustment. */
    static double affinity(PendingRequest request, EligibleReferrer referrer,
                           MatchingWeights weights) {
        double org = equalsIgnoreCase(request.department(), referrer.department()) ? 1.0 : 0.0;
        double stack = jaccard(request.stack(), referrer.stack());
        double seniority = seniorityFit(request.level(), referrer.level());
        double responsiveness = clamp(referrer.reputation());

        return (weights.org() * org
                + weights.stack() * stack
                + weights.seniority() * seniority
                + weights.responsiveness() * responsiveness) / weights.affinitySum();
    }

    /**
     * A referrer at or one rung above the role is ideal. Far above is worse, not better: a VP
     * referring a new grad carries less weight internally than the new grad's future teammate,
     * and costs a scarce senior person's time.
     */
    static double seniorityFit(String requestLevel, String referrerLevel) {
        int request = LADDER.indexOf(upper(requestLevel));
        int referrer = LADDER.indexOf(upper(referrerLevel));
        if (request < 0 || referrer < 0) {
            return 0.5;
        }
        int gap = referrer - request;
        if (gap < 0) {
            // Below the role: they may not even be able to refer for it.
            return Math.max(0.0, 0.5 + gap * 0.25);
        }
        return switch (gap) {
            case 0, 1 -> 1.0;
            case 2 -> 0.75;
            case 3 -> 0.5;
            default -> 0.25;
        };
    }

    static double jaccard(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(normalize(left));
        Set<String> union = new HashSet<>(normalize(left));
        Set<String> normalizedRight = normalize(right);
        intersection.retainAll(normalizedRight);
        union.addAll(normalizedRight);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static Set<String> normalize(Set<String> values) {
        Set<String> normalized = new HashSet<>(values.size());
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.toLowerCase(Locale.ROOT).trim());
            }
        }
        return normalized;
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && !left.isBlank() && left.equalsIgnoreCase(right);
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).trim();
    }

    private static double clamp(double value) {
        return Math.min(1.0, Math.max(0.0, value));
    }
}
