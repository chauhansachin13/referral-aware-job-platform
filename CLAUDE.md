# Referral-Aware Job Discovery Platform

## What this is
A job aggregation platform that ingests postings from public ATS boards, deduplicates them across sources, ranks them by relevance and freshness, and runs a two-sided referral marketplace where seekers request referrals and verified employees provide them.

## Stack (do not deviate without asking)
- Java 21, Spring Boot 3.x, Gradle (Kotlin DSL)
- PostgreSQL 16 + pgvector, Flyway migrations
- Redis 7 (crawl scheduling, distributed rate limits, caching)
- Kafka (event backbone, transactional outbox)
- OpenSearch (BM25 + kNN hybrid retrieval)
- MinIO (S3-compatible, resume storage)
- Testcontainers for integration tests, JMH for benchmarks
- Docker Compose for local orchestration

## Modules
ingestion, dedup, search, referral, trust, common

## Engineering rules
- Every module gets Testcontainers-backed integration tests, not just unit tests with mocks.
- All Kafka producers use the transactional outbox pattern. All consumers are idempotent with a DLQ.
- No secrets in code. Config through environment variables only.
- Resumes are PII: encrypted at rest, access only via short-TTL signed URLs, hard-delete path required.
- Only ingest from public ATS APIs (Greenhouse, Lever, Ashby). Never scrape LinkedIn or Naukri.
- Package by feature, not by layer.
- Write an ADR in docs/adr/ for any non-obvious design decision, using the format: Context, Decision, Alternatives Considered, Consequences.

## Commands
./gradlew build, ./gradlew test, docker compose up -d

## Implementation notes (added during build)
- An extra `app` module exists purely as the Spring Boot entry point that assembles the
  six feature modules into one bootable jar. Feature modules are plain libraries.
- Testcontainers integration tests are tagged `@Tag("integration")` and skipped
  automatically when no Docker daemon is reachable, so `./gradlew test` is green on a
  machine without Docker and runs the full suite in CI where Docker exists.
