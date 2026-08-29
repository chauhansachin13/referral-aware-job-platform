package com.referralhub.referral.api;

import com.referralhub.referral.ReferralRequest;
import com.referralhub.referral.ReferralRequestStore;
import com.referralhub.referral.ReferralService;
import com.referralhub.referral.match.ReferralMatcher;
import com.referralhub.referral.match.ReferralMatchingService;
import com.referralhub.referral.resume.ResumeStorage;
import com.referralhub.referral.resume.StoredResume;
import com.referralhub.referral.state.ReferralState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/referrals")
public class ReferralController {

    private final ReferralService referrals;
    private final ReferralRequestStore store;
    private final ResumeStorage resumes;
    private final ReferralMatchingService matching;

    public ReferralController(ReferralService referrals,
                              ReferralRequestStore store,
                              ResumeStorage resumes,
                              ReferralMatchingService matching) {
        this.referrals = referrals;
        this.store = store;
        this.resumes = resumes;
        this.matching = matching;
    }

    public record CreateRequest(@NotNull UUID seekerId, @NotNull UUID canonicalJobId,
                                @NotNull UUID companyId, UUID resumeId,
                                @Size(max = 2000) String message) {
    }

    public record ActorRequest(@NotNull UUID actorId, @Size(max = 500) String reason) {
    }

    public record RequestView(UUID id, UUID seekerId, UUID referrerId, UUID canonicalJobId,
                              ReferralState state, String message, Instant createdAt,
                              Instant expiresAt, boolean resumeAttached) {

        static RequestView of(ReferralRequest request) {
            return new RequestView(request.id(), request.seekerId(), request.referrerId(),
                    request.canonicalJobId(), request.state(), request.message(),
                    request.createdAt(), request.expiresAt(), request.resumeId() != null);
        }
    }

    public record DownloadLink(String url) {
    }

    // --------------------------------------------------------------------------------
    // Lifecycle
    // --------------------------------------------------------------------------------

    /**
     * The {@code Idempotency-Key} header is optional but strongly recommended: without it, a
     * client that retries after a timeout creates a second request.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RequestView create(@Valid @RequestBody CreateRequest body,
                              @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return RequestView.of(referrals.request(body.seekerId(), body.canonicalJobId(),
                body.companyId(), body.resumeId(), body.message(), key));
    }

    @PostMapping("/{requestId}/accept")
    public RequestView accept(@PathVariable UUID requestId, @Valid @RequestBody ActorRequest body,
                              @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return RequestView.of(referrals.accept(requestId, body.actorId(), key));
    }

    @PostMapping("/{requestId}/decline")
    public RequestView decline(@PathVariable UUID requestId, @Valid @RequestBody ActorRequest body,
                               @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return RequestView.of(referrals.decline(requestId, body.actorId(), body.reason(), key));
    }

    @PostMapping("/{requestId}/submit")
    public RequestView submit(@PathVariable UUID requestId, @Valid @RequestBody ActorRequest body,
                              @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return RequestView.of(referrals.submit(requestId, body.actorId(), key));
    }

    @PostMapping("/{requestId}/close")
    public RequestView close(@PathVariable UUID requestId, @Valid @RequestBody ActorRequest body,
                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return RequestView.of(referrals.close(requestId, body.actorId(), body.reason(), key));
    }

    @GetMapping("/{requestId}")
    public RequestView get(@PathVariable UUID requestId) {
        return RequestView.of(referrals.load(requestId));
    }

    @GetMapping("/{requestId}/audit")
    public List<ReferralRequestStore.TransitionRecord> audit(@PathVariable UUID requestId) {
        return store.auditTrail(requestId);
    }

    @GetMapping("/seekers/{seekerId}")
    public List<RequestView> bySeeker(@PathVariable UUID seekerId,
                                      @RequestParam(defaultValue = "50") int limit) {
        return store.findBySeeker(seekerId, Math.min(limit, 200)).stream()
                .map(RequestView::of).toList();
    }

    // --------------------------------------------------------------------------------
    // Resumes
    // --------------------------------------------------------------------------------

    @PostMapping(value = "/resumes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public StoredResume upload(@RequestParam UUID ownerId,
                               @RequestPart("file") MultipartFile file) throws IOException {
        return resumes.store(ownerId,
                file.getOriginalFilename() == null ? "resume.pdf" : file.getOriginalFilename(),
                file.getContentType() == null ? "application/pdf" : file.getContentType(),
                file.getBytes());
    }

    @PostMapping("/{requestId}/resume-link")
    public DownloadLink resumeLink(@PathVariable UUID requestId, @RequestParam UUID referrerId) {
        return new DownloadLink(referrals.mintResumeDownloadUrl(requestId, referrerId));
    }

    @GetMapping("/resume")
    public ResponseEntity<ByteArrayResource> download(@RequestParam String token) {
        ReferralService.ResumePayload payload = referrals.readResume(token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(payload.filename()).build().toString())
                // Never let a proxy or browser keep a copy of someone's resume.
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .body(new ByteArrayResource(payload.bytes()));
    }

    /** The erasure path. Hard delete: the object and the row both go. */
    @DeleteMapping("/resumes/{resumeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteResume(@PathVariable UUID resumeId) {
        resumes.hardDelete(resumeId);
    }

    @DeleteMapping("/users/{ownerId}/resumes")
    public DeletionReceipt deleteAllResumes(@PathVariable UUID ownerId) {
        return new DeletionReceipt(resumes.hardDeleteAllFor(ownerId), Instant.now());
    }

    public record DeletionReceipt(int deleted, Instant at) {
    }

    // --------------------------------------------------------------------------------
    // Matching
    // --------------------------------------------------------------------------------

    @GetMapping("/jobs/{canonicalJobId}/proposed-assignments")
    public List<ReferralMatcher.Assignment> proposedAssignments(@PathVariable UUID canonicalJobId) {
        return matching.proposeAssignments(canonicalJobId);
    }
}
