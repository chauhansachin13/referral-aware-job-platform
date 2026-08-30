package com.referralhub.trust.api;

import com.referralhub.common.error.ConflictException;
import com.referralhub.trust.auth.CurrentUser;
import com.referralhub.trust.capacity.ReferrerCapacity;
import com.referralhub.trust.capacity.SeekerQuota;
import com.referralhub.trust.reputation.ReputationScore;
import com.referralhub.trust.verify.VerificationService;
import com.referralhub.trust.verify.VerificationStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee verification and standing, always for the authenticated caller.
 *
 * <p>Account creation moved to {@code /api/v1/auth/register}: an endpoint that mints a user id
 * anybody could then act as is exactly what authentication is here to remove.
 */
@RestController
@RequestMapping("/api/v1/trust")
public class TrustController {

    private final VerificationService verificationService;
    private final VerificationStore store;
    private final SeekerQuota seekerQuota;
    private final ReferrerCapacity referrerCapacity;

    public TrustController(VerificationService verificationService,
                           VerificationStore store,
                           SeekerQuota seekerQuota,
                           ReferrerCapacity referrerCapacity) {
        this.verificationService = verificationService;
        this.store = store;
        this.seekerQuota = seekerQuota;
        this.referrerCapacity = referrerCapacity;
    }

    public record StartVerificationRequest(@NotNull UUID companyId, @NotBlank String workEmail) {
    }

    public record ConfirmVerificationRequest(@NotNull UUID companyId,
                                             @NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }

    public record VerificationView(boolean verified, Instant expiresAt) {
    }

    public record StandingView(UUID userId, double reputation, double responseRate,
                               double completionRate, int remainingDailyRequests,
                               int remainingReferralCapacity) {
    }

    /**
     * Always 202, whatever happened.
     *
     * <p>A different response for an address that is already registered, or for a company that
     * has no verified domain, would turn this into an employee directory.
     */
    @PostMapping("/verifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void startVerification(@Valid @RequestBody StartVerificationRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        verificationService.startVerification(CurrentUser.idOf(jwt), request.companyId(),
                request.workEmail());
    }

    @PostMapping("/verifications/confirm")
    public VerificationView confirm(@Valid @RequestBody ConfirmVerificationRequest request,
                                    @AuthenticationPrincipal Jwt jwt) {
        Instant expiresAt = verificationService.confirm(CurrentUser.idOf(jwt), request.companyId(),
                request.code());
        return new VerificationView(true, expiresAt);
    }

    @GetMapping("/standing")
    public StandingView myStanding(@AuthenticationPrincipal Jwt jwt) {
        return standingOf(CurrentUser.idOf(jwt));
    }

    /** Somebody else's standing is administrator-only; it is a signal about a person. */
    @GetMapping("/users/{userId}/standing")
    public StandingView standing(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        if (!userId.equals(CurrentUser.idOf(jwt)) && !CurrentUser.isAdmin()) {
            throw new ConflictException("You may only read your own standing");
        }
        return standingOf(userId);
    }

    private StandingView standingOf(UUID userId) {
        ReputationScore.Counters counters = store.countersFor(userId);
        return new StandingView(
                userId,
                ReputationScore.of(counters),
                ReputationScore.responseRate(counters),
                ReputationScore.completionRate(counters),
                seekerQuota.remaining(userId),
                referrerCapacity.remaining(userId));
    }
}
