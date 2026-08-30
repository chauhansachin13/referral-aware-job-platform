package com.referralhub.referral;

import com.referralhub.common.error.ConflictException;
import com.referralhub.common.error.NotFoundException;
import com.referralhub.common.events.NotificationRequested;
import com.referralhub.common.events.ReferralLifecycleChanged;
import com.referralhub.common.ids.Ids;
import com.referralhub.common.outbox.TransactionalEventPublisher;
import com.referralhub.referral.config.StorageProperties;
import com.referralhub.referral.resume.ResumeAccessToken;
import com.referralhub.referral.resume.ResumeStorage;
import com.referralhub.referral.state.ReferralState;
import com.referralhub.trust.capacity.ReferrerCapacity;
import com.referralhub.trust.capacity.SeekerQuota;
import com.referralhub.trust.verify.VerificationService;
import com.referralhub.trust.verify.VerificationStore;
import com.referralhub.trust.config.TrustProperties;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The referral lifecycle, with every rule enforced in the domain layer.
 *
 * <p>Three things are true of every transition here and none of them is optional:
 * <ul>
 *   <li>the row is locked before it is read, so two referrers cannot both accept;</li>
 *   <li>the move is validated by {@link ReferralState}, so an illegal transition throws
 *       regardless of which caller attempted it;</li>
 *   <li>the transition, the audit row and the outbox event commit together or not at all.</li>
 * </ul>
 */
@Service
public class ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);

    private final ReferralRequestStore store;
    private final ResumeStorage resumes;
    private final SeekerQuota seekerQuota;
    private final ReferrerCapacity referrerCapacity;
    private final VerificationService verification;
    private final VerificationStore trustStore;
    private final TransactionalEventPublisher events;
    private final TrustProperties trustProperties;
    private final StorageProperties storageProperties;

    public ReferralService(ReferralRequestStore store,
                           ResumeStorage resumes,
                           SeekerQuota seekerQuota,
                           ReferrerCapacity referrerCapacity,
                           VerificationService verification,
                           VerificationStore trustStore,
                           TransactionalEventPublisher events,
                           TrustProperties trustProperties,
                           StorageProperties storageProperties) {
        this.store = store;
        this.resumes = resumes;
        this.seekerQuota = seekerQuota;
        this.referrerCapacity = referrerCapacity;
        this.verification = verification;
        this.trustStore = trustStore;
        this.events = events;
        this.trustProperties = trustProperties;
        this.storageProperties = storageProperties;
    }

    // ------------------------------------------------------------------------------------
    // Creation
    // ------------------------------------------------------------------------------------

    @Transactional
    public ReferralRequest request(UUID seekerId, UUID canonicalJobId, UUID companyId,
                                   UUID resumeId, String message, String idempotencyKey) {
        Optional<UUID> replay = replayedRequestId(idempotencyKey);
        if (replay.isPresent()) {
            return load(replay.get());
        }

        if (resumeId != null) {
            var resume = resumes.findMetadata(resumeId)
                    .orElseThrow(() -> new NotFoundException("Resume", resumeId));
            if (!resume.ownerId().equals(seekerId)) {
                // Attaching someone else's resume would be a straightforward PII leak.
                throw new ConflictException("That resume belongs to a different user");
            }
        }

        // Consumed before the insert so a burst of concurrent requests cannot all pass the check.
        seekerQuota.consume(seekerId);

        Instant expiresAt = Instant.now().plus(trustProperties.getRequestExpiry());
        UUID id;
        try {
            id = store.create(seekerId, canonicalJobId, companyId, resumeId,
                    message == null ? "" : message, expiresAt);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            seekerQuota.refund(seekerId);
            throw new ConflictException("You have already requested a referral for this job");
        }

        store.recordTransition(id, null, ReferralState.REQUESTED, "SEEKER", seekerId, null);
        trustStore.incrementCounter(seekerId, "requests_sent");

        events.publish(new ReferralLifecycleChanged(id, seekerId, canonicalJobId, null,
                null, ReferralState.REQUESTED.name(), "SEEKER", Instant.now()));

        store.recordIdempotency(idempotencyKey, id, "request", ReferralState.REQUESTED);
        return load(id);
    }

    // ------------------------------------------------------------------------------------
    // Referrer actions
    // ------------------------------------------------------------------------------------

    @Transactional
    public ReferralRequest accept(UUID requestId, UUID referrerId, String idempotencyKey) {
        Optional<UUID> replay = replayedRequestId(idempotencyKey);
        if (replay.isPresent()) {
            return load(replay.get());
        }

        ReferralRequest request = lock(requestId);
        requireVerifiedReferrer(referrerId, request.companyId());

        if (!referrerCapacity.hasRoom(referrerId)) {
            throw new ConflictException("You are already holding "
                    + referrerCapacity.capacityOf(referrerId)
                    + " open referrals; close one before accepting another");
        }
        if (request.seekerId().equals(referrerId)) {
            throw new ConflictException("You cannot refer yourself");
        }

        return transition(request, ReferralState.ACCEPTED, "REFERRER", referrerId, referrerId,
                null, idempotencyKey, "accept");
    }

    @Transactional
    public ReferralRequest decline(UUID requestId, UUID referrerId, String reason,
                                   String idempotencyKey) {
        Optional<UUID> replay = replayedRequestId(idempotencyKey);
        if (replay.isPresent()) {
            return load(replay.get());
        }

        ReferralRequest request = lock(requestId);
        requireVerifiedReferrer(referrerId, request.companyId());

        return transition(request, ReferralState.DECLINED, "REFERRER", referrerId, referrerId,
                reason, idempotencyKey, "decline");
    }

    @Transactional
    public ReferralRequest submit(UUID requestId, UUID referrerId, String idempotencyKey) {
        Optional<UUID> replay = replayedRequestId(idempotencyKey);
        if (replay.isPresent()) {
            return load(replay.get());
        }

        ReferralRequest request = lock(requestId);
        requireOwningReferrer(request, referrerId);

        return transition(request, ReferralState.SUBMITTED, "REFERRER", referrerId, referrerId,
                null, idempotencyKey, "submit");
    }

    @Transactional
    public ReferralRequest close(UUID requestId, UUID actorId, String outcome,
                                 String idempotencyKey) {
        Optional<UUID> replay = replayedRequestId(idempotencyKey);
        if (replay.isPresent()) {
            return load(replay.get());
        }

        ReferralRequest request = lock(requestId);
        boolean isParticipant = actorId.equals(request.seekerId())
                || actorId.equals(request.referrerId());
        if (!isParticipant) {
            throw new ConflictException("Only the seeker or the referrer can close this request");
        }

        return transition(request, ReferralState.CLOSED,
                actorId.equals(request.seekerId()) ? "SEEKER" : "REFERRER",
                actorId, request.referrerId(), outcome, idempotencyKey, "close");
    }

    // ------------------------------------------------------------------------------------
    // Resume release
    // ------------------------------------------------------------------------------------

    /**
     * Mints a short-lived download link.
     *
     * <p>Access is gated twice on purpose: here, when the link is minted, and again in
     * {@link #readResume} when it is redeemed. A referrer who accepts, gets a link, and then has
     * the request closed out from under them must not still be able to pull the resume with a
     * link they already hold.
     */
    @Transactional(readOnly = true)
    public String mintResumeDownloadUrl(UUID requestId, UUID referrerId) {
        ReferralRequest request = load(requestId);
        requireOwningReferrer(request, referrerId);

        if (!request.state().grantsResumeAccess()) {
            throw new ConflictException(
                    "The resume is released once you accept the request; this one is "
                            + request.state());
        }
        if (request.resumeId() == null) {
            throw new NotFoundException("Resume for referral request", requestId);
        }

        Instant expiresAt = Instant.now().plus(storageProperties.getDownloadUrlTtl());
        String token = new ResumeAccessToken(request.resumeId(), requestId, expiresAt)
                .sign(storageProperties.getUrlSigningSecret());

        log.info("Minted resume download token for request {} by referrer {}", requestId, referrerId);
        return "/api/v1/referrals/resume?token=" + token;
    }

    /** Redeems a token. Re-checks state, because a valid signature is not a standing permission. */
    @Transactional(readOnly = true)
    public ResumePayload readResume(String token) {
        ResumeAccessToken access = ResumeAccessToken.verify(token,
                storageProperties.getUrlSigningSecret());

        ReferralRequest request = load(access.requestId());
        if (!request.state().grantsResumeAccess()) {
            throw new ConflictException("This referral is no longer open; the link is void");
        }
        if (request.resumeId() == null || !request.resumeId().equals(access.resumeId())) {
            throw new NotFoundException("Resume", access.resumeId());
        }

        var metadata = resumes.findMetadata(access.resumeId())
                .orElseThrow(() -> new NotFoundException("Resume", access.resumeId()));
        return new ResumePayload(metadata.filename(), metadata.contentType(),
                resumes.read(access.resumeId()));
    }

    public record ResumePayload(String filename, String contentType, byte[] bytes) {
    }

    // ------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------

    private ReferralRequest transition(ReferralRequest request, ReferralState to, String actorType,
                                       UUID actorId, UUID referrerId, String reason,
                                       String idempotencyKey, String operation) {
        ReferralState from = request.state();
        // Throws IllegalTransitionException for anything off the graph.
        from.transitionTo(to);

        store.applyTransition(request.id(), to, referrerId, reason);
        store.recordTransition(request.id(), from, to, actorType, actorId, reason);
        updateReputation(request, from, to, referrerId);

        events.publish(new ReferralLifecycleChanged(request.id(), request.seekerId(),
                request.canonicalJobId(), referrerId, from.name(), to.name(), actorType,
                Instant.now()));

        events.publish(new NotificationRequested(Ids.next(), request.seekerId(), "email",
                "referral-" + to.name().toLowerCase(java.util.Locale.ROOT),
                Map.of("requestId", request.id().toString(), "state", to.name()), Instant.now()));

        store.recordIdempotency(idempotencyKey, request.id(), operation, to);
        return load(request.id());
    }

    /**
     * Counters that feed the reputation score.
     *
     * <p>"Responded" counts accept and decline equally. A fast, honest no is a good outcome for
     * the seeker — it frees them to ask someone else — and scoring it as a failure would train
     * referrers to leave requests rotting instead.
     */
    private void updateReputation(ReferralRequest request, ReferralState from, ReferralState to,
                                  UUID referrerId) {
        if (referrerId == null) {
            return;
        }
        switch (to) {
            case ACCEPTED -> {
                trustStore.incrementCounter(referrerId, "requests_received");
                trustStore.incrementCounter(referrerId, "requests_responded");
                trustStore.incrementCounter(referrerId, "requests_accepted");
            }
            case DECLINED -> {
                trustStore.incrementCounter(referrerId, "requests_received");
                trustStore.incrementCounter(referrerId, "requests_responded");
            }
            case SUBMITTED -> trustStore.incrementCounter(referrerId, "requests_completed");
            case EXPIRED -> {
                trustStore.incrementCounter(referrerId, "requests_received");
                trustStore.incrementCounter(referrerId, "requests_expired");
            }
            default -> {
                // CLOSED after SUBMITTED is bookkeeping, not a signal about the referrer.
            }
        }
    }

    private void requireVerifiedReferrer(UUID referrerId, UUID companyId) {
        if (!verification.isVerifiedReferrer(referrerId, companyId)) {
            throw new ConflictException(
                    "Only a currently verified employee of this company can act on this request");
        }
    }

    private void requireOwningReferrer(ReferralRequest request, UUID referrerId) {
        if (request.referrerId() == null) {
            // Distinct from "someone else has it". Saying a request was taken when nobody has
            // taken it sends the reader looking for a race that never happened.
            throw new ConflictException(
                    "Nobody has accepted this referral yet; accept it before acting on it");
        }
        if (!request.referrerId().equals(referrerId)) {
            throw new ConflictException("This referral was accepted by someone else");
        }
    }

    private Optional<UUID> replayedRequestId(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return store.replayOf(idempotencyKey)
                .flatMap(state -> store.requestForIdempotencyKey(idempotencyKey));
    }

    private ReferralRequest lock(UUID requestId) {
        return store.findByIdForUpdate(requestId)
                .orElseThrow(() -> new NotFoundException("Referral request", requestId));
    }

    public ReferralRequest load(UUID requestId) {
        return store.findById(requestId)
                .orElseThrow(() -> new NotFoundException("Referral request", requestId));
    }
}
