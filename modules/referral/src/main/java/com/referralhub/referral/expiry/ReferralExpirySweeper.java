package com.referralhub.referral.expiry;

import com.referralhub.common.events.ReferralLifecycleChanged;
import com.referralhub.common.outbox.TransactionalEventPublisher;
import com.referralhub.referral.ReferralRequest;
import com.referralhub.referral.ReferralRequestStore;
import com.referralhub.referral.state.ReferralState;
import com.referralhub.trust.verify.VerificationStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retires requests nobody acted on.
 *
 * <p>Without this, a seeker's daily quota stays consumed by requests that will never be answered,
 * and a referrer's capacity stays occupied by ones they forgot. Expiry is what keeps both sides
 * of the marketplace liquid.
 *
 * <p>Rows are claimed with {@code SKIP LOCKED}, so every replica can run the sweeper and no
 * request is expired twice.
 */
@Component
@ConditionalOnProperty(prefix = "referralhub.referral", name = "expiry-enabled",
        havingValue = "true", matchIfMissing = true)
public class ReferralExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ReferralExpirySweeper.class);
    private static final int BATCH_SIZE = 100;

    private final ReferralRequestStore store;
    private final VerificationStore trustStore;
    private final TransactionalEventPublisher events;
    private final Counter expiredCounter;

    public ReferralExpirySweeper(ReferralRequestStore store,
                                 VerificationStore trustStore,
                                 TransactionalEventPublisher events,
                                 MeterRegistry meters) {
        this.store = store;
        this.trustStore = trustStore;
        this.events = events;
        this.expiredCounter = meters.counter("referralhub.referral.expired");
    }

    @Scheduled(fixedDelayString = "${referralhub.referral.expiry-interval-millis:60000}")
    public void sweep() {
        try {
            int expired = expireBatch();
            if (expired > 0) {
                expiredCounter.increment(expired);
                log.info("Expired {} stale referral requests", expired);
            }
        } catch (Exception e) {
            log.error("Referral expiry sweep failed", e);
        }
    }

    @Transactional
    public int expireBatch() {
        List<ReferralRequest> stale = store.claimExpired(BATCH_SIZE);
        for (ReferralRequest request : stale) {
            ReferralState from = request.state();
            from.transitionTo(ReferralState.EXPIRED);

            store.applyTransition(request.id(), ReferralState.EXPIRED, null, "expired unanswered");
            store.recordTransition(request.id(), from, ReferralState.EXPIRED, "SYSTEM", null,
                    "no action before " + request.expiresAt());

            // Only counts against a referrer who had actually taken it on.
            if (request.referrerId() != null) {
                trustStore.incrementCounter(request.referrerId(), "requests_received");
                trustStore.incrementCounter(request.referrerId(), "requests_expired");
            }

            events.publish(new ReferralLifecycleChanged(request.id(), request.seekerId(),
                    request.canonicalJobId(), request.referrerId(), from.name(),
                    ReferralState.EXPIRED.name(), "SYSTEM", Instant.now()));
        }
        return stale.size();
    }
}
