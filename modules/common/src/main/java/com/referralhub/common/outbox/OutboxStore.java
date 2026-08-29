package com.referralhub.common.outbox;

import com.referralhub.common.events.DomainEvent;
import com.referralhub.common.ids.Ids;
import com.referralhub.common.json.Json;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The outbox table, spoken to directly in SQL.
 *
 * <p>{@link #append} must run inside the caller's transaction — that is the entire contract of
 * the pattern. If the business write commits, the event row commits with it; if it rolls back,
 * the event never existed. There is no code path that publishes to Kafka from a service method.
 */
@Repository
public class OutboxStore {

    private static final RowMapper<OutboxRecord> MAPPER = (rs, rowNum) -> new OutboxRecord(
            rs.getObject("id", UUID.class),
            rs.getString("aggregate_type"),
            rs.getObject("aggregate_id", UUID.class),
            rs.getString("event_type"),
            rs.getString("topic"),
            rs.getString("partition_key"),
            rs.getString("payload"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getInt("attempts"));

    private final JdbcTemplate jdbc;

    public OutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Writes the event into the outbox using the caller's transaction. Returns the row id. */
    public UUID append(DomainEvent event) {
        UUID id = Ids.next();
        jdbc.update("""
                INSERT INTO outbox_event
                    (id, aggregate_type, aggregate_id, event_type, topic, partition_key,
                     payload, occurred_at, attempts)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 0)
                """,
                id,
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.topic(),
                event.aggregateId().toString(),
                Json.write(event),
                Timestamp.from(Instant.now()));
        return id;
    }

    /**
     * Claims a batch of unpublished rows for this relay pass.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what lets every application replica run its own relay
     * without coordination: each transaction takes a disjoint set of rows and no row is ever
     * published twice concurrently.
     */
    public List<OutboxRecord> claimBatch(int batchSize, int maxAttempts) {
        return jdbc.query("""
                SELECT id, aggregate_type, aggregate_id, event_type, topic, partition_key,
                       payload::text AS payload, occurred_at, attempts
                FROM outbox_event
                WHERE published_at IS NULL
                  AND attempts < ?
                ORDER BY occurred_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, MAPPER, maxAttempts, batchSize);
    }

    public void markPublished(List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "UPDATE outbox_event SET published_at = now(), last_error = NULL WHERE id = ?",
                ids.stream().map(id -> new Object[] {id}).toList());
    }

    public void markFailed(UUID id, String error) {
        jdbc.update("""
                UPDATE outbox_event
                SET attempts = attempts + 1,
                    last_error = ?
                WHERE id = ?
                """, error == null ? null : error.substring(0, Math.min(error.length(), 1000)), id);
    }

    public int countPending() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Integer.class);
        return n == null ? 0 : n;
    }

    /** Rows stuck at the attempt ceiling; surfaced as a metric so they page someone. */
    public int countPoisoned(int maxAttempts) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NULL AND attempts >= ?",
                Integer.class, maxAttempts);
        return n == null ? 0 : n;
    }

    public int deletePublishedBefore(Instant cutoff) {
        return jdbc.update(
                "DELETE FROM outbox_event WHERE published_at IS NOT NULL AND published_at < ?",
                Timestamp.from(cutoff));
    }
}
