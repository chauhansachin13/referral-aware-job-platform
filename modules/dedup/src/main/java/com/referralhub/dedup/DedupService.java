package com.referralhub.dedup;

import com.referralhub.common.error.NotFoundException;
import com.referralhub.common.events.JobCanonicalized;
import com.referralhub.common.outbox.TransactionalEventPublisher;
import com.referralhub.dedup.canonical.CandidateRow;
import com.referralhub.dedup.canonical.CanonicalJobStore;
import com.referralhub.dedup.config.DedupProperties;
import com.referralhub.dedup.match.DuplicateScorer;
import com.referralhub.dedup.match.JobFingerprint;
import com.referralhub.dedup.match.MatchScore;
import com.referralhub.dedup.minhash.LshBanding;
import com.referralhub.dedup.minhash.MinHasher;
import com.referralhub.ingestion.raw.RawPostingRecord;
import com.referralhub.ingestion.raw.RawPostingStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attaches every ingested posting to a canonical job, creating one if it is genuinely new.
 *
 * <p>Two stages, for two different reasons:
 * <ol>
 *   <li><b>Retrieval</b> via LSH bands. Cheap, indexed, deliberately over-inclusive. It answers
 *       "which of this company's jobs could plausibly be this one" without touching the other
 *       199,900 rows in the corpus.</li>
 *   <li><b>Scoring</b> over a bounded prefix of those candidates, using exact Jaccard plus
 *       title, company and location agreement. Expensive per pair, but the pair count is now a
 *       couple of dozen rather than the corpus size.</li>
 * </ol>
 */
@Service
public class DedupService {

    private static final Logger log = LoggerFactory.getLogger(DedupService.class);

    private final RawPostingStore rawPostings;
    private final CanonicalJobStore canonicalJobs;
    private final DuplicateScorer scorer;
    private final MinHasher hasher;
    private final LshBanding banding;
    private final DedupProperties properties;
    private final TransactionalEventPublisher events;
    private final Counter mergedCounter;
    private final Counter createdCounter;

    public DedupService(RawPostingStore rawPostings,
                        CanonicalJobStore canonicalJobs,
                        DuplicateScorer scorer,
                        MinHasher hasher,
                        LshBanding banding,
                        DedupProperties properties,
                        TransactionalEventPublisher events,
                        MeterRegistry meters) {
        this.rawPostings = rawPostings;
        this.canonicalJobs = canonicalJobs;
        this.scorer = scorer;
        this.hasher = hasher;
        this.banding = banding;
        this.properties = properties;
        this.events = events;
        this.mergedCounter = meters.counter("referralhub.dedup.decision", "outcome", "merged");
        this.createdCounter = meters.counter("referralhub.dedup.decision", "outcome", "created");
    }

    @Transactional
    public DedupDecision canonicalize(UUID rawPostingId) {
        RawPostingRecord posting = rawPostings.findById(rawPostingId)
                .orElseThrow(() -> new NotFoundException("Raw posting", rawPostingId));

        JobFingerprint fingerprint = fingerprintOf(posting);
        long[] bands = banding.bandHashes(fingerprint.signature());

        List<CandidateRow> candidates = canonicalJobs.findCandidates(
                posting.companyId(), bands, properties.getMaxCandidates());

        // Rank before scoring: matched bands first (already ordered by the query), then the
        // MinHash estimate, which costs one pass over two int arrays already in memory.
        List<CandidateRow> shortlist = candidates.stream()
                .sorted(Comparator
                        .comparingInt(CandidateRow::matchedBands).reversed()
                        .thenComparing(candidate -> -MinHasher.estimateJaccard(
                                fingerprint.signature(), candidate.signature())))
                .limit(properties.getExactScoreLimit())
                .toList();

        CandidateRow best = null;
        MatchScore bestScore = MatchScore.zero();
        for (CandidateRow candidate : shortlist) {
            MatchScore score = scorer.score(fingerprint, fingerprintOf(candidate));
            if (score.total() > bestScore.total()) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best != null && bestScore.isDuplicate(properties.getMatchThreshold())) {
            canonicalJobs.attachSource(best.id(), posting.id(), posting.source(),
                    posting.externalId(), posting.applyUrl(), bestScore.total());
            mergedCounter.increment();
            events.publish(new JobCanonicalized(best.id(), posting.id(), false,
                    bestScore.total(), Instant.now()));

            log.debug("Posting {} merged into canonical {} at {} (jaccard {}, title {})",
                    posting.externalId(), best.id(), bestScore.total(),
                    bestScore.jaccard(), bestScore.titleScore());

            return new DedupDecision(best.id(), posting.id(), false, bestScore.total(),
                    candidates.size(), shortlist.size());
        }

        UUID canonicalId = canonicalJobs.create(fingerprint, posting.descriptionHtml());
        canonicalJobs.indexBands(canonicalId, posting.companyId(), bands);
        canonicalJobs.attachSource(canonicalId, posting.id(), posting.source(),
                posting.externalId(), posting.applyUrl(), 1.0);
        createdCounter.increment();
        events.publish(new JobCanonicalized(canonicalId, posting.id(), true, 1.0, Instant.now()));

        return new DedupDecision(canonicalId, posting.id(), true, 1.0,
                candidates.size(), shortlist.size());
    }

    private JobFingerprint fingerprintOf(RawPostingRecord posting) {
        return JobFingerprint.of(posting.id(), posting.companyId(), posting.title(),
                posting.descriptionHtml(), posting.location(), posting.remote(),
                hasher, properties.getShingleSize());
    }

    private JobFingerprint fingerprintOf(CandidateRow candidate) {
        return JobFingerprint.of(candidate.id(), candidate.companyId(), candidate.title(),
                candidate.descriptionHtml(), candidate.location(), candidate.remote(),
                hasher, properties.getShingleSize());
    }
}
