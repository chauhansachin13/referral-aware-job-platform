package com.referralhub.referral.match;

import com.referralhub.dedup.canonical.CanonicalJob;
import com.referralhub.dedup.canonical.CanonicalJobStore;
import com.referralhub.common.error.NotFoundException;
import com.referralhub.referral.ReferralRequest;
import com.referralhub.referral.ReferralRequestStore;
import com.referralhub.trust.capacity.ReferrerCapacity;
import com.referralhub.trust.reputation.ReputationScore;
import com.referralhub.trust.verify.EmployeeVerification;
import com.referralhub.trust.verify.VerificationStore;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feeds the database into {@link ReferralMatcher} and nothing more.
 *
 * <p>All the judgement lives in the pure matcher; this class only gathers inputs. That split is
 * what lets the interesting cases — 400 requests against 3 referrers, capacity exhaustion,
 * fairness under skewed affinity — be tested in microseconds without any infrastructure.
 */
@Service
public class ReferralMatchingService {

    private final ReferralRequestStore requests;
    private final CanonicalJobStore canonicalJobs;
    private final VerificationStore trustStore;
    private final ReferrerCapacity capacity;

    public ReferralMatchingService(ReferralRequestStore requests,
                                   CanonicalJobStore canonicalJobs,
                                   VerificationStore trustStore,
                                   ReferrerCapacity capacity) {
        this.requests = requests;
        this.canonicalJobs = canonicalJobs;
        this.trustStore = trustStore;
        this.capacity = capacity;
    }

    @Transactional(readOnly = true)
    public List<ReferralMatcher.Assignment> proposeAssignments(UUID canonicalJobId) {
        CanonicalJob job = canonicalJobs.findById(canonicalJobId)
                .orElseThrow(() -> new NotFoundException("Canonical job", canonicalJobId));

        Set<String> jobStack = stackOf(job.specialization() + " " + job.descriptionHtml());

        List<ReferralMatcher.PendingRequest> pending = requests.findPendingForJob(canonicalJobId)
                .stream()
                .map(request -> toPendingRequest(request, job, jobStack))
                .toList();

        List<ReferralMatcher.EligibleReferrer> eligible =
                trustStore.activeReferrersFor(job.companyId()).stream()
                        .map(this::toEligibleReferrer)
                        .filter(referrer -> referrer.remainingCapacity() > 0)
                        .toList();

        return ReferralMatcher.assign(pending, eligible, MatchingWeights.defaults());
    }

    private ReferralMatcher.PendingRequest toPendingRequest(ReferralRequest request,
                                                            CanonicalJob job,
                                                            Set<String> jobStack) {
        return new ReferralMatcher.PendingRequest(
                request.id(),
                request.seekerId(),
                request.canonicalJobId(),
                job.specialization(),
                job.canonicalLevel(),
                jobStack,
                request.createdAt());
    }

    private ReferralMatcher.EligibleReferrer toEligibleReferrer(EmployeeVerification verification) {
        ReputationScore.Counters counters = trustStore.countersFor(verification.userId());
        return new ReferralMatcher.EligibleReferrer(
                verification.userId(),
                // Department and stack for a referrer would come from their profile; until that
                // exists they contribute nothing rather than contributing a wrong guess.
                "",
                "",
                Set.of(),
                ReputationScore.of(counters),
                capacity.remaining(verification.userId()));
    }

    /** Extracts recognisable technologies from free text. */
    static Set<String> stackOf(String text) {
        Set<String> known = Set.of("java", "kotlin", "go", "golang", "python", "typescript",
                "javascript", "react", "kubernetes", "k8s", "docker", "terraform", "aws", "gcp",
                "azure", "kafka", "postgres", "postgresql", "redis", "spark", "flink", "opensearch",
                "elasticsearch", "grpc", "graphql", "rust", "scala", "swift", "pytorch");
        String normalized = com.referralhub.common.text.TextNormalizer.canonical(text);
        return known.stream().filter(tech -> normalized.contains(tech))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
