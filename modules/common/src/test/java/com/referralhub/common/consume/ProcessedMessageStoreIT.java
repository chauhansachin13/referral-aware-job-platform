package com.referralhub.common.consume;

import static org.assertj.core.api.Assertions.assertThat;

import com.referralhub.common.testing.Databases;
import com.referralhub.common.testing.RequiresDocker;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@RequiresDocker
class ProcessedMessageStoreIT {

    private static JdbcTemplate jdbc;
    private static ProcessedMessageStore store;

    @BeforeAll
    static void migrate() {
        DataSource ds = Databases.migrated("classpath:db/migration/common");
        jdbc = new JdbcTemplate(ds);
        store = new ProcessedMessageStore(jdbc);
    }

    @BeforeEach
    void clean() {
        Databases.truncate(jdbc, "processed_message");
    }

    @Test
    @DisplayName("the first claim wins and every later claim of the same key is refused")
    void claimIsIdempotent() {
        assertThat(store.claim("dedup", "evt-1")).isTrue();
        assertThat(store.claim("dedup", "evt-1")).isFalse();
        assertThat(store.wasProcessed("dedup", "evt-1")).isTrue();
    }

    @Test
    @DisplayName("consumer groups do not share an inbox")
    void groupsAreIndependent() {
        assertThat(store.claim("dedup", "evt-1")).isTrue();
        assertThat(store.claim("search-indexer", "evt-1")).isTrue();
    }

    @Test
    @DisplayName("exactly one of 16 racing consumers claims the same key")
    void onlyOneWinnerUnderRace() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Boolean>> attempts = IntStream.range(0, 16)
                    .<Callable<Boolean>>mapToObj(i -> () -> store.claim("dedup", "hot-key"))
                    .toList();
            List<Future<Boolean>> results = pool.invokeAll(attempts);

            long winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the purge drops old keys and keeps recent ones")
    void purgeRespectsCutoff() {
        store.claim("dedup", "old");
        jdbc.update("UPDATE processed_message SET processed_at = ? WHERE message_key = 'old'",
                java.sql.Timestamp.from(Instant.now().minus(40, ChronoUnit.DAYS)));
        store.claim("dedup", "fresh");

        assertThat(store.purgeBefore(Instant.now().minus(30, ChronoUnit.DAYS))).isEqualTo(1);
        assertThat(store.wasProcessed("dedup", "fresh")).isTrue();
        assertThat(store.wasProcessed("dedup", "old")).isFalse();
    }
}
