package com.referralhub.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.referralhub.common.events.JobIngested;
import com.referralhub.common.ids.Ids;
import com.referralhub.common.testing.Databases;
import com.referralhub.common.testing.RequiresDocker;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises the outbox against a real Postgres.
 *
 * <p>The claim query's whole value is in {@code FOR UPDATE SKIP LOCKED}, which no in-memory
 * database implements faithfully. A mock-based test here would assert that we can call
 * {@code jdbc.query} with a string, which is not the thing that can break.
 */
@Tag("integration")
@RequiresDocker
class OutboxStoreIT {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static OutboxStore store;
    private static TransactionTemplate tx;

    @BeforeAll
    static void migrate() {
        dataSource = Databases.migrated("classpath:db/migration/common");
        jdbc = new JdbcTemplate(dataSource);
        store = new OutboxStore(jdbc);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void clean() {
        Databases.truncate(jdbc, "outbox_event", "processed_message");
    }

    private static JobIngested event(String title) {
        return new JobIngested(Ids.next(), Ids.next(), "greenhouse", "ext-" + title,
                title, "hash-" + title, Instant.now());
    }

    @Test
    @DisplayName("an appended event is claimable and its payload survives the jsonb round trip")
    void appendThenClaim() {
        JobIngested e = event("Staff Engineer");
        tx.executeWithoutResult(status -> store.append(e));

        List<OutboxRecord> claimed = tx.execute(status -> store.claimBatch(10, 5));

        assertThat(claimed).hasSize(1);
        OutboxRecord record = claimed.get(0);
        assertThat(record.eventType()).isEqualTo("job.ingested");
        assertThat(record.topic()).isEqualTo("jobs.ingested.v1");
        assertThat(record.partitionKey()).isEqualTo(e.rawPostingId().toString());
        assertThat(record.payload()).contains("Staff Engineer");
        assertThat(record.attempts()).isZero();
    }

    @Test
    @DisplayName("a rolled-back business transaction leaves no event behind")
    void rollbackLosesTheEvent() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            store.append(event("Never Happened"));
            throw new IllegalStateException("business rule violated");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(store.countPending()).isZero();
    }

    @Test
    @DisplayName("two concurrent relays claim disjoint batches — SKIP LOCKED, not coordination")
    void concurrentRelaysDoNotOverlap() throws Exception {
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < 40; i++) {
                store.append(event("Job " + i));
            }
        });

        CyclicBarrier bothInsideTransaction = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<List<OutboxRecord>> first = new AtomicReference<>();
        AtomicReference<List<OutboxRecord>> second = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (AtomicReference<List<OutboxRecord>> sink : List.of(first, second)) {
                pool.submit(() -> {
                    try {
                        tx.executeWithoutResult(status -> {
                            sink.set(store.claimBatch(20, 5));
                            try {
                                // Hold the locks until the other transaction has also claimed.
                                bothInsideTransaction.await(20, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        });
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(first.get()).hasSize(20);
        assertThat(second.get()).hasSize(20);

        Set<UUID> ids = new HashSet<>();
        first.get().forEach(r -> ids.add(r.id()));
        second.get().forEach(r -> ids.add(r.id()));
        assertThat(ids).as("no row may be claimed by both relays").hasSize(40);
    }

    @Test
    @DisplayName("published rows drop out of the pending set; failures raise the attempt counter")
    void publishAndFailBookkeeping() {
        tx.executeWithoutResult(status -> {
            store.append(event("A"));
            store.append(event("B"));
        });

        List<OutboxRecord> claimed = tx.execute(status -> store.claimBatch(10, 5));
        assertThat(claimed).hasSize(2);

        store.markPublished(List.of(claimed.get(0).id()));
        store.markFailed(claimed.get(1).id(), "broker unreachable");

        assertThat(store.countPending()).isEqualTo(1);

        List<OutboxRecord> reclaimed = tx.execute(status -> store.claimBatch(10, 5));
        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("rows past the attempt ceiling stop being retried and are counted as poisoned")
    void poisonedRowsAreParked() {
        tx.executeWithoutResult(status -> store.append(event("Doomed")));
        List<OutboxRecord> first = tx.execute(status -> store.claimBatch(1, 3));
        UUID id = first.get(0).id();

        for (int i = 0; i < 3; i++) {
            store.markFailed(id, "still broken");
        }

        List<OutboxRecord> afterCeiling = tx.execute(status -> store.claimBatch(10, 3));
        assertThat(afterCeiling).isEmpty();
        assertThat(store.countPoisoned(3)).isEqualTo(1);
        assertThat(store.countPending()).isEqualTo(1);
    }

    @Test
    @DisplayName("the reaper deletes published rows and never touches pending ones")
    void reaperOnlyDeletesPublished() {
        tx.executeWithoutResult(status -> {
            store.append(event("Published"));
            store.append(event("Pending"));
        });
        List<OutboxRecord> claimed = tx.execute(status -> store.claimBatch(10, 5));
        store.markPublished(List.of(claimed.get(0).id()));

        int deleted = store.deletePublishedBefore(Instant.now().plusSeconds(1));

        assertThat(deleted).isEqualTo(1);
        assertThat(store.countPending()).isEqualTo(1);
    }
}
