package com.referralhub.trust.capacity;

import com.referralhub.trust.config.TrustProperties;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * How many open referrals one person is allowed to be holding.
 *
 * <p>Counted from Postgres rather than cached, and deliberately so: this is the number the
 * matcher divides work by, and an over-count means a referrer silently drops requests they
 * were assigned. Correctness beats the microsecond a cache would save on a path that runs
 * once per assignment, not once per page view.
 */
@Component
public class ReferrerCapacity {

    private final JdbcTemplate jdbc;
    private final TrustProperties properties;

    public ReferrerCapacity(JdbcTemplate jdbc, TrustProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** Requests this person has accepted and not yet closed. */
    public int inFlight(UUID referrerId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM referral_request
                WHERE referrer_id = ? AND state = 'ACCEPTED'
                """, Integer.class, referrerId);
        return count == null ? 0 : count;
    }

    public int capacityOf(UUID referrerId) {
        return properties.getReferrerConcurrentCapacity();
    }

    public int remaining(UUID referrerId) {
        return Math.max(0, capacityOf(referrerId) - inFlight(referrerId));
    }

    public boolean hasRoom(UUID referrerId) {
        return remaining(referrerId) > 0;
    }
}
