package com.referralhub.benchmarks;

import com.referralhub.ingestion.adapter.ParsedPosting;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates a job corpus with realistic shape, deterministically.
 *
 * <p>Benchmarks against random strings measure nothing useful: real postings share heavy
 * boilerplate, cluster into a few hundred title families, and contain a long tail of near
 * duplicates. A corpus without those properties makes the LSH index look far better than it is,
 * because nothing ever lands in the same bucket.
 *
 * <p>Seeded, so two runs on two machines compare like for like.
 */
public final class SyntheticCorpus {

    private static final String[] ROLE_TEMPLATES = {
            "Software Engineer", "Senior Software Engineer", "Staff Software Engineer",
            "SDE-1", "SDE-2", "SDE-3", "Member of Technical Staff", "Backend Engineer",
            "Frontend Engineer", "Site Reliability Engineer", "Data Engineer", "Data Scientist",
            "Machine Learning Engineer", "Security Engineer", "QA Engineer", "Product Manager",
            "Engineering Manager", "Technical Program Manager", "Solutions Architect"
    };

    private static final String[] SPECIALIZATIONS = {
            "Payments", "Search", "Infrastructure", "Growth", "Platform", "Identity", "Billing",
            "Risk", "Marketplace", "Ranking", "Developer Experience", "Data Platform"
    };

    private static final String[] LOCATIONS = {
            "San Francisco, CA", "New York, NY", "Seattle, WA", "Austin, TX", "London, UK",
            "Berlin, Germany", "Bengaluru, India", "Toronto, Canada", "Remote - United States",
            "Remote - EMEA", "Dublin, Ireland", "Singapore"
    };

    private static final String[] TECH = {
            "Java", "Kotlin", "Go", "Python", "TypeScript", "React", "Kubernetes", "Terraform",
            "Kafka", "Postgres", "Redis", "Spark", "OpenSearch", "gRPC", "AWS", "GCP"
    };

    /** The shared boilerplate that makes real deduplication hard. */
    private static final String BOILERPLATE = """
            We are a distributed team that values ownership, written communication and shipping.
            We offer competitive compensation, meaningful equity and comprehensive health coverage.
            We are an equal opportunity employer and we welcome applicants from every background.
            Applications are reviewed on a rolling basis and we aim to respond within two weeks.
            """;

    private SyntheticCorpus() {
    }

    public record Posting(UUID id, UUID companyId, String title, String description,
                          String location, boolean remote, Instant postedAt) {
    }

    /**
     * @param size          number of postings
     * @param companies     how many companies they are spread across
     * @param duplicateRate fraction that are near duplicates of an earlier posting
     */
    public static List<Posting> generate(int size, int companies, double duplicateRate, long seed) {
        Random random = new Random(seed);
        List<UUID> companyIds = new ArrayList<>(companies);
        for (int i = 0; i < companies; i++) {
            companyIds.add(new UUID(seed, i));
        }

        List<Posting> corpus = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            boolean makeDuplicate = !corpus.isEmpty() && random.nextDouble() < duplicateRate;
            if (makeDuplicate) {
                Posting original = corpus.get(random.nextInt(corpus.size()));
                corpus.add(nearDuplicateOf(original, random, i));
                continue;
            }

            UUID companyId = companyIds.get(random.nextInt(companies));
            String role = ROLE_TEMPLATES[random.nextInt(ROLE_TEMPLATES.length)];
            String specialization = SPECIALIZATIONS[random.nextInt(SPECIALIZATIONS.length)];
            String location = LOCATIONS[random.nextInt(LOCATIONS.length)];

            corpus.add(new Posting(
                    new UUID(seed + 1, i),
                    companyId,
                    role + ", " + specialization,
                    description(random, specialization),
                    location,
                    location.startsWith("Remote"),
                    Instant.now().minusSeconds(random.nextInt(90 * 86_400))));
        }
        return corpus;
    }

    /** The same job, lightly reworded: a repost or a second board. */
    private static Posting nearDuplicateOf(Posting original, Random random, int index) {
        String title = random.nextBoolean()
                ? original.title().replace("Senior", "Sr.").replace("SDE-2", "Software Engineer II")
                : original.title();
        String description = original.description()
                + (random.nextBoolean() ? " This role reports to the engineering manager." : "");
        return new Posting(new UUID(99L, index), original.companyId(), title, description,
                original.location(), original.remote(), original.postedAt());
    }

    private static String description(Random random, String specialization) {
        StringBuilder text = new StringBuilder(1_024);
        text.append("<p>You will own ").append(specialization.toLowerCase(java.util.Locale.ROOT))
                .append(" end to end, from design through operation.</p><p>Our stack includes ");
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(TECH[random.nextInt(TECH.length)]);
        }
        text.append(".</p><p>").append(BOILERPLATE).append("</p>");
        return text.toString();
    }

    /** The same corpus shaped as adapter output, for the ingestion benchmarks. */
    public static List<ParsedPosting> asParsedPostings(List<Posting> corpus) {
        List<ParsedPosting> parsed = new ArrayList<>(corpus.size());
        for (Posting posting : corpus) {
            parsed.add(new ParsedPosting(posting.id().toString(), posting.title(),
                    posting.description(), posting.location(), posting.remote(), "Engineering",
                    "https://example.test/" + posting.id(), posting.postedAt(), "{}"));
        }
        return parsed;
    }

    /** A Greenhouse-shaped response body for the parser benchmark. */
    public static String asGreenhouseJson(List<Posting> corpus) {
        StringBuilder json = new StringBuilder("{\"jobs\":[");
        for (int i = 0; i < corpus.size(); i++) {
            Posting posting = corpus.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(i)
                    .append(",\"title\":\"").append(escape(posting.title()))
                    .append("\",\"updated_at\":\"").append(posting.postedAt())
                    .append("\",\"location\":{\"name\":\"").append(escape(posting.location()))
                    .append("\"},\"absolute_url\":\"https://example.test/").append(i)
                    .append("\",\"departments\":[{\"name\":\"Engineering\"}]")
                    .append(",\"content\":\"").append(escape(posting.description())).append("\"}");
        }
        return json.append("]}").toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
