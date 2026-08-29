package com.referralhub.ingestion.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.referralhub.common.testing.Databases;
import com.referralhub.common.testing.PlatformProperties;
import com.referralhub.common.testing.RequiresDocker;
import com.referralhub.ingestion.IngestionTestApplication;
import com.referralhub.ingestion.adapter.GreenhouseAdapter;
import com.referralhub.ingestion.adapter.ParsedPosting;
import com.referralhub.ingestion.adapter.SourceAdapter;
import com.referralhub.ingestion.board.BoardStore;
import com.referralhub.ingestion.board.CompanyBoard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The crawl pipeline against a real Postgres, a real Redis and a real HTTP server.
 *
 * <p>The sequence is the point. Each step asserts on what the <em>next</em> crawl costs, because
 * every optimisation in this module is about the cost of the second and thousandth crawl of a
 * board that has not changed:
 *
 * <ol>
 *   <li>first crawl: full download, postings stored, events emitted;</li>
 *   <li>identical board, valid ETag: 304, no payload row, no events;</li>
 *   <li>identical bytes but no ETag offered: raw-hash short circuit, no parse, no events;</li>
 *   <li>cosmetically reordered response: new bytes, but no events;</li>
 *   <li>a genuinely new posting: exactly one new event.</li>
 * </ol>
 */
@Tag("integration")
@RequiresDocker
@SpringBootTest(classes = {IngestionTestApplication.class, CrawlPipelineIT.StubBoardConfig.class},
        properties = {
                "referralhub.outbox.relay-enabled=false",
                "referralhub.ingestion.crawl-enabled=false",
                "spring.flyway.locations=classpath:db/migration/common,classpath:db/migration/ingestion",
                "spring.kafka.bootstrap-servers=localhost:1"
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrawlPipelineIT {

    private static final AtomicReference<String> BASE_URL = new AtomicReference<>();
    private static final AtomicReference<Mode> MODE = new AtomicReference<>(Mode.NORMAL);
    private static final AtomicInteger BODIES_SERVED = new AtomicInteger();

    private enum Mode {
        /** Honours If-None-Match and serves the two-posting board. */
        NORMAL,
        /** Serves the same bytes but never answers 304 — exercises the raw-hash short circuit. */
        NO_VALIDATORS,
        /** Same postings, different order and whitespace: cosmetic churn only. */
        REORDERED,
        /** One extra posting: a real change. */
        EXTRA_POSTING
    }

    private static HttpServer server;
    private static UUID boardId;

    @Autowired
    private CrawlPipeline pipeline;
    @Autowired
    private BoardStore boards;
    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PlatformProperties.postgres(registry);
        PlatformProperties.redis(registry);
    }

    @BeforeAll
    static void startStubBoard() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/board", CrawlPipelineIT::handle);
        server.start();
        BASE_URL.set("http://127.0.0.1:" + server.getAddress().getPort() + "/board");
    }

    @AfterAll
    static void stopStubBoard() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void ensureBoardRegistered() {
        if (boardId != null) {
            return;
        }
        Databases.truncateAll(jdbc);
        UUID companyId = boards.upsertCompany("Acme", "acme", "acme.com", null);
        boardId = boards.registerBoard(companyId, "stub", "acme", Duration.ofHours(1));
    }

    private static void handle(HttpExchange exchange) throws IOException {
        Mode mode = MODE.get();
        String etag = switch (mode) {
            case NORMAL, NO_VALIDATORS -> "\"board-v1\"";
            case REORDERED -> "\"board-v2\"";
            case EXTRA_POSTING -> "\"board-v3\"";
        };

        if (mode == Mode.NORMAL
                && etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            exchange.getResponseHeaders().set("ETag", etag);
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
        }

        byte[] bytes = bodyFor(mode).getBytes(StandardCharsets.UTF_8);
        BODIES_SERVED.incrementAndGet();
        if (mode != Mode.NO_VALIDATORS) {
            exchange.getResponseHeaders().set("ETag", etag);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String job(String id, String title, String location, String content) {
        return """
                {"id":%s,"title":"%s","updated_at":"2026-08-20T14:02:11Z",
                 "location":{"name":"%s"},"absolute_url":"https://example.test/%s",
                 "departments":[{"name":"Engineering"}],"content":"%s"}
                """.formatted(id, title, location, id, content);
    }

    private static String bodyFor(Mode mode) {
        String payments = job("1001", "Senior Software Engineer, Payments", "San Francisco, CA",
                "Own ledger correctness.");
        String infra = job("1002", "Staff Engineer, Infrastructure", "Remote - United States",
                "Own the Kubernetes platform.");
        String ml = job("1003", "Machine Learning Engineer", "New York, NY", "Own ranking models.");

        return switch (mode) {
            case NORMAL, NO_VALIDATORS -> "{\"jobs\":[" + payments + "," + infra + "]}";
            // Same two postings, opposite order, extra whitespace: nothing a seeker would notice.
            case REORDERED -> "{\"jobs\":  [" + infra + " , " + payments + "  ]}";
            case EXTRA_POSTING -> "{\"jobs\":[" + payments + "," + infra + "," + ml + "]}";
        };
    }

    private int outboxRows() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class);
        return n == null ? 0 : n;
    }

    private int payloadRows() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM raw_payload", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    @Order(1)
    @DisplayName("the first crawl stores the raw payload, the postings, and one event per posting")
    void firstCrawlIngestsEverything() {
        MODE.set(Mode.NORMAL);

        CrawlOutcome outcome = pipeline.crawl(boardId);

        assertThat(outcome.status()).isEqualTo(CrawlOutcome.Status.CHANGED);
        assertThat(outcome.postingsSeen()).isEqualTo(2);
        assertThat(outcome.postingsChanged()).isEqualTo(2);
        assertThat(payloadRows()).isEqualTo(1);
        assertThat(outboxRows()).isEqualTo(2);

        CompanyBoard board = boards.findById(boardId).orElseThrow();
        assertThat(board.etag()).isEqualTo("\"board-v1\"");
        assertThat(board.lastContentHash()).isNotBlank();
        assertThat(board.consecutiveUnchanged()).isZero();
    }

    @Test
    @Order(2)
    @DisplayName("an unchanged board answers 304 and costs no payload row and no event")
    void secondCrawlIsNotModified() {
        MODE.set(Mode.NORMAL);
        int served = BODIES_SERVED.get();

        CrawlOutcome outcome = pipeline.crawl(boardId);

        assertThat(outcome.status()).isEqualTo(CrawlOutcome.Status.NOT_MODIFIED);
        assertThat(BODIES_SERVED.get()).as("a 304 transfers no body").isEqualTo(served);
        assertThat(payloadRows()).isEqualTo(1);
        assertThat(outboxRows()).isEqualTo(2);
        assertThat(boards.findById(boardId).orElseThrow().consecutiveUnchanged()).isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("identical bytes from a board that offers no validators still skip the parse")
    void identicalBytesShortCircuitOnRawHash() {
        MODE.set(Mode.NO_VALIDATORS);

        CrawlOutcome outcome = pipeline.crawl(boardId);

        assertThat(outcome.status()).isEqualTo(CrawlOutcome.Status.UNCHANGED);
        assertThat(outcome.postingsSeen()).isZero();
        assertThat(payloadRows()).as("no second copy of identical bytes").isEqualTo(1);
        assertThat(outboxRows()).isEqualTo(2);
    }

    @Test
    @Order(4)
    @DisplayName("a reordered, reformatted response is new bytes but emits no events")
    void cosmeticChurnEmitsNothing() {
        MODE.set(Mode.REORDERED);

        CrawlOutcome outcome = pipeline.crawl(boardId);

        assertThat(outcome.status()).isEqualTo(CrawlOutcome.Status.UNCHANGED);
        assertThat(outcome.postingsSeen()).isEqualTo(2);
        assertThat(outcome.postingsChanged()).isZero();
        assertThat(payloadRows()).as("the bytes did differ, so they are stored").isEqualTo(2);
        assertThat(outboxRows()).as("but nothing a seeker would notice changed").isEqualTo(2);
    }

    @Test
    @Order(5)
    @DisplayName("a genuinely new posting produces exactly one new event")
    void realChangeEmitsOneEvent() {
        MODE.set(Mode.EXTRA_POSTING);

        CrawlOutcome outcome = pipeline.crawl(boardId);

        assertThat(outcome.status()).isEqualTo(CrawlOutcome.Status.CHANGED);
        assertThat(outcome.postingsSeen()).isEqualTo(3);
        assertThat(outcome.postingsChanged()).isEqualTo(1);
        assertThat(outboxRows()).isEqualTo(3);

        List<String> titles = jdbc.queryForList(
                "SELECT title FROM raw_posting WHERE closed_at IS NULL ORDER BY title", String.class);
        assertThat(titles).containsExactly(
                "Machine Learning Engineer",
                "Senior Software Engineer, Payments",
                "Staff Engineer, Infrastructure");
    }

    @Test
    @Order(6)
    @DisplayName("a board that reverts to an earlier body is re-parsed, and the lost posting closed")
    void revertingToAnEarlierBodyIsStillProcessed() {
        MODE.set(Mode.NORMAL);
        // The board returns to the exact two-posting body it served in step 1, after having
        // served a three-posting body in step 5.
        //
        // This is the case that caught a real bug: the raw-hash short circuit originally asked
        // "have we ever stored these bytes?", which is true here, so the crawl was skipped and
        // the withdrawn third posting stayed open forever. The question has to be "were these
        // the *last* bytes we stored?" — see RawPostingStore.lastPayloadHasHash.
        //
        // The validators are cleared so the stub serves a body rather than a 304; the point of
        // the test is what happens once the bytes arrive.
        jdbc.update("UPDATE company_board SET etag = NULL, last_content_hash = NULL WHERE id = ?", boardId);

        CrawlOutcome outcome = pipeline.crawl(boardId);

        assertThat(outcome.status()).isEqualTo(CrawlOutcome.Status.CHANGED);
        Integer closed = jdbc.queryForObject(
                "SELECT count(*) FROM raw_posting WHERE closed_at IS NOT NULL", Integer.class);
        assertThat(closed).isEqualTo(1);
        Integer total = jdbc.queryForObject("SELECT count(*) FROM raw_posting", Integer.class);
        assertThat(total).as("closed, not deleted").isEqualTo(3);
    }

    /** Points a Greenhouse-shaped adapter at the local stub board. */
    @TestConfiguration
    static class StubBoardConfig {

        @Bean
        SourceAdapter stubAdapter() {
            GreenhouseAdapter delegate = new GreenhouseAdapter();
            return new SourceAdapter() {
                @Override
                public String source() {
                    return "stub";
                }

                @Override
                public URI boardUri(CompanyBoard board) {
                    return URI.create(BASE_URL.get());
                }

                @Override
                public List<ParsedPosting> parse(String body, CompanyBoard board) {
                    return delegate.parse(body, board);
                }
            };
        }
    }
}
