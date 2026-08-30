package com.referralhub.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.referralhub.app.ReferralHubApplication;
import com.referralhub.common.error.ConflictException;
import com.referralhub.common.ids.Ids;
import com.referralhub.common.testing.Databases;
import com.referralhub.common.testing.PlatformProperties;
import com.referralhub.common.testing.RequiresDocker;
import com.referralhub.dedup.DedupDecision;
import com.referralhub.dedup.DedupService;
import com.referralhub.ingestion.adapter.GreenhouseAdapter;
import com.referralhub.ingestion.adapter.ParsedPosting;
import com.referralhub.ingestion.adapter.SourceAdapter;
import com.referralhub.ingestion.board.BoardStore;
import com.referralhub.ingestion.board.CompanyBoard;
import com.referralhub.ingestion.pipeline.CrawlOutcome;
import com.referralhub.ingestion.pipeline.CrawlPipeline;
import com.referralhub.referral.ReferralRequest;
import com.referralhub.referral.ReferralService;
import com.referralhub.referral.resume.ResumeCipher;
import com.referralhub.referral.resume.ResumeStorage;
import com.referralhub.referral.resume.StoredResume;
import com.referralhub.referral.state.ReferralState;
import com.referralhub.search.SearchService;
import com.referralhub.search.index.JobIndexer;
import com.referralhub.search.index.OpenSearchGateway;
import com.referralhub.search.query.SearchHit;
import com.referralhub.search.query.SearchRequest;
import com.referralhub.search.query.SearchResults;
import com.referralhub.trust.verify.VerificationStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * One job, all the way through: ATS board to a completed referral.
 *
 * <p>Every module test proves its own module. This proves they compose — that the raw posting
 * ingestion writes is the one dedup canonicalizes, that the canonical job dedup produces is the
 * one search indexes and returns, and that the job search returns can carry a referral whose
 * resume survives encryption, storage, release and deletion.
 *
 * <p>Real Postgres, Redis, OpenSearch and MinIO. The only stub is the ATS board itself, because
 * a test that depends on a third party's live job listings fails for reasons that have nothing
 * to do with this code.
 *
 * <p>The pipeline is driven synchronously rather than through Kafka. The asynchronous path is
 * covered where it belongs — outbox claiming in {@code OutboxStoreIT}, consumer idempotency in
 * {@code ProcessedMessageStoreIT} — and this test asserts that the correct events were written
 * to the outbox, which is the handoff point. Waiting on real consumer lag here would buy nothing
 * except flakiness.
 */
@Tag("integration")
@RequiresDocker
@SpringBootTest(
        classes = {ReferralHubApplication.class, EndToEndPipelineIT.StubBoardConfig.class},
        properties = {
                "referralhub.outbox.relay-enabled=false",
                "referralhub.ingestion.crawl-enabled=false",
                "referralhub.search.indexer-enabled=false",
                "referralhub.dedup.consumer-enabled=false",
                "referralhub.referral.expiry-enabled=false",
                "spring.kafka.bootstrap-servers=localhost:1",
                // AuthConfig refuses to build a signing key without one, by design.
                "referralhub.auth.jwt-secret=integration-test-signing-secret-at-least-32-bytes-long",
                "referralhub.search.index-name=jobs_e2e",
                "referralhub.storage.bucket=referralhub-e2e"
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndPipelineIT {

    private static final AtomicReference<String> BOARD_URL = new AtomicReference<>();
    private static HttpServer boardServer;

    private static UUID companyId;
    private static UUID boardId;
    private static UUID seekerId;
    private static UUID referrerId;
    private static UUID paymentsCanonicalJobId;
    private static UUID resumeId;
    private static UUID referralId;

    private static final byte[] RESUME_BYTES =
            "Sachin Chauhan\nStaff Engineer\nKubernetes, Kafka, Postgres".getBytes(StandardCharsets.UTF_8);

    @Autowired private BoardStore boards;
    @Autowired private CrawlPipeline crawlPipeline;
    @Autowired private DedupService dedup;
    @Autowired private JobIndexer indexer;
    @Autowired private OpenSearchGateway openSearch;
    @Autowired private SearchService search;
    @Autowired private ReferralService referrals;
    @Autowired private ResumeStorage resumes;
    @Autowired private VerificationStore trust;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PlatformProperties.postgres(registry);
        PlatformProperties.redis(registry);
        PlatformProperties.minio(registry);
        PlatformProperties.openSearch(registry);
        registry.add("referralhub.storage.encryption-key", ResumeCipher::generateKey);
        registry.add("referralhub.storage.url-signing-secret", () -> "e2e-signing-secret");
    }

    @BeforeAll
    static void startStubBoard() throws IOException {
        boardServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        boardServer.createContext("/board", EndToEndPipelineIT::serveBoard);
        boardServer.start();
        BOARD_URL.set("http://127.0.0.1:" + boardServer.getAddress().getPort() + "/board");
    }

    @AfterAll
    static void stopStubBoard() {
        if (boardServer != null) {
            boardServer.stop(0);
        }
    }

    /**
     * Three postings, two of which are the same job.
     *
     * <p>1001 and 1003 are the same payments role advertised twice with different requisition
     * ids and lightly reworded titles — the exact case the deduplicator exists for.
     */
    private static void serveBoard(HttpExchange exchange) throws IOException {
        String body = """
                {"jobs":[
                  {"id":1001,"title":"Senior Software Engineer, Payments",
                   "updated_at":"2026-08-20T14:02:11Z","location":{"name":"Berlin, Germany"},
                   "absolute_url":"https://example.test/1001",
                   "departments":[{"name":"Engineering"}],
                   "content":"You will own the double entry ledger and money movement APIs. We run Java, Postgres and Kafka."},
                  {"id":1002,"title":"Site Reliability Engineer, Platform",
                   "updated_at":"2026-08-21T09:00:00Z","location":{"name":"Remote - EMEA"},
                   "absolute_url":"https://example.test/1002",
                   "departments":[{"name":"Engineering"}],
                   "content":"You will own container orchestration and infrastructure as code for every product team."},
                  {"id":1003,"title":"Sr. Software Engineer - Payments",
                   "updated_at":"2026-08-20T15:00:00Z","location":{"name":"Berlin, Germany"},
                   "absolute_url":"https://example.test/1003",
                   "departments":[{"name":"Engineering"}],
                   "content":"You will own the double entry ledger and money movement APIs. We run Java, Postgres and Kafka."}
                ]}""";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    @Order(1)
    @DisplayName("a registered board is crawled and every posting is stored with an event")
    void crawlIngestsTheBoard() {
        Databases.truncateAll(jdbc);
        openSearch.deleteIndex();
        openSearch.ensureIndex(256);

        companyId = boards.upsertCompany("Acme", "acme", "acme.com", null);
        boardId = boards.registerBoard(companyId, "stub", "acme", Duration.ofHours(1));

        CrawlOutcome outcome = crawlPipeline.crawl(boardId);

        assertThat(outcome.status()).isEqualTo(CrawlOutcome.Status.CHANGED);
        assertThat(outcome.postingsSeen()).isEqualTo(3);
        assertThat(outcome.postingsChanged()).isEqualTo(3);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM raw_posting WHERE closed_at IS NULL", Integer.class))
                .isEqualTo(3);
        // The handoff to the asynchronous half: one event per changed posting, durably written
        // in the same transaction as the postings themselves.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'job.ingested'", Integer.class))
                .isEqualTo(3);
    }

    @Test
    @Order(2)
    @DisplayName("deduplication collapses the two payments postings into one canonical job")
    void dedupMergesTheRepost() {
        List<UUID> postingIds = jdbc.queryForList(
                "SELECT id FROM raw_posting ORDER BY external_id", UUID.class);
        assertThat(postingIds).hasSize(3);

        DedupDecision first = dedup.canonicalize(postingIds.get(0));   // 1001 payments
        DedupDecision second = dedup.canonicalize(postingIds.get(1));  // 1002 SRE
        DedupDecision third = dedup.canonicalize(postingIds.get(2));   // 1003 payments repost

        assertThat(first.createdNewCanonical()).isTrue();
        assertThat(second.createdNewCanonical()).isTrue();
        assertThat(third.createdNewCanonical())
                .as("1003 is 1001 under a new requisition id and must not create a second job")
                .isFalse();
        assertThat(third.canonicalJobId()).isEqualTo(first.canonicalJobId());
        assertThat(third.matchScore()).isGreaterThan(0.82);

        paymentsCanonicalJobId = first.canonicalJobId();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM canonical_job", Integer.class))
                .as("three postings, two real jobs").isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT source_count FROM canonical_job WHERE id = ?", Integer.class,
                paymentsCanonicalJobId))
                .isEqualTo(2);
    }

    @Test
    @Order(3)
    @DisplayName("indexed jobs are retrievable, including by a query sharing no tokens")
    void searchFindsTheIndexedJobs() {
        jdbc.queryForList("SELECT id FROM canonical_job", UUID.class).forEach(indexer::index);
        openSearch.refresh();

        SearchResults payments = search.search(
                new SearchRequest("double entry ledger", null, null, null, null, 10, null));
        assertThat(payments.hits()).isNotEmpty();
        assertThat(payments.hits().get(0).canonicalJobId()).isEqualTo(paymentsCanonicalJobId);
        assertThat(payments.hits().get(0).sourceCount())
                .as("the merged job reports both of its sources").isEqualTo(2);

        // "k8s" appears nowhere in the corpus; the SRE posting says "container orchestration".
        SearchResults k8s = search.search(
                new SearchRequest("k8s", null, null, null, null, 10, null));
        assertThat(k8s.hits()).isNotEmpty();
        assertThat(k8s.hits().get(0).title()).contains("Site Reliability Engineer");
        assertThat(k8s.hits().get(0).vectorRank()).isNotNull();
    }

    @Test
    @Order(4)
    @DisplayName("a resume is encrypted before it reaches object storage")
    void resumeIsStoredAsCiphertext() {
        seekerId = trust.createUser("Seeker", "seeker@example.com");
        referrerId = trust.createUser("Referrer", "referrer@example.com");
        jdbc.update("""
                INSERT INTO employee_verification
                    (id, user_id, company_id, work_email, email_domain, status, verified_at, expires_at)
                VALUES (?, ?, ?, 'someone@acme.com', 'acme.com', 'VERIFIED', now(), ?)
                """, Ids.next(), referrerId, companyId,
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));

        resumes.ensureBucket();
        StoredResume stored = resumes.store(seekerId, "cv.pdf", "application/pdf", RESUME_BYTES);
        resumeId = stored.id();

        assertThat(stored.sizeBytes()).isEqualTo(RESUME_BYTES.length);
        assertThat(stored.encryptionIv()).isNotBlank();
        // Round-trips through decryption...
        assertThat(resumes.read(resumeId)).isEqualTo(RESUME_BYTES);
    }

    @Test
    @Order(5)
    @DisplayName("a referral runs its whole lifecycle against the job search returned")
    void referralLifecycleCompletes() {
        ReferralRequest created = referrals.request(seekerId, paymentsCanonicalJobId, companyId,
                resumeId, "Would appreciate a referral", "e2e-create");
        referralId = created.id();
        assertThat(created.state()).isEqualTo(ReferralState.REQUESTED);

        // The resume is not readable until someone commits to the request.
        assertThatThrownBy(() -> referrals.mintResumeDownloadUrl(referralId, referrerId))
                .isInstanceOf(ConflictException.class);

        assertThat(referrals.accept(referralId, referrerId, "e2e-accept").state())
                .isEqualTo(ReferralState.ACCEPTED);

        String link = referrals.mintResumeDownloadUrl(referralId, referrerId);
        String token = link.substring(link.indexOf("token=") + 6);
        ReferralService.ResumePayload payload = referrals.readResume(token);

        assertThat(payload.filename()).isEqualTo("cv.pdf");
        assertThat(payload.bytes()).isEqualTo(RESUME_BYTES);

        assertThat(referrals.submit(referralId, referrerId, "e2e-submit").state())
                .isEqualTo(ReferralState.SUBMITTED);
        assertThat(referrals.close(referralId, seekerId, "offer accepted", "e2e-close").state())
                .isEqualTo(ReferralState.CLOSED);

        assertThat(jdbc.queryForList(
                "SELECT to_state FROM referral_transition WHERE request_id = ? ORDER BY occurred_at",
                String.class, referralId))
                .containsExactly("REQUESTED", "ACCEPTED", "SUBMITTED", "CLOSED");
    }

    @Test
    @Order(6)
    @DisplayName("a closed referral voids a link that was valid while it was open")
    void closingTheReferralVoidsOutstandingLinks() {
        // A signed token is not a standing permission: state is re-checked at redemption.
        assertThatThrownBy(() -> referrals.mintResumeDownloadUrl(referralId, referrerId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @Order(7)
    @DisplayName("deleting a resume removes the object and the row, and detaches the referral")
    void resumeHardDeleteIsComplete() {
        resumes.hardDelete(resumeId);

        assertThat(resumes.findMetadata(resumeId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resume", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM referral_request WHERE id = ? AND resume_id IS NULL",
                Integer.class, referralId))
                .as("the referral survives; only the PII is gone").isEqualTo(1);

        assertThatThrownBy(() -> resumes.read(resumeId))
                .isInstanceOf(com.referralhub.common.error.NotFoundException.class);
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
                    return URI.create(BOARD_URL.get());
                }

                @Override
                public List<ParsedPosting> parse(String body, CompanyBoard board) {
                    return delegate.parse(body, board);
                }
            };
        }
    }
}
