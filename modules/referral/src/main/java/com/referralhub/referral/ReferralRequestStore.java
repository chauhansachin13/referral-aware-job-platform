package com.referralhub.referral;

import com.referralhub.common.ids.Ids;
import com.referralhub.referral.state.ReferralState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ReferralRequestStore {

    private static final RowMapper<ReferralRequest> MAPPER = (rs, rowNum) -> new ReferralRequest(
            rs.getObject("id", UUID.class),
            rs.getObject("seeker_id", UUID.class),
            rs.getObject("referrer_id", UUID.class),
            rs.getObject("canonical_job_id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getObject("resume_id", UUID.class),
            ReferralState.valueOf(rs.getString("state")),
            rs.getString("message"),
            rs.getString("decline_reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            instant(rs.getTimestamp("accepted_at")),
            instant(rs.getTimestamp("submitted_at")),
            instant(rs.getTimestamp("closed_at")));

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private final JdbcTemplate jdbc;

    public ReferralRequestStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID create(UUID seekerId, UUID canonicalJobId, UUID companyId, UUID resumeId,
                       String message, Instant expiresAt) {
        UUID id = Ids.next();
        jdbc.update("""
                INSERT INTO referral_request
                    (id, seeker_id, canonical_job_id, company_id, resume_id, message,
                     state, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, id, seekerId, canonicalJobId, companyId, resumeId, message,
                Timestamp.from(expiresAt));
        return id;
    }

    public Optional<ReferralRequest> findById(UUID id) {
        return jdbc.query("SELECT * FROM referral_request WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * Reads the row and holds it for the duration of the caller's transaction.
     *
     * <p>Two referrers hitting Accept at the same instant would otherwise both read REQUESTED,
     * both find the transition legal, and both write ACCEPTED — the second silently stealing the
     * first one's assignment.
     */
    public Optional<ReferralRequest> findByIdForUpdate(UUID id) {
        return jdbc.query("SELECT * FROM referral_request WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public List<ReferralRequest> findBySeeker(UUID seekerId, int limit) {
        return jdbc.query("""
                SELECT * FROM referral_request WHERE seeker_id = ?
                ORDER BY created_at DESC LIMIT ?
                """, MAPPER, seekerId, limit);
    }

    public List<ReferralRequest> findPendingForJob(UUID canonicalJobId) {
        return jdbc.query("""
                SELECT * FROM referral_request
                WHERE canonical_job_id = ? AND state = 'REQUESTED'
                ORDER BY created_at
                """, MAPPER, canonicalJobId);
    }

    /** Applies a transition and stamps the matching timestamp column. */
    public void applyTransition(UUID id, ReferralState to, UUID referrerId, String declineReason) {
        jdbc.update("""
                UPDATE referral_request
                SET state = ?,
                    referrer_id = COALESCE(?, referrer_id),
                    decline_reason = COALESCE(?, decline_reason),
                    accepted_at  = CASE WHEN ? = 'ACCEPTED'  THEN now() ELSE accepted_at  END,
                    submitted_at = CASE WHEN ? = 'SUBMITTED' THEN now() ELSE submitted_at END,
                    closed_at    = CASE WHEN ? IN ('CLOSED','DECLINED','EXPIRED')
                                        THEN now() ELSE closed_at END,
                    updated_at = now()
                WHERE id = ?
                """, to.name(), referrerId, declineReason, to.name(), to.name(), to.name(), id);
    }

    public void recordTransition(UUID requestId, ReferralState from, ReferralState to,
                                 String actorType, UUID actorId, String reason) {
        jdbc.update("""
                INSERT INTO referral_transition
                    (id, request_id, from_state, to_state, actor_type, actor_id, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, Ids.next(), requestId, from == null ? null : from.name(), to.name(),
                actorType, actorId, reason);
    }

    public List<TransitionRecord> auditTrail(UUID requestId) {
        return jdbc.query("""
                SELECT from_state, to_state, actor_type, actor_id, reason, occurred_at
                FROM referral_transition WHERE request_id = ? ORDER BY occurred_at
                """, (rs, rowNum) -> new TransitionRecord(
                        rs.getString("from_state"),
                        rs.getString("to_state"),
                        rs.getString("actor_type"),
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("reason"),
                        rs.getTimestamp("occurred_at").toInstant()), requestId);
    }

    public record TransitionRecord(String fromState, String toState, String actorType,
                                   UUID actorId, String reason, Instant occurredAt) {
    }

    /**
     * Claims an idempotency key.
     *
     * @return empty when the key is new and the caller should do the work; the previously
     *         recorded outcome when this is a replay
     */
    public Optional<String> replayOf(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return jdbc.queryForList("""
                SELECT resulting_state FROM referral_idempotency WHERE idempotency_key = ?
                """, String.class, idempotencyKey).stream().findFirst();
    }

    public void recordIdempotency(String idempotencyKey, UUID requestId, String operation,
                                  ReferralState resultingState) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        jdbc.update("""
                INSERT INTO referral_idempotency
                    (idempotency_key, request_id, operation, resulting_state)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, idempotencyKey, requestId, operation, resultingState.name());
    }

    public Optional<UUID> requestForIdempotencyKey(String idempotencyKey) {
        return jdbc.queryForList("SELECT request_id FROM referral_idempotency WHERE idempotency_key = ?",
                UUID.class, idempotencyKey).stream().findFirst();
    }

    /** Non-terminal requests past their deadline, locked so one sweeper wins each row. */
    public List<ReferralRequest> claimExpired(int limit) {
        return jdbc.query("""
                SELECT * FROM referral_request
                WHERE state IN ('REQUESTED', 'ACCEPTED') AND expires_at < now()
                ORDER BY expires_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, MAPPER, limit);
    }

    public int countByState(ReferralState state) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM referral_request WHERE state = ?", Integer.class, state.name());
        return count == null ? 0 : count;
    }
}
