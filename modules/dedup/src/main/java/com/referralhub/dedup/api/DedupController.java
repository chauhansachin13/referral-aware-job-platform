package com.referralhub.dedup.api;

import com.referralhub.common.error.NotFoundException;
import com.referralhub.dedup.DedupDecision;
import com.referralhub.dedup.DedupService;
import com.referralhub.dedup.canonical.CanonicalJob;
import com.referralhub.dedup.canonical.CanonicalJobStore;
import com.referralhub.dedup.minhash.LshBanding;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dedup")
public class DedupController {

    private final DedupService dedupService;
    private final CanonicalJobStore store;
    private final LshBanding banding;

    public DedupController(DedupService dedupService, CanonicalJobStore store, LshBanding banding) {
        this.dedupService = dedupService;
        this.store = store;
        this.banding = banding;
    }

    @GetMapping("/canonical/{id}")
    public CanonicalJobView get(@PathVariable UUID id) {
        return store.findById(id).map(CanonicalJobView::of)
                .orElseThrow(() -> new NotFoundException("Canonical job", id));
    }

    @GetMapping("/companies/{companyId}/canonical")
    public List<CanonicalJobView> byCompany(@PathVariable UUID companyId,
                                            @RequestParam(defaultValue = "50") int limit) {
        return store.findByCompany(companyId, Math.min(limit, 500)).stream()
                .map(CanonicalJobView::of).toList();
    }

    /** Re-runs canonicalization for one posting; used to replay after a threshold change. */
    @PostMapping("/postings/{rawPostingId}/canonicalize")
    public DedupDecision canonicalize(@PathVariable UUID rawPostingId) {
        return dedupService.canonicalize(rawPostingId);
    }

    /** Exposes the retrieval curve so the banding choice can be inspected, not guessed at. */
    @GetMapping("/banding")
    public BandingView banding() {
        return new BandingView(
                banding.bands(),
                banding.rowsPerBand(),
                banding.similarityThreshold(),
                List.of(0.5, 0.6, 0.7, 0.8, 0.9, 0.95).stream()
                        .map(s -> new BandingView.Point(s, banding.retrievalProbability(s)))
                        .toList());
    }

    public record CanonicalJobView(UUID id, UUID companyId, String title, String role,
                                   String level, String specialization, String location,
                                   boolean remote, int sourceCount) {

        static CanonicalJobView of(CanonicalJob job) {
            return new CanonicalJobView(job.id(), job.companyId(), job.title(), job.canonicalRole(),
                    job.canonicalLevel(), job.specialization(), job.location(), job.remote(),
                    job.sourceCount());
        }
    }

    public record BandingView(int bands, int rowsPerBand, double similarityThreshold,
                              List<Point> retrievalCurve) {

        public record Point(double jaccard, double retrievalProbability) {
        }
    }
}
