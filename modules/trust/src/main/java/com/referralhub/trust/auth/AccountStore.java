package com.referralhub.trust.auth;

import com.referralhub.common.ids.Ids;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AccountStore {

    private static final RowMapper<Account> MAPPER = (rs, rowNum) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("display_name"),
            rs.getString("password_hash"),
            List.of(rs.getString("roles").split(",")));

    private final JdbcTemplate jdbc;

    public AccountStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findByEmail(String email) {
        return jdbc.query("""
                SELECT id, email, display_name, password_hash, roles
                FROM platform_user WHERE lower(email) = lower(?)
                """, MAPPER, email).stream().findFirst();
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.query("""
                SELECT id, email, display_name, password_hash, roles
                FROM platform_user WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public UUID create(String displayName, String email, String passwordHash, List<String> roles) {
        UUID id = Ids.next();
        jdbc.update("""
                INSERT INTO platform_user (id, display_name, email, password_hash, roles)
                VALUES (?, ?, ?, ?, ?)
                """, id, displayName, email, passwordHash, String.join(",", roles));
        jdbc.update("INSERT INTO reputation_counters (user_id) VALUES (?) ON CONFLICT DO NOTHING", id);
        return id;
    }

    /** Attaches a password to an account that predates authentication. */
    public void setPassword(UUID userId, String passwordHash) {
        jdbc.update("UPDATE platform_user SET password_hash = ? WHERE id = ?", passwordHash, userId);
    }

    public void recordLogin(UUID userId) {
        jdbc.update("UPDATE platform_user SET last_login_at = now() WHERE id = ?", userId);
    }

    public boolean anyAdminExists() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM platform_user WHERE roles LIKE '%ADMIN%'", Integer.class);
        return n != null && n > 0;
    }
}
