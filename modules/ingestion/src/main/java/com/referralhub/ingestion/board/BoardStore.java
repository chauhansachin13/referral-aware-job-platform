package com.referralhub.ingestion.board;

import com.referralhub.common.ids.Ids;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Durable crawl state for every registered board. */
@Repository
public class BoardStore {

    private static final RowMapper<CompanyBoard> MAPPER = (rs, rowNum) -> new CompanyBoard(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("company_name"),
            rs.getString("source"),
            rs.getString("board_token"),
            rs.getBoolean("enabled"),
            rs.getString("etag"),
            rs.getTimestamp("last_modified") == null ? null : rs.getTimestamp("last_modified").toInstant(),
            rs.getString("last_content_hash"),
            Duration.ofSeconds(rs.getInt("crawl_interval_seconds")),
            rs.getTimestamp("last_crawled_at") == null ? null : rs.getTimestamp("last_crawled_at").toInstant(),
            rs.getTimestamp("last_changed_at") == null ? null : rs.getTimestamp("last_changed_at").toInstant(),
            rs.getInt("consecutive_unchanged"),
            rs.getDouble("observed_postings_per_day"));

    private static final String SELECT = """
            SELECT b.id, b.company_id, c.name AS company_name, b.source, b.board_token, b.enabled,
                   b.etag, b.last_modified, b.last_content_hash, b.crawl_interval_seconds,
                   b.last_crawled_at, b.last_changed_at, b.consecutive_unchanged,
                   b.observed_postings_per_day
            FROM company_board b
            JOIN company c ON c.id = b.company_id
            """;

    private final JdbcTemplate jdbc;

    public BoardStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CompanyBoard> findById(UUID id) {
        return jdbc.query(SELECT + " WHERE b.id = ?", MAPPER, id).stream().findFirst();
    }

    public List<CompanyBoard> findEnabled() {
        return jdbc.query(SELECT + " WHERE b.enabled ORDER BY b.created_at", MAPPER);
    }

    /** Registers a company if the slug is new, otherwise returns the existing id. */
    public UUID upsertCompany(String name, String slug, String emailDomain, String careersUrl) {
        List<UUID> existing = jdbc.queryForList(
                "SELECT id FROM company WHERE slug = ?", UUID.class, slug);
        if (!existing.isEmpty()) {
            jdbc.update("""
                    UPDATE company
                    SET name = ?,
                        email_domain = COALESCE(?, email_domain),
                        careers_url = COALESCE(?, careers_url)
                    WHERE id = ?
                    """, name, emailDomain, careersUrl, existing.get(0));
            return existing.get(0);
        }
        UUID id = Ids.next();
        jdbc.update("""
                INSERT INTO company (id, name, slug, email_domain, careers_url)
                VALUES (?, ?, ?, ?, ?)
                """, id, name, slug, emailDomain, careersUrl);
        return id;
    }

    /** Registers a board, or re-enables and returns the existing one. */
    public UUID registerBoard(UUID companyId, String source, String boardToken, Duration initialInterval) {
        List<UUID> existing = jdbc.queryForList(
                "SELECT id FROM company_board WHERE source = ? AND board_token = ?",
                UUID.class, source, boardToken);
        if (!existing.isEmpty()) {
            jdbc.update("UPDATE company_board SET enabled = true WHERE id = ?", existing.get(0));
            return existing.get(0);
        }
        UUID id = Ids.next();
        jdbc.update("""
                INSERT INTO company_board (id, company_id, source, board_token, crawl_interval_seconds)
                VALUES (?, ?, ?, ?, ?)
                """, id, companyId, source, boardToken, (int) initialInterval.toSeconds());
        return id;
    }

    /** Records the validators and the new cadence after a crawl that produced a body. */
    public void recordFetched(UUID boardId, String etag, Instant lastModified, String contentHash,
                              Duration nextInterval, boolean changed, double postingsPerDay) {
        jdbc.update("""
                UPDATE company_board
                SET etag = ?,
                    last_modified = ?,
                    last_content_hash = ?,
                    crawl_interval_seconds = ?,
                    last_crawled_at = now(),
                    last_changed_at = CASE WHEN ? THEN now() ELSE last_changed_at END,
                    consecutive_unchanged = CASE WHEN ? THEN 0 ELSE consecutive_unchanged + 1 END,
                    observed_postings_per_day = ?
                WHERE id = ?
                """,
                etag,
                lastModified == null ? null : Timestamp.from(lastModified),
                contentHash,
                (int) nextInterval.toSeconds(),
                changed,
                changed,
                postingsPerDay,
                boardId);
    }

    /** Records a 304: nothing changed, so only the cadence and the crawl timestamp move. */
    public void recordNotModified(UUID boardId, Duration nextInterval) {
        jdbc.update("""
                UPDATE company_board
                SET crawl_interval_seconds = ?,
                    last_crawled_at = now(),
                    consecutive_unchanged = consecutive_unchanged + 1
                WHERE id = ?
                """, (int) nextInterval.toSeconds(), boardId);
    }

    /** Records a failure without touching the validators — they are still the best we have. */
    public void recordFailure(UUID boardId, Duration nextInterval) {
        jdbc.update("""
                UPDATE company_board
                SET crawl_interval_seconds = ?,
                    last_crawled_at = now()
                WHERE id = ?
                """, (int) nextInterval.toSeconds(), boardId);
    }

    public void setEnabled(UUID boardId, boolean enabled) {
        jdbc.update("UPDATE company_board SET enabled = ? WHERE id = ?", enabled, boardId);
    }
}
