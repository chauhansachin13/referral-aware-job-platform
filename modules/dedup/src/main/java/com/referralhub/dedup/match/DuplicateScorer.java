package com.referralhub.dedup.match;

import com.referralhub.common.text.Shingles;
import com.referralhub.common.text.Tokens;
import com.referralhub.dedup.config.DedupProperties;
import com.referralhub.dedup.title.CanonicalTitle;
import com.referralhub.dedup.title.SeniorityLevel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The exact scoring pass that runs over LSH candidates.
 *
 * <p>LSH answers "might these be the same?" cheaply and with false positives by design. This
 * answers "are they?" expensively and precisely, over the handful of candidates LSH returned
 * rather than the whole corpus. Splitting the two is what makes the module both fast and
 * accurate; either technique alone is one or the other.
 */
@Component
public class DuplicateScorer {

    private final DedupProperties properties;

    public DuplicateScorer(DedupProperties properties) {
        this.properties = properties;
        double sum = properties.getWeights().sum();
        if (Math.abs(sum - 1.0) > 1e-6) {
            throw new IllegalStateException(
                    "referralhub.dedup.weights must sum to 1.0 but sum to " + sum);
        }
    }

    public MatchScore score(JobFingerprint left, JobFingerprint right) {
        // Exact Jaccard, not the MinHash estimate: at this point there are a few dozen
        // candidates, and the estimate's variance is the difference between a correct merge
        // and a wrong one.
        double jaccard = Shingles.jaccard(left.shingles(), right.shingles());
        double title = titleScore(left.title(), right.title());
        double company = left.companyId().equals(right.companyId()) ? 1.0 : 0.0;
        double location = locationScore(left, right);

        DedupProperties.Weights weights = properties.getWeights();
        double base = weights.getJaccard() * jaccard
                + weights.getTitle() * title
                + weights.getCompany() * company
                + weights.getLocation() * location;

        // Gates, applied multiplicatively. Some of these facts are not "a bit less similar",
        // they are "not the same job", and a weighted sum cannot say that: to make a different
        // company outweigh a 0.97 Jaccard and an identical title, its weight would have to be
        // so large that nothing else in the formula could move the result.
        double gate = companyGate(company)
                * levelGate(left.title().level(), right.title().level())
                * locationGate(left, right, location);

        return new MatchScore(base * gate, jaccard, title, company, location);
    }

    /** Two companies are never advertising the same requisition. */
    static double companyGate(double companyScore) {
        return companyScore >= 1.0 ? 1.0 : 0.0;
    }

    /**
     * Ladder compatibility.
     *
     * <p>An unstated level is missing information, not evidence of a mismatch — most postings
     * never name a level, and treating that as disagreement would suppress most real merges.
     * A manager posting and an IC posting on the same team, on the other hand, are different
     * jobs no matter how much boilerplate they share.
     */
    static double levelGate(SeniorityLevel left, SeniorityLevel right) {
        if (left == SeniorityLevel.UNSPECIFIED || right == SeniorityLevel.UNSPECIFIED) {
            return 1.0;
        }
        if (left.isIndividualContributor() != right.isIndividualContributor()) {
            return 0.0;
        }
        return switch (left.distance(right)) {
            case 0 -> 1.0;
            case 1 -> 0.85;
            case 2 -> 0.35;
            default -> 0.0;
        };
    }

    /**
     * Location compatibility.
     *
     * <p>A remote listing and an on-site listing with the same title are two requisitions with
     * different hiring plans, which is why that case is a hard zero rather than a penalty.
     */
    static double locationGate(JobFingerprint left, JobFingerprint right, double locationScore) {
        if (left.remote() != right.remote()) {
            return 0.0;
        }
        if (locationScore >= 0.8) {
            return 1.0;
        }
        if (left.location().isBlank() || right.location().isBlank()) {
            return 0.9;
        }
        if (locationScore >= 0.4) {
            return 0.85;
        }
        return 0.25;
    }

    /**
     * Role family, ladder position and specialization, weighted in that order.
     *
     * <p>Specialization matters more than it looks: "Software Engineer, Payments" and "Software
     * Engineer, Search" normalize to the same role and level, share most of their company
     * boilerplate, and are emphatically not the same job.
     */
    static double titleScore(CanonicalTitle left, CanonicalTitle right) {
        double role = left.role().equals(right.role())
                ? 1.0
                : Shingles.jaccard(tokenSet(left.role()), tokenSet(right.role()));

        int levelDistance = left.level().distance(right.level());
        double level = 1.0 - Math.min(levelDistance, 3) / 3.0;

        boolean bothUnspecialized = left.specialization().isBlank() && right.specialization().isBlank();
        double specialization = bothUnspecialized
                ? 1.0
                : Shingles.jaccard(tokenSet(left.specialization()), tokenSet(right.specialization()));

        return 0.5 * role + 0.3 * level + 0.2 * specialization;
    }

    /**
     * Location similarity that tolerates the many ways one office is written down.
     *
     * <p>Two blank locations score 0.5, not 0: an unstated location is missing information, and
     * treating missing information as a mismatch would suppress genuine cross-board duplicates
     * where only one source publishes a location.
     */
    static double locationScore(JobFingerprint left, JobFingerprint right) {
        if (left.remote() && right.remote()) {
            return 1.0;
        }
        String a = left.location();
        String b = right.location();
        if (a.isBlank() && b.isBlank()) {
            return 0.5;
        }
        if (a.isBlank() || b.isBlank()) {
            return 0.3;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        // "san francisco" vs "san francisco ca united states"
        if (a.contains(b) || b.contains(a)) {
            return 0.8;
        }
        Set<String> left1 = tokenSet(a);
        Set<String> right1 = tokenSet(b);
        Set<String> shared = new HashSet<>(left1);
        shared.retainAll(right1);
        if (!shared.isEmpty()) {
            return 0.4 + 0.4 * Shingles.jaccard(left1, right1);
        }
        // A remote posting and an on-site one in a named city are not the same job.
        return left.remote() != right.remote() ? 0.0 : 0.1;
    }

    private static Set<String> tokenSet(String text) {
        List<String> tokens = Tokens.of(text);
        return tokens.isEmpty() ? Set.of() : new HashSet<>(tokens);
    }
}
