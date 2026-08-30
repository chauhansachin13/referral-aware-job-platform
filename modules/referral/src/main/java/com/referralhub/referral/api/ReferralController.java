package com.referralhub.referral.api;

import com.referralhub.common.error.ConflictException;
import com.referralhub.referral.ReferralRequest;
import com.referralhub.referral.ReferralRequestStore;
import com.referralhub.referral.ReferralService;
import com.referralhub.referral.match.ReferralMatcher;
import com.referralhub.referral.match.ReferralMatchingService;
import com.referralhub.referral.resume.ResumeStorage;
import com.referralhub.referral.resume.StoredResume;
import com.referralhub.referral.state.ReferralState;
import com.referralhub.trust.auth.CurrentUser;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

/**
 * Every acting identity on this controller comes from the bearer token.
 *
 * <p>Before authentication existed these endpoints took the actor as a request parameter and
 * believed it, so any caller could accept another person's referral or mint a resume link for a
 * request that was not theirs. No endpoint here accepts an actor id any more; passing one would
 * be an invitation to trust it.
 */
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

    public record CreateRequest(@NotNull UUID canonicalJobId, @NotNull UUID companyId,
                                UUID resumeId, @Size(max = 2000) String message) {
    }

    public record ReasonRequest(@Size(max = 500) String reason) {
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RequestView create(@Valid @RequestBody CreateRequest body,
                              @AuthenticationPrincipal Jwt jwt,
                              @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return RequestView.of(referrals.request(CurrentUser.idOf(jwt), body.canonicalJobId(),
                body.companyId(), body.resumeId(), body.message(), key));
    }

    @PostMapping("/{requestId}/accept")
    public RequestView accept(@PathVariable UUID requestId, @AuthenticationPrincipal Jwt jwt,
                              @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return RequestView.of(referrals.accept(requestId, CurrentUser.idOf(jwt), key));
    }

    @PostMapping("/{requestId}/decline")
    public RequestView decline(@PathVariable UUID requestId,
                               @RequestBody(required = false) ReasonRequest body,
                               @AuthenticationPrincipal Jwt jwt,
                               @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return RequestView.of(referrals.decline(requestId, CurrentUser.idOf(jwt),
                body == null ? null : body.reason(), key));
    }

    @PostMapping("/{requestId}/submit")
    public RequestView submit(@PathVariable UUID requestId, @AuthenticationPrincipal Jwt jwt,
                              @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return RequestView.of(referrals.submit(requestId, CurrentUser.idOf(jwt), key));
    }

    @PostMapping("/{requestId}/close")
    public RequestView close(@PathVariable UUID requestId,
                             @RequestBody(required = false) ReasonRequest body,
                             @AuthenticationPrincipal Jwt jwt,
                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return RequestView.of(referrals.close(requestId, CurrentUser.idOf(jwt),
                body == null ? null : body.reason(), key));
    }

    /** Readable by the two parties to the referral, and by nobody else. */
    @GetMapping("/{requestId}")
    public RequestView get(@PathVariable UUID requestId, @AuthenticationPrincipal Jwt jwt) {
        return RequestView.of(requireParticipant(requestId, CurrentUser.idOf(jwt)));
    }

    @GetMapping("/{requestId}/audit")
    public List<ReferralRequestStore.TransitionRecord> audit(@PathVariable UUID requestId,
                                                             @AuthenticationPrincipal Jwt jwt) {
        requireParticipant(requestId, CurrentUser.idOf(jwt));
        return store.auditTrail(requestId);
    }

    /** Your own requests. There is no endpoint for reading somebody else's. */
    @GetMapping("/mine")
    public List<RequestView> mine(@AuthenticationPrincipal Jwt jwt,
                                  @RequestParam(defaultValue = "50") int limit) {
        return store.findBySeeker(CurrentUser.idOf(jwt), Math.min(limit, 200)).stream()
                .map(RequestView::of).toList();
    }

    // --------------------------------------------------------------------------------
    // Resumes
    // --------------------------------------------------------------------------------

    @PostMapping(value = "/resumes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public StoredResume upload(@RequestPart("file") MultipartFile file,
                               @AuthenticationPrincipal Jwt jwt) throws IOException {
        return resumes.store(CurrentUser.idOf(jwt),
                file.getOriginalFilename() == null ? "resume.pdf" : file.getOriginalFilename(),
                file.getContentType() == null ? "application/pdf" : file.getContentType(),
                file.getBytes());
    }

    @PostMapping("/{requestId}/resume-link")
    public DownloadLink resumeLink(@PathVariable UUID requestId, @AuthenticationPrincipal Jwt jwt) {
        return new DownloadLink(referrals.mintResumeDownloadUrl(requestId, CurrentUser.idOf(jwt)));
    }

    /**
     * Redeems a download token.
     *
     * <p>The token is the credential here, which is why this is the one endpoint that does not
     * read the principal: it is signed, short-lived, bound to one referral, and re-checked
     * against that referral's current state on every redemption.
     */
    @GetMapping("/resume")
    public ResponseEntity<ByteArrayResource> download(@RequestParam String token) {
        ReferralService.ResumePayload payload = referrals.readResume(token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(payload.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .body(new ByteArrayResource(payload.bytes()));
    }

    @DeleteMapping("/resumes/{resumeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteResume(@PathVariable UUID resumeId, @AuthenticationPrincipal Jwt jwt) {
        UUID caller = CurrentUser.idOf(jwt);
        resumes.findMetadata(resumeId)
                .filter(resume -> resume.ownerId().equals(caller) || CurrentUser.isAdmin())
                .orElseThrow(() -> new ConflictException("That resume belongs to someone else"));
        resumes.hardDelete(resumeId);
    }

    /** The erasure path for your own account. */
    @DeleteMapping("/resumes")
    public DeletionReceipt deleteMyResumes(@AuthenticationPrincipal Jwt jwt) {
        return new DeletionReceipt(resumes.hardDeleteAllFor(CurrentUser.idOf(jwt)), Instant.now());
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

    private ReferralRequest requireParticipant(UUID requestId, UUID caller) {
        ReferralRequest request = referrals.load(requestId);
        boolean allowed = caller.equals(request.seekerId())
                || caller.equals(request.referrerId())
                || CurrentUser.isAdmin();
        if (!allowed) {
            // Deliberately the same shape as any other refusal: a distinct "exists but not
            // yours" would let a caller enumerate referral ids.
            throw new ConflictException("That referral request is not yours");
        }
        return request;
    }
}
