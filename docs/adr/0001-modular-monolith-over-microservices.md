# 1. A modular monolith, not six services

- Status: Accepted
- Date: 2026-08-29

## Context

The platform has six clearly separable concerns: ingestion, deduplication, search, referrals,
trust and shared infrastructure. Each owns its own tables and communicates with the others
through events. That is the shape people usually reach for microservices to express.

This is a solo project. There is one deployment target, one on-call person, and no team
boundary that a service boundary would be protecting.

## Decision

Ship one deployable containing six Gradle modules with an enforced dependency graph. Modules
communicate through Kafka topics and through a small number of explicit read APIs
(`RawPostingStore.findById`, `BoardStore.findCompany`), never by reaching into each other's
tables.

## Alternatives considered

**Six independent services.** Correct if the modules had different scaling profiles or different
teams. They do not: ingestion is bursty and search is latency-sensitive, but both are bounded by
the same Postgres and would be deployed together anyway. The cost would be six pipelines, six
sets of dashboards, distributed tracing to answer questions a stack trace answers today, and
network calls where method calls suffice.

**A single flat module.** Simpler to start, and the reason most "we'll split it later" monoliths
never split. Without a compile-time dependency graph, `SearchService` acquires a reference to
`CrawlPipeline` in week three and the boundary is gone.

## Consequences

- The dependency graph is enforced by Gradle: `ingestion` cannot see `search`, and a cycle fails
  the build rather than being discovered later.
- Each module's integration tests scan only the packages it actually depends on, so accidental
  coupling breaks a test rather than passing silently.
- Extracting a module later is a deployment change: the events it consumes and produces are
  already on Kafka, and its tables are already disjoint.
- The honest cost: everything shares a JVM and a connection pool. A crawl storm can starve the
  search API of database connections. Mitigated by pool sizing and by the crawler's own bounded
  worker pool, not eliminated.
