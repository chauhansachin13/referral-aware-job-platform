package com.referralhub.ingestion.raw;

import com.referralhub.common.ids.Ids;
import com.referralhub.ingestion.adapter.ParsedPosting;
import com.referralhub.ingestion.board.CompanyBoard;
import com.referralhub.ingestion.fetch.ContentHasher;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Raw response bodies and the per-source postings parsed out of them. */
@Repository
public class RawPostingStore {

    private final JdbcTemplate jdbc;

    public RawPostingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Persists the response body before anything tries to understand it.
     *
     * <p>Called first, deliberately: if the adapter throws on a shape it has never seen, the
     * bytes that broke it are already durable and the fix can be replayed offline.
     */
    public UUID savePayload(UUID boardId, int httpStatus, String rawHash, String body) {
        UUID id = Ids.next();
        jdbc.update("""
                INSERT INTO raw_payload (id, board_id, http_status, raw_hash, byte_size, body)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, boardId, httpStatus, rawHash, body.length(), body);
        return id;
    }

    /**
     * Upserts every posting from one crawl and reports which of them actually changed.
     *
     * <p>The existing hashes are read in a single query rather than per posting: a board with
     * 800 roles would otherwise turn one crawl into 1600 round trips, and the crawler would
     * spend more time on latency than on parsing.
     */
    public List<PostingUpsert> upsertAll(CompanyBoard board, List<ParsedPosting> postings) {
        if (postings.isEmpty()) {
            return List.of();
        }

        Map<String, String> existingHashes = new HashMap<>();
        jdbc.query("SELECT external_id, content_hash FROM raw_posting WHERE board_id = ?",
                rs -> {
                    existingHashes.put(rs.getString("external_id"), rs.getString("content_hash"));
                }, board.id());

        List<PostingUpsert> results = new ArrayList<>(postings.size());
        for (ParsedPosting posting : postings) {
            String hash = ContentHasher.posting(posting);
            String previous = existingHashes.get(posting.externalId());
            boolean firstSeen = previous == null;
            boolean changed = firstSeen || !previous.equals(hash);

            if (!changed) {
                // Still live on the board; refresh the liveness timestamp and nothing else.
                jdbc.update("""
                        UPDATE raw_posting SET last_seen_at = now(), closed_at = NULL
                        WHERE source = ? AND external_id = ?
                        """, board.source(), posting.externalId());
                UUID id = jdbc.queryForObject(
                        "SELECT id FROM raw_posting WHERE source = ? AND external_id = ?",
                        UUID.class, board.source(), posting.externalId());
                results.add(new PostingUpsert(id, posting.externalId(), posting.title(), hash, false, false));
                continue;
            }

            UUID id = Ids.next();
            UUID stored = jdbc.queryForObject("""
                    INSERT INTO raw_posting
                        (id, board_id, company_id, source, external_id, title, description_html,
                         location, remote, department, apply_url, posted_at, content_hash, raw_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (source, external_id) DO UPDATE SET
                        title = excluded.title,
                        description_html = excluded.description_html,
                        location = excluded.location,
                        remote = excluded.remote,
                        department = excluded.department,
                        apply_url = excluded.apply_url,
                        posted_at = excluded.posted_at,
                        content_hash = excluded.content_hash,
                        raw_json = excluded.raw_json,
                        last_seen_at = now(),
                        closed_at = NULL
                    RETURNING id
                    """,
                    UUID.class,
                    id, board.id(), board.companyId(), board.source(), posting.externalId(),
                    posting.title(), posting.descriptionHtml(), posting.location(), posting.remote(),
                    posting.department(), posting.applyUrl(),
                    posting.postedAt() == null ? null : Timestamp.from(posting.postedAt()),
                    hash, posting.rawJson());

            results.add(new PostingUpsert(stored, posting.externalId(), posting.title(), hash, true, firstSeen));
        }
        return results;
    }

    /**
     * Closes postings that were on the board last time and are not on it now.
     *
     * <p>Guarded by the caller: this is only ever run after a parse that produced postings, so a
     * board that returns an empty array because of an outage cannot silently close a company's
     * entire catalogue.
     */
    public int closeMissing(UUID boardId, Collection<String> liveExternalIds) {
        if (liveExternalIds.isEmpty()) {
            return 0;
        }
        return jdbc.update("""
                UPDATE raw_posting
                SET closed_at = now()
                WHERE board_id = ?
                  AND closed_at IS NULL
                  AND external_id <> ALL (?)
                """, boardId, liveExternalIds.toArray(String[]::new));
    }

    public void logCrawl(UUID boardId, String outcome, Integer httpStatus, long elapsedMillis,
                         int postingsSeen, int postingsChanged, String error) {
        jdbc.update("""
                INSERT INTO crawl_log
                    (id, board_id, outcome, http_status, elapsed_ms, postings_seen,
                     postings_changed, error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, Ids.next(), boardId, outcome, httpStatus, (int) elapsedMillis,
                postingsSeen, postingsChanged,
                error == null ? null : error.substring(0, Math.min(error.length(), 1000)));
    }

    private static final org.springframework.jdbc.core.RowMapper<RawPostingRecord> RECORD_MAPPER =
            (rs, rowNum) -> new RawPostingRecord(
                    rs.getObject("id", UUID.class),
                    rs.getObject("board_id", UUID.class),
                    rs.getObject("company_id", UUID.class),
                    rs.getString("source"),
                    rs.getString("external_id"),
                    rs.getString("title"),
                    rs.getString("description_html"),
                    rs.getString("location"),
                    rs.getBoolean("remote"),
                    rs.getString("department"),
                    rs.getString("apply_url"),
                    rs.getTimestamp("posted_at") == null ? null : rs.getTimestamp("posted_at").toInstant(),
                    rs.getString("content_hash"),
                    rs.getTimestamp("first_seen_at").toInstant(),
                    rs.getTimestamp("last_seen_at").toInstant(),
                    rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant());

    /** Read access for downstream modules; they never touch the table directly. */
    public java.util.Optional<RawPostingRecord> findById(UUID id) {
        return jdbc.query("SELECT * FROM raw_posting WHERE id = ?", RECORD_MAPPER, id)
                .stream().findFirst();
    }

    public List<RawPostingRecord> findOpenByCompany(UUID companyId, int limit) {
        return jdbc.query("""
                SELECT * FROM raw_posting
                WHERE company_id = ? AND closed_at IS NULL
                ORDER BY last_seen_at DESC
                LIMIT ?
                """, RECORD_MAPPER, companyId, limit);
    }

    /**
     * Whether the board's <em>most recent</em> payload had this exact hash.
     *
     * <p>Deliberately the latest payload and not any historical one. Matching against history
     * looks equivalent and is not: a board that publishes A, then B, then reverts to A would
     * match the stored A, skip the parse, and leave the database describing B — with a posting
     * that has since been withdrawn still marked open. Reverts are common (a role is pulled and
     * reinstated, a CMS rolls back a bad deploy), so this is a real sequence, not a contrived one.
     *
     * <p>The short circuit is still worth having: the overwhelmingly common case is a board that
     * has not changed since the last crawl, which is exactly what this now tests.
     */
    public boolean lastPayloadHasHash(UUID boardId, String rawHash) {
        return jdbc.queryForList("""
                SELECT raw_hash FROM raw_payload
                WHERE board_id = ?
                ORDER BY fetched_at DESC, id DESC
                LIMIT 1
                """, String.class, boardId)
                .stream().findFirst()
                .map(rawHash::equals)
                .orElse(false);
    }

    public int countOpen(UUID boardId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM raw_posting WHERE board_id = ? AND closed_at IS NULL",
                Integer.class, boardId);
        return n == null ? 0 : n;
    }
}
