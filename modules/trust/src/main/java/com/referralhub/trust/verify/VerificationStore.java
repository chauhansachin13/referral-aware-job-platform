package com.referralhub.trust.verify;

import com.referralhub.common.ids.Ids;
import com.referralhub.trust.reputation.ReputationScore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class VerificationStore {

    private static final RowMapper<EmployeeVerification> MAPPER = (rs, rowNum) ->
            new EmployeeVerification(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getObject("company_id", UUID.class),
                    rs.getString("work_email"),
                    rs.getString("email_domain"),
                    VerificationStatus.valueOf(rs.getString("status")),
                    instant(rs.getTimestamp("verified_at")),
                    instant(rs.getTimestamp("expires_at")),
                    instant(rs.getTimestamp("otp_expires_at")),
                    rs.getInt("otp_attempts"));

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private final JdbcTemplate jdbc;

    public VerificationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID createUser(String displayName, String email) {
        List<UUID> existing = jdbc.queryForList(
                "SELECT id FROM platform_user WHERE email = ?", UUID.class, email);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        UUID id = Ids.next();
        jdbc.update("INSERT INTO platform_user (id, display_name, email) VALUES (?, ?, ?)",
                id, displayName, email);
        jdbc.update("INSERT INTO reputation_counters (user_id) VALUES (?) ON CONFLICT DO NOTHING", id);
        return id;
    }

    public Optional<EmployeeVerification> find(UUID userId, UUID companyId) {
        return jdbc.query("""
                SELECT * FROM employee_verification WHERE user_id = ? AND company_id = ?
                """, MAPPER, userId, companyId).stream().findFirst();
    }

    public Optional<EmployeeVerification> findById(UUID id) {
        return jdbc.query("SELECT * FROM employee_verification WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** Starts or restarts a verification, replacing any previous unfinished attempt. */
    public UUID startVerification(UUID userId, UUID companyId, String workEmail, String domain,
                                  String otpHash, String otpSalt, Instant otpExpiresAt) {
        UUID id = Ids.next();
        return jdbc.queryForObject("""
                INSERT INTO employee_verification
                    (id, user_id, company_id, work_email, email_domain,
                     otp_hash, otp_salt, otp_expires_at, otp_attempts, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 'PENDING')
                ON CONFLICT (user_id, company_id) DO UPDATE SET
                    work_email = excluded.work_email,
                    email_domain = excluded.email_domain,
                    otp_hash = excluded.otp_hash,
                    otp_salt = excluded.otp_salt,
                    otp_expires_at = excluded.otp_expires_at,
                    otp_attempts = 0,
                    status = 'PENDING'
                RETURNING id
                """, UUID.class, id, userId, companyId, workEmail, domain,
                otpHash, otpSalt, Timestamp.from(otpExpiresAt));
    }

    /** @return the stored hash and salt, or null when there is no live challenge. */
    public String[] challengeFor(UUID userId, UUID companyId) {
        return jdbc.query("""
                SELECT otp_hash, otp_salt FROM employee_verification
                WHERE user_id = ? AND company_id = ? AND status = 'PENDING'
                """, (rs, rowNum) -> new String[] {rs.getString("otp_hash"), rs.getString("otp_salt")},
                userId, companyId).stream().findFirst().orElse(null);
    }

    public int recordFailedAttempt(UUID userId, UUID companyId) {
        return jdbc.queryForObject("""
                UPDATE employee_verification
                SET otp_attempts = otp_attempts + 1
                WHERE user_id = ? AND company_id = ?
                RETURNING otp_attempts
                """, Integer.class, userId, companyId);
    }

    public void markVerified(UUID userId, UUID companyId, Instant expiresAt) {
        jdbc.update("""
                UPDATE employee_verification
                SET status = 'VERIFIED',
                    verified_at = now(),
                    last_reverified_at = now(),
                    expires_at = ?,
                    otp_hash = NULL,
                    otp_salt = NULL,
                    otp_expires_at = NULL,
                    otp_attempts = 0
                WHERE user_id = ? AND company_id = ?
                """, Timestamp.from(expiresAt), userId, companyId);
    }

    public void updateStatus(UUID userId, UUID companyId, VerificationStatus status) {
        jdbc.update("UPDATE employee_verification SET status = ? WHERE user_id = ? AND company_id = ?",
                status.name(), userId, companyId);
    }

    /** Sweeps leases that have run out. Returns how many were expired. */
    public int expireStale() {
        return jdbc.update("""
                UPDATE employee_verification
                SET status = 'EXPIRED'
                WHERE status = 'VERIFIED' AND expires_at IS NOT NULL AND expires_at < now()
                """);
    }

    public List<EmployeeVerification> activeReferrersFor(UUID companyId) {
        return jdbc.query("""
                SELECT * FROM employee_verification
                WHERE company_id = ? AND status = 'VERIFIED' AND expires_at > now()
                """, MAPPER, companyId);
    }

    public ReputationScore.Counters countersFor(UUID userId) {
        return jdbc.query("""
                SELECT requests_received, requests_responded, requests_accepted,
                       requests_completed, requests_expired
                FROM reputation_counters WHERE user_id = ?
                """, (rs, rowNum) -> new ReputationScore.Counters(
                        rs.getInt("requests_received"),
                        rs.getInt("requests_responded"),
                        rs.getInt("requests_accepted"),
                        rs.getInt("requests_completed"),
                        rs.getInt("requests_expired")), userId)
                .stream().findFirst().orElse(ReputationScore.Counters.empty());
    }

    /** Bumps one counter. Column names are from a fixed allow-list, never from user input. */
    public void incrementCounter(UUID userId, String column) {
        List<String> allowed = List.of("requests_received", "requests_responded",
                "requests_accepted", "requests_completed", "requests_expired", "requests_sent");
        if (!allowed.contains(column)) {
            throw new IllegalArgumentException("Unknown reputation counter: " + column);
        }
        jdbc.update("""
                INSERT INTO reputation_counters (user_id, %s, updated_at)
                VALUES (?, 1, now())
                ON CONFLICT (user_id) DO UPDATE SET
                    %s = reputation_counters.%s + 1,
                    updated_at = now()
                """.formatted(column, column, column), userId);
    }
}
