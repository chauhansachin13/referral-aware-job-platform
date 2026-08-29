package com.referralhub.common.consume;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The consumer-side half of exactly-once-in-effect processing.
 *
 * <p>The outbox gives at-least-once delivery, so a consumer will occasionally see the same
 * record twice (rebalance, redelivery after a crash between "handled" and "committed"). A row
 * in this table, written in the same transaction as the handler's own writes, is what makes the
 * second delivery a no-op.
 */
@Repository
public class ProcessedMessageStore {

    private final JdbcTemplate jdbc;

    public ProcessedMessageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims a message for processing.
     *
     * @return {@code true} if this is the first time the group has seen the key, {@code false}
     *         if it was already processed and the caller should skip its handler entirely.
     */
    public boolean claim(String consumerGroup, String messageKey) {
        int inserted = jdbc.update("""
                INSERT INTO processed_message (consumer_group, message_key, processed_at)
                VALUES (?, ?, ?)
                ON CONFLICT (consumer_group, message_key) DO NOTHING
                """, consumerGroup, messageKey, Timestamp.from(Instant.now()));
        return inserted == 1;
    }

    public boolean wasProcessed(String consumerGroup, String messageKey) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM processed_message
                WHERE consumer_group = ? AND message_key = ?
                """, Integer.class, consumerGroup, messageKey);
        return n != null && n > 0;
    }

    public int purgeBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM processed_message WHERE processed_at < ?",
                Timestamp.from(cutoff));
    }
}
