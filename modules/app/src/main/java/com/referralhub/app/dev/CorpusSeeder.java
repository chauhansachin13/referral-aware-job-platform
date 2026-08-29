package com.referralhub.app.dev;

import com.referralhub.dedup.DedupService;
import com.referralhub.ingestion.adapter.ParsedPosting;
import com.referralhub.ingestion.board.BoardStore;
import com.referralhub.ingestion.board.CompanyBoard;
import com.referralhub.ingestion.raw.PostingUpsert;
import com.referralhub.ingestion.raw.RawPostingStore;
import com.referralhub.search.index.JobIndexer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds a realistic corpus without crawling anyone.
 *
 * <p>Benchmarks and demos need a populated system, and getting one by pointing the crawler at
 * real ATS boards is both slow and impolite — it would mean thousands of requests to third
 * parties to produce test data. This writes an equivalent corpus straight through the real
 * services, so the rows, the canonical jobs and the index entries are exactly what a crawl would
 * have produced.
 *
 * <p>The generated corpus is deliberately shaped like the real thing: heavy shared boilerplate
 * within a company, a small number of title families, and a configurable near-duplicate rate.
 * A corpus of random strings would make the deduplicator and the LSH index look far better than
 * they are, because nothing would ever collide.
 *
 * <p>Seeded, so re-running produces byte-identical input and benchmark numbers stay comparable.
 * Disabled unless explicitly switched on; it is not something to point at a real database.
 */
@Component
@ConditionalOnProperty(prefix = "referralhub.loadgen", name = "seed-corpus", havingValue = "true")
public class CorpusSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CorpusSeeder.class);

    private static final String[] ROLES = {
            "Software Engineer", "Senior Software Engineer", "Staff Software Engineer", "SDE-1",
            "SDE-2", "SDE-3", "Member of Technical Staff", "Backend Engineer", "Frontend Engineer",
            "Site Reliability Engineer", "Data Engineer", "Data Scientist",
            "Machine Learning Engineer", "Security Engineer", "Product Manager",
            "Engineering Manager", "QA Engineer", "Solutions Architect"};

    private static final String[] SPECIALIZATIONS = {
            "Payments", "Search", "Infrastructure", "Growth", "Platform", "Identity", "Billing",
            "Risk", "Marketplace", "Ranking", "Developer Experience", "Data Platform"};

    private static final String[] LOCATIONS = {
            "San Francisco, CA", "New York, NY", "Seattle, WA", "Austin, TX", "London, UK",
            "Berlin, Germany", "Bengaluru, India", "Toronto, Canada", "Remote - United States",
            "Remote - EMEA", "Dublin, Ireland", "Singapore"};

    private static final String[] TECH = {
            "Java", "Kotlin", "Go", "Python", "TypeScript", "React", "Kubernetes", "Terraform",
            "Kafka", "Postgres", "Redis", "Spark", "OpenSearch", "gRPC", "AWS", "GCP"};

    private static final String BOILERPLATE =
            "We are a distributed team that values ownership, written communication and shipping. "
                    + "We offer competitive compensation, equity and comprehensive health coverage. "
                    + "We are an equal opportunity employer and welcome applicants from every background.";

    private final BoardStore boards;
    private final RawPostingStore postings;
    private final DedupService dedup;
    private final JobIndexer indexer;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final LoadGeneratorProperties properties;

    public CorpusSeeder(BoardStore boards,
                        RawPostingStore postings,
                        DedupService dedup,
                        JobIndexer indexer,
                        TransactionTemplate tx,
                        JdbcTemplate jdbc,
                        LoadGeneratorProperties properties) {
        this.boards = boards;
        this.postings = postings;
        this.dedup = dedup;
        this.indexer = indexer;
        this.tx = tx;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        int companies = properties.getCompanies();
        int perCompany = properties.getJobsPerCompany();
        long started = System.nanoTime();

        log.info("Seeding {} companies x {} postings (duplicate rate {})",
                companies, perCompany, properties.getDuplicateRate());

        Random random = new Random(properties.getSeed());
        int ingested = 0;
        int canonicalized = 0;

        for (int c = 0; c < companies; c++) {
            String slug = "synthetic-" + c;
            UUID companyId = tx.execute(status ->
                    boards.upsertCompany("Synthetic " + slug, slug, slug + ".test", null));
            UUID boardId = tx.execute(status ->
                    boards.registerBoard(companyId, "greenhouse", slug, Duration.ofHours(6)));
            CompanyBoard board = boards.findById(boardId).orElseThrow();

            List<ParsedPosting> generated = generateFor(board, perCompany, random);
            List<PostingUpsert> upserts = tx.execute(status -> postings.upsertAll(board, generated));
            ingested += upserts.size();

            for (PostingUpsert upsert : upserts) {
                try {
                    dedup.canonicalize(upsert.id());
                    canonicalized++;
                } catch (RuntimeException e) {
                    log.warn("Could not canonicalize {}: {}", upsert.id(), e.toString());
                }
            }

            if ((c + 1) % 25 == 0) {
                log.info("  seeded {}/{} companies", c + 1, companies);
            }
        }

        List<UUID> canonicalIds = jdbc.queryForList("SELECT id FROM canonical_job", UUID.class);
        int indexed = 0;
        for (UUID id : canonicalIds) {
            try {
                indexer.index(id);
                indexed++;
            } catch (RuntimeException e) {
                log.warn("Could not index {}: {}", id, e.toString());
            }
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        log.info("""
                Seeding complete in {}s
                  raw postings ingested : {}
                  canonicalization runs : {}
                  canonical jobs        : {}  ({}% collapsed by dedup)
                  documents indexed     : {}""",
                elapsed.toSeconds(), ingested, canonicalized, canonicalIds.size(),
                ingested == 0 ? 0 : Math.round(100.0 * (ingested - canonicalIds.size()) / ingested),
                indexed);
    }

    private List<ParsedPosting> generateFor(CompanyBoard board, int count, Random random) {
        List<ParsedPosting> generated = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            boolean duplicate = !generated.isEmpty() && random.nextDouble() < properties.getDuplicateRate();
            if (duplicate) {
                ParsedPosting original = generated.get(random.nextInt(generated.size()));
                generated.add(new ParsedPosting(
                        board.boardToken() + "-dup-" + i,
                        // A repost with the title lightly reworded, as they arrive in reality.
                        original.title().replace("Senior", "Sr.").replace("SDE-2", "Software Engineer II"),
                        original.descriptionHtml(), original.location(), original.remote(),
                        original.department(), original.applyUrl(), original.postedAt(), "{}"));
                continue;
            }

            String role = ROLES[random.nextInt(ROLES.length)];
            String specialization = SPECIALIZATIONS[random.nextInt(SPECIALIZATIONS.length)];
            String location = LOCATIONS[random.nextInt(LOCATIONS.length)];

            generated.add(new ParsedPosting(
                    board.boardToken() + "-" + i,
                    role + ", " + specialization,
                    description(specialization, random),
                    location,
                    location.startsWith("Remote"),
                    "Engineering",
                    "https://example.test/" + board.boardToken() + "/" + i,
                    Instant.now().minusSeconds(random.nextInt(90 * 86_400)),
                    "{}"));
        }
        return generated;
    }

    private static String description(String specialization, Random random) {
        StringBuilder text = new StringBuilder(768);
        text.append("<p>You will own ").append(specialization.toLowerCase(Locale.ROOT))
                .append(" end to end, from design through operation.</p><p>Our stack includes ");
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(TECH[random.nextInt(TECH.length)]);
        }
        return text.append(".</p><p>").append(BOILERPLATE).append("</p>").toString();
    }
}
