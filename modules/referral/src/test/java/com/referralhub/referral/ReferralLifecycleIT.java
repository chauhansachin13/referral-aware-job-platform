package com.referralhub.referral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.referralhub.common.error.ConflictException;
import com.referralhub.common.ids.Ids;
import com.referralhub.common.testing.Databases;
import com.referralhub.common.testing.PlatformProperties;
import com.referralhub.common.testing.RequiresDocker;
import com.referralhub.referral.expiry.ReferralExpirySweeper;
import com.referralhub.referral.resume.ResumeCipher;
import com.referralhub.referral.state.IllegalTransitionException;
import com.referralhub.referral.state.ReferralState;
import com.referralhub.trust.verify.VerificationStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The referral lifecycle against a real Postgres.
 *
 * <p>The interesting assertions are the ones that only a database can settle: that an idempotent
 * retry writes one row and not two, that the audit trail is complete, and that a row locked by
 * one accepting referrer is not simultaneously accepted by another.
 */
@Tag("integration")
@RequiresDocker
@SpringBootTest(classes = ReferralTestApplication.class, properties = {
        "referralhub.outbox.relay-enabled=false",
        "referralhub.ingestion.crawl-enabled=false",
        "referralhub.search.indexer-enabled=false",
        "referralhub.dedup.consumer-enabled=false",
        "referralhub.referral.expiry-enabled=false",
        "spring.flyway.locations=classpath:db/migration/common,classpath:db/migration/ingestion,"
                + "classpath:db/migration/dedup,classpath:db/migration/referral,"
                + "classpath:db/migration/trust",
        "spring.kafka.bootstrap-servers=localhost:1",
        "referralhub.storage.access-key=test",
        "referralhub.storage.secret-key=testtest",
        "referralhub.storage.url-signing-secret=integration-test-signing-secret"
})
class ReferralLifecycleIT {

    private static final UUID COMPANY = Ids.next();
    private static final UUID JOB = Ids.next();

    @Autowired
    private ReferralService referrals;
    @Autowired
    private ReferralRequestStore store;
    @Autowired
    private ReferralExpirySweeper sweeper;
    @Autowired
    private VerificationStore trustStore;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID seeker;
    private UUID referrer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PlatformProperties.postgres(registry);
        PlatformProperties.redis(registry);
        registry.add("referralhub.storage.encryption-key", ResumeCipher::generateKey);
    }

    @BeforeEach
    void seed() {
        Databases.truncateAll(jdbc);
        jdbc.update("INSERT INTO company (id, name, slug, email_domain) VALUES (?, ?, ?, ?)",
                COMPANY, "Acme", "acme", "acme.com");

        seeker = trustStore.createUser("Seeker", "seeker@example.com");
        referrer = trustStore.createUser("Referrer", "referrer@example.com");
        verifyEmployee(referrer);
    }

    private void verifyEmployee(UUID userId) {
        jdbc.update("""
                INSERT INTO employee_verification
                    (id, user_id, company_id, work_email, email_domain, status, verified_at, expires_at)
                VALUES (?, ?, ?, ?, 'acme.com', 'VERIFIED', now(), ?)
                """, Ids.next(), userId, COMPANY, "someone@acme.com",
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
    }

    private ReferralRequest newRequest() {
        return referrals.request(seeker, JOB, COMPANY, null, "Would appreciate a referral", null);
    }

    @Test
    @DisplayName("the happy path walks REQUESTED to CLOSED and records every step")
    void happyPathIsFullyAudited() {
        ReferralRequest created = newRequest();
        assertThat(created.state()).isEqualTo(ReferralState.REQUESTED);

        assertThat(referrals.accept(created.id(), referrer, null).state())
                .isEqualTo(ReferralState.ACCEPTED);
        assertThat(referrals.submit(created.id(), referrer, null).state())
                .isEqualTo(ReferralState.SUBMITTED);
        assertThat(referrals.close(created.id(), seeker, "offer accepted", null).state())
                .isEqualTo(ReferralState.CLOSED);

        List<ReferralRequestStore.TransitionRecord> audit = store.auditTrail(created.id());
        assertThat(audit).extracting(ReferralRequestStore.TransitionRecord::toState)
                .containsExactly("REQUESTED", "ACCEPTED", "SUBMITTED", "CLOSED");
        assertThat(audit).allSatisfy(record ->
                assertThat(record.occurredAt()).isNotNull());
        assertThat(audit.get(1).actorId()).isEqualTo(referrer);
        assertThat(audit.get(3).actorType()).isEqualTo("SEEKER");
    }

    @Test
    @DisplayName("an illegal transition is refused by the domain, not by the controller")
    void illegalTransitionIsRefused() {
        ReferralRequest created = newRequest();

        assertThatThrownBy(() -> referrals.submit(created.id(), referrer, null))
                .isInstanceOf(ConflictException.class);

        referrals.accept(created.id(), referrer, null);
        referrals.submit(created.id(), referrer, null);
        referrals.close(created.id(), seeker, null, null);

        // CLOSED is terminal; nothing may follow it.
        assertThatThrownBy(() -> referrals.submit(created.id(), referrer, null))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    @DisplayName("replaying an accept with the same idempotency key does not double-apply it")
    void doubleSubmissionIsIdempotent() {
        ReferralRequest created = newRequest();
        String key = "accept-" + created.id();

        ReferralRequest first = referrals.accept(created.id(), referrer, key);
        ReferralRequest replay = referrals.accept(created.id(), referrer, key);

        assertThat(first.state()).isEqualTo(ReferralState.ACCEPTED);
        assertThat(replay.state()).isEqualTo(ReferralState.ACCEPTED);
        assertThat(replay.id()).isEqualTo(first.id());

        // The audit trail is the proof: one transition, not two.
        assertThat(store.auditTrail(created.id()))
                .extracting(ReferralRequestStore.TransitionRecord::toState)
                .containsExactly("REQUESTED", "ACCEPTED");

        Integer counters = jdbc.queryForObject(
                "SELECT requests_accepted FROM reputation_counters WHERE user_id = ?",
                Integer.class, referrer);
        assertThat(counters).as("reputation must not be inflated by a retry").isEqualTo(1);
    }

    @Test
    @DisplayName("a retried create returns the original request rather than making a second one")
    void createIsIdempotent() {
        String key = "create-once";

        ReferralRequest first = referrals.request(seeker, JOB, COMPANY, null, "hello", key);
        ReferralRequest replay = referrals.request(seeker, JOB, COMPANY, null, "hello", key);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(store.findBySeeker(seeker, 10)).hasSize(1);
    }

    @Test
    @DisplayName("a second referrer cannot accept a request that is already taken")
    void onlyOneReferrerCanAccept() {
        UUID other = trustStore.createUser("Other", "other@example.com");
        verifyEmployee(other);

        ReferralRequest created = newRequest();
        referrals.accept(created.id(), referrer, null);

        assertThatThrownBy(() -> referrals.accept(created.id(), other, null))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    @DisplayName("an unverified employee cannot act on a request")
    void unverifiedReferrersAreRefused() {
        UUID stranger = trustStore.createUser("Stranger", "stranger@example.com");
        ReferralRequest created = newRequest();

        assertThatThrownBy(() -> referrals.accept(created.id(), stranger, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("verified employee");
    }

    @Test
    @DisplayName("the same seeker cannot queue the same job twice")
    void duplicateRequestsAreRefused() {
        newRequest();

        assertThatThrownBy(this::newRequest)
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already requested");
    }

    @Test
    @DisplayName("the resume is not released before the request is accepted")
    void resumeIsGatedOnAcceptance() {
        ReferralRequest created = newRequest();

        assertThatThrownBy(() -> referrals.mintResumeDownloadUrl(created.id(), referrer))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("the sweeper expires stale requests and leaves fresh ones alone")
    void expirySweeperRetiresStaleRequests() {
        ReferralRequest stale = newRequest();
        jdbc.update("UPDATE referral_request SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), stale.id());

        UUID otherJob = Ids.next();
        ReferralRequest fresh = referrals.request(seeker, otherJob, COMPANY, null, "fresh", null);

        assertThat(sweeper.expireBatch()).isEqualTo(1);

        assertThat(referrals.load(stale.id()).state()).isEqualTo(ReferralState.EXPIRED);
        assertThat(referrals.load(fresh.id()).state()).isEqualTo(ReferralState.REQUESTED);
        assertThat(store.auditTrail(stale.id()))
                .extracting(ReferralRequestStore.TransitionRecord::actorType)
                .contains("SYSTEM");
    }

    @Test
    @DisplayName("a declined request is terminal and counts as a response, not a failure")
    void declineIsARespectableOutcome() {
        ReferralRequest created = newRequest();

        assertThat(referrals.decline(created.id(), referrer, "not my team", null).state())
                .isEqualTo(ReferralState.DECLINED);

        Integer responded = jdbc.queryForObject(
                "SELECT requests_responded FROM reputation_counters WHERE user_id = ?",
                Integer.class, referrer);
        assertThat(responded).isEqualTo(1);

        assertThatThrownBy(() -> referrals.accept(created.id(), referrer, null))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    @DisplayName("a referrer at capacity cannot accept another request")
    void capacityIsEnforced() {
        // Default concurrent capacity is 5; fill it, then try one more.
        for (int i = 0; i < 5; i++) {
            UUID job = Ids.next();
            UUID otherSeeker = trustStore.createUser("Seeker " + i, "seeker" + i + "@example.com");
            ReferralRequest request = referrals.request(otherSeeker, job, COMPANY, null, "hi", null);
            referrals.accept(request.id(), referrer, null);
        }

        ReferralRequest overflow = newRequest();
        assertThatThrownBy(() -> referrals.accept(overflow.id(), referrer, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("open referrals");
    }
}
