package com.referralhub.trust.verify;

import com.referralhub.common.error.ConflictException;
import com.referralhub.common.error.NotFoundException;
import com.referralhub.common.events.NotificationRequested;
import com.referralhub.common.ids.Ids;
import com.referralhub.common.outbox.TransactionalEventPublisher;
import com.referralhub.ingestion.board.BoardStore;
import com.referralhub.ingestion.board.CompanyRecord;
import com.referralhub.trust.config.TrustProperties;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves that a would-be referrer actually works where they say they do.
 *
 * <p>The code is delivered by emitting a {@link NotificationRequested} through the outbox rather
 * than by calling an email provider inline. That keeps a third party's latency out of the
 * request's transaction, and means a provider outage delays delivery instead of losing the
 * verification attempt entirely.
 */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    private final VerificationStore store;
    private final BoardStore companies;
    private final TrustProperties properties;
    private final TransactionalEventPublisher events;

    public VerificationService(VerificationStore store,
                               BoardStore companies,
                               TrustProperties properties,
                               TransactionalEventPublisher events) {
        this.store = store;
        this.companies = companies;
        this.properties = properties;
        this.events = events;
    }

    /**
     * Issues a code to the work address.
     *
     * <p>Returns nothing useful on purpose: the caller must not learn the code, and the response
     * is identical whether or not the address was already registered, so this endpoint cannot be
     * used to enumerate a company's employees.
     */
    @Transactional
    public void startVerification(UUID userId, UUID companyId, String workEmail) {
        CompanyRecord company = companies.findCompany(companyId)
                .orElseThrow(() -> new NotFoundException("Company", companyId));

        if (company.emailDomain() == null || company.emailDomain().isBlank()) {
            throw new ConflictException(
                    "No verified email domain is registered for " + company.name()
                            + "; employee verification is unavailable for this company");
        }
        if (!WorkEmails.isWellFormed(workEmail)) {
            throw new IllegalArgumentException("Not a valid email address");
        }
        if (!WorkEmails.belongsToCompany(workEmail, company.emailDomain())) {
            throw new ConflictException("That address is not on " + company.name()
                    + "'s domain (" + company.emailDomain() + ")");
        }

        OneTimeCodes.Issued issued = OneTimeCodes.issue();
        Instant expiresAt = Instant.now().plus(properties.getOtpValidity());
        store.startVerification(userId, companyId, workEmail, WorkEmails.domainOf(workEmail),
                issued.hash(), issued.salt(), expiresAt);

        events.publish(new NotificationRequested(
                Ids.next(),
                userId,
                "email",
                "employee-verification-code",
                Map.of("to", workEmail,
                        "code", issued.plaintext(),
                        "company", company.name(),
                        "expiresInMinutes", String.valueOf(properties.getOtpValidity().toMinutes())),
                Instant.now()));

        log.info("Issued employee verification code for user {} at company {}", userId, companyId);
    }

    /**
     * Completes verification.
     *
     * @return the instant the resulting verification lease runs out
     */
    @Transactional
    public Instant confirm(UUID userId, UUID companyId, String code) {
        EmployeeVerification verification = store.find(userId, companyId)
                .orElseThrow(() -> new NotFoundException("Verification", userId));

        if (verification.status() == VerificationStatus.REVOKED) {
            throw new ConflictException("This verification was revoked; start a new one");
        }
        if (verification.otpExpiresAt() == null
                || verification.otpExpiresAt().isBefore(Instant.now())) {
            throw new ConflictException("That code has expired; request a new one");
        }

        String[] challenge = store.challengeFor(userId, companyId);
        if (challenge == null || challenge[0] == null) {
            throw new ConflictException("No verification is in progress");
        }

        if (!OneTimeCodes.matches(code, challenge[0], challenge[1])) {
            int attempts = store.recordFailedAttempt(userId, companyId);
            if (attempts >= properties.getMaxOtpAttempts()) {
                // Revoking rather than merely rejecting stops an offline brute force from
                // simply continuing against the same challenge.
                store.updateStatus(userId, companyId, VerificationStatus.REVOKED);
                throw new ConflictException("Too many incorrect codes; verification is locked");
            }
            throw new ConflictException("Incorrect code ("
                    + (properties.getMaxOtpAttempts() - attempts) + " attempts remaining)");
        }

        Instant expiresAt = Instant.now().plus(properties.getVerificationValidity());
        store.markVerified(userId, companyId, expiresAt);
        return expiresAt;
    }

    public boolean isVerifiedReferrer(UUID userId, UUID companyId) {
        return store.find(userId, companyId)
                .map(verification -> verification.isActiveAt(Instant.now()))
                .orElse(false);
    }

    /**
     * Expires leases that have run out.
     *
     * <p>Verification is a lease and not a permanent fact because people change jobs without
     * telling anyone. Left alone, the referrer pool would slowly fill with ex-employees who can
     * no longer actually submit a referral.
     */
    @Scheduled(cron = "${referralhub.trust.reverification-cron:0 0 * * * *}")
    @Transactional
    public void expireStaleVerifications() {
        int expired = store.expireStale();
        if (expired > 0) {
            log.info("Expired {} employee verifications past their validity window", expired);
        }
    }
}
