package com.referralhub.search.embed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The synonym and abbreviation map that job search actually runs on.
 *
 * <p>Hiring vocabulary is unusually synonym-dense and unusually abbreviation-heavy. A seeker
 * types "k8s"; the posting says "container orchestration". A seeker types "ML engineer"; the
 * posting says "deep learning". BM25 scores both pairs at zero because they share no token.
 *
 * <p>Mapping surface forms onto concept ids before embedding is what lets a query with no
 * lexical overlap still retrieve the right job. It is a curated lexicon, not a learned model —
 * cheap, inspectable, and exactly as good as the list below, which is the honest trade.
 */
public final class JobDomainOntology {

    /** Concept id -> surface forms. Longest forms are matched first. */
    private static final Map<String, List<String>> CONCEPTS = new LinkedHashMap<>();

    static {
        concept("software-engineering", "software development engineer", "software engineer",
                "software developer", "member of technical staff", "programmer", "sde", "swe",
                "mts", "coder", "engineer");
        concept("reliability", "site reliability engineering", "site reliability engineer",
                "platform engineering", "infrastructure engineering", "production engineering",
                "devops", "sre", "on call", "error budget", "toil");
        concept("kubernetes", "container orchestration", "kubernetes", "k8s", "eks", "gke",
                "container platform", "helm", "kubelet");
        concept("containers", "docker", "containerd", "oci image", "containerisation",
                "containerization");
        concept("machine-learning", "machine learning", "deep learning", "neural network",
                "artificial intelligence", "ml", "mle", "pytorch", "tensorflow", "model training");
        concept("ranking", "learning to rank", "relevance ranking", "ranking model",
                "recommendation", "recsys", "personalisation", "personalization");
        concept("search", "information retrieval", "full text search", "bm25", "elasticsearch",
                "opensearch", "lucene", "query understanding", "inverted index");
        concept("data-engineering", "data pipeline", "etl", "elt", "data warehouse", "batch job",
                "spark", "airflow", "dbt", "backfill");
        concept("streaming", "stream processing", "event streaming", "kafka", "pulsar", "kinesis",
                "flink", "event driven");
        concept("payments", "payment processing", "money movement", "ledger", "settlement",
                "reconciliation", "billing", "fintech", "double entry");
        concept("frontend", "front end", "frontend", "user interface", "react", "typescript",
                "javascript", "css", "accessibility", "browser");
        concept("backend", "back end", "backend", "server side", "api development",
                "microservices", "distributed systems");
        concept("mobile", "ios", "android", "swift", "kotlin", "react native", "mobile app");
        concept("security", "application security", "appsec", "infosec", "penetration testing",
                "threat modelling", "threat modeling", "cryptography", "zero trust");
        concept("cloud", "aws", "amazon web services", "azure", "gcp", "google cloud",
                "cloud native", "serverless", "lambda");
        concept("iac", "infrastructure as code", "terraform", "pulumi", "cloudformation",
                "ansible", "configuration management");
        concept("observability", "monitoring", "telemetry", "prometheus", "grafana",
                "distributed tracing", "opentelemetry", "alerting", "slo", "sli");
        concept("ci-cd", "continuous integration", "continuous delivery", "continuous deployment",
                "ci cd", "build pipeline", "github actions", "jenkins", "argocd");
        concept("database", "postgres", "postgresql", "mysql", "relational database", "rdbms",
                "sql", "query planner", "index tuning");
        concept("nosql", "cassandra", "dynamodb", "mongodb", "key value store", "document store");
        concept("caching", "redis", "memcached", "cache invalidation", "in memory store");
        concept("java", "java", "jvm", "spring boot", "spring framework", "kotlin jvm");
        concept("go", "golang", "go language");
        concept("python", "python", "django", "fastapi", "pandas");
        concept("remote-work", "remote", "work from home", "distributed team", "anywhere",
                "fully remote", "telecommute");
        concept("seniority-senior", "senior", "sr", "experienced", "seasoned");
        concept("seniority-staff", "staff", "tech lead", "technical lead", "principal",
                "distinguished");
        concept("seniority-entry", "junior", "graduate", "new grad", "entry level", "intern",
                "internship");
        concept("management", "engineering manager", "people manager", "line manager",
                "director of engineering", "head of engineering");
        concept("product", "product manager", "product owner", "roadmap", "discovery",
                "stakeholder");
        concept("testing", "quality assurance", "test automation", "sdet", "unit testing",
                "integration testing", "end to end testing");
    }

    private static void concept(String id, String... surfaceForms) {
        List<String> forms = new ArrayList<>(List.of(surfaceForms));
        // Longest first so "site reliability engineer" wins over "engineer".
        forms.sort((a, b) -> Integer.compare(b.length(), a.length()));
        CONCEPTS.put(id, forms);
    }

    private JobDomainOntology() {
    }

    /**
     * Concept ids present in the normalized text.
     *
     * <p>Phrase matching is done on the raw normalized string rather than on tokens, because
     * half the entries here are multi-word ("learning to rank", "money movement") and a
     * token-level lookup would never see them.
     */
    public static List<String> conceptsIn(String normalizedText) {
        String haystack = " " + normalizedText + " ";
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : CONCEPTS.entrySet()) {
            for (String form : entry.getValue()) {
                if (haystack.contains(" " + form + " ")) {
                    found.add(entry.getKey());
                    break;
                }
            }
        }
        return found;
    }

    public static int size() {
        return CONCEPTS.size();
    }

    public static java.util.Set<String> conceptIds() {
        return CONCEPTS.keySet();
    }
}
