package com.referralhub.trust.api;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

    public record RegisterUserRequest(@NotBlank String displayName, @NotBlank String email) {
    }

    public record StartVerificationRequest(@NotNull UUID userId, @NotNull UUID companyId,
                                           @NotBlank String workEmail) {
    }

    public record ConfirmVerificationRequest(@NotNull UUID userId, @NotNull UUID companyId,
                                             @NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }

    public record UserView(UUID id) {
    }

    public record VerificationView(boolean verified, Instant expiresAt) {
    }

    public record StandingView(UUID userId, double reputation, double responseRate,
                               double completionRate, int remainingDailyRequests,
                               int remainingReferralCapacity) {
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@Valid @RequestBody RegisterUserRequest request) {
        return new UserView(store.createUser(request.displayName(), request.email()));
    }

    /**
     * Always 202, whatever happened. A different response for a known address would turn this
     * endpoint into an employee directory.
     */
    @PostMapping("/verifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void startVerification(@Valid @RequestBody StartVerificationRequest request) {
        verificationService.startVerification(request.userId(), request.companyId(),
                request.workEmail());
    }

    @PostMapping("/verifications/confirm")
    public VerificationView confirm(@Valid @RequestBody ConfirmVerificationRequest request) {
        Instant expiresAt = verificationService.confirm(request.userId(), request.companyId(),
                request.code());
        return new VerificationView(true, expiresAt);
    }

    @GetMapping("/users/{userId}/standing")
    public StandingView standing(@PathVariable UUID userId) {
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
