# Referral-Aware Job Discovery Platform

Aggregates job postings from public ATS boards, deduplicates them across sources, ranks them with
hybrid retrieval, and runs a two-sided referral marketplace between seekers and verified
employees.

Java 21 · Spring Boot 3.5 · PostgreSQL 16 + pgvector · Redis 7 · Kafka · OpenSearch · MinIO ·
Testcontainers · JMH · Docker Compose · Kubernetes · GitHub Actions

```bash
git clone https://github.com/chauhansachin13/referral-aware-job-platform
cd referral-aware-job-platform
make up          # every dependency plus the app
make seed        # register a few real public ATS boards
open http://localhost:8080
```

The console at `/` exercises the real API: hybrid search with per-retriever attribution, the
referral state machine, board registration and the LSH retrieval curve. `/docs` is Swagger UI.

---

## Contents

- [Architecture](#architecture)
- [The three hardest problems](#the-three-hardest-problems)
- [What is and is not implemented](#what-is-and-is-not-implemented)
- [Benchmarks](#benchmarks)
- [Testing](#testing)
- [Running it](#running-it)
- [Operations](#operations)
- [What I would do differently at 100x](#what-i-would-do-differently-at-100x)
- [Design decisions](#design-decisions)

---

## Architecture

```
                    Greenhouse        Lever          Ashby
                     (public board APIs — no scraping)
                          │             │             │
                          └──────┬──────┴──────┬──────┘
                                 │             │
                        ┌────────▼─────────────▼────────┐
                        │  ingestion                    │   Redis: crawl queue (ZSET),
                        │  · per-host token bucket      │◄──────── per-host token bucket (Lua)
                        │  · conditional GET (ETag/IMS) │
                        │  · raw payload → parse → hash │
                        │  · adaptive interval per board│
                        └───────────────┬───────────────┘
                                        │ JobIngested  (transactional outbox)
                        ┌───────────────▼───────────────┐
                        │  dedup                        │
                        │  · title → (role, level)      │
                        │  · MinHash 128 → 16×8 LSH     │
                        │  · gated exact scoring        │
                        └───────────────┬───────────────┘
                                        │ JobCanonicalized
                        ┌───────────────▼───────────────┐
                        │  search                       │
                        │  · BM25 + kNN in one _msearch │────► OpenSearch
                        │  · reciprocal rank fusion     │
                        │  · exponential freshness decay│
                        └───────────────┬───────────────┘
                                        │
   ┌────────────────────┐   ┌───────────▼───────────┐   ┌────────────────────┐
   │  trust             │◄──┤  referral             ├──►│  MinIO             │
   │  · work-email OTP  │   │  · explicit FSM       │   │  AES-256-GCM       │
   │  · lease + expiry  │   │  · idempotent moves   │   │  ciphertext only   │
   │  · Wilson-bound    │   │  · full audit log     │   └────────────────────┘
   │    reputation      │   │  · fair matcher       │
   │  · quotas/capacity │   │  · expiry sweeper     │
   └────────────────────┘   └───────────────────────┘

   Every event: written to outbox_event in the business transaction, relayed to Kafka by a
   SKIP LOCKED poller. Every consumer: idempotent via processed_message, with a DLQ.
```

**Modules** (Gradle enforces the graph; a cycle fails the build):

| module | owns | depends on |
|---|---|---|
| `common` | outbox, inbox, DLQ, ids, text normalization, error model | — |
| `ingestion` | companies, boards, raw payloads, raw postings, crawl schedule | `common` |
| `dedup` | canonical jobs, job sources, LSH index | `common`, `ingestion` |
| `search` | OpenSearch index and query path | `common`, `dedup` |
| `trust` | users, employee verification, reputation, quotas | `common`, `ingestion` |
| `referral` | referral requests, transitions, resumes, matching | `common`, `trust`, `dedup` |
| `app` | Spring Boot entry point, config, web console | all six |
| `benchmarks` | JMH suite and synthetic corpus | all six |

It is one deployable, not six services — [ADR 1](docs/adr/0001-modular-monolith-over-microservices.md)
explains why, and what that costs.

---

## The three hardest problems

### 1. Crawling four thousand boards politely and cheaply

A fixed crawl interval is wrong in both directions simultaneously. Six-hourly crawls of a company
posting thirty roles a day means seekers arrive a day late, in a market where the first fifty
applicants get read. The same interval against a ten-person startup spends 1,460 requests a year
to learn nothing, on someone else's infrastructure.

Four mechanisms, applied in order of increasing cost:

1. **Conditional GET.** ETag and `If-Modified-Since` from the last response. A 304 transfers no
   body, parses nothing, and writes one row.
2. **Raw content hash.** Boards that offer no validators still resend identical bytes. If the
   SHA-256 of the body matches a payload already stored for that board, the parse is skipped
   entirely.
3. **Semantic hash.** Computed over *sorted, normalized, meaning-bearing fields only*. A
   re-ordered `jobs` array, a regenerated tracking parameter, or a whitespace change from a CMS
   redeploy produce identical semantic hashes and therefore zero events. Without this, cosmetic
   churn floods dedup, the indexer, and everyone's saved-search notifications.
4. **Adaptive interval.** Derived from the board's own EWMA-smoothed posting rate, then backed
   off multiplicatively while it keeps coming back unchanged, clamped to `[5 min, 12 h]`.

Politeness is enforced by a **per-host token bucket implemented as a Redis Lua script**. Atomicity
is the entire point: with a client-side read-modify-write, twenty workers all read "9 tokens left",
all decide they may proceed, and the host sees twenty requests against a bucket that held nine.
The integration test runs 500 attempts across 20 threads and asserts the total never exceeds
capacity plus refill.

`AdaptiveInterval` is a pure function — nine unit tests cover both clamps, compounding backoff and
rate decay, in milliseconds, with no Redis and no clock.

→ [ADR 3](docs/adr/0003-adaptive-crawl-scheduling.md)

### 2. Deciding when two postings are the same job

The same role appears on a company's Greenhouse board, on an aggregator, and again next quarter
under a new requisition id. Showing all three separately makes the product worse than the boards
it aggregates. Comparing every new posting against 200,000 canonical jobs is quadratic.

**Retrieval and scoring are separate stages with different jobs.** MinHash signatures (128
permutations) banded into 16 bands of 8 rows give a candidate set from one indexed lookup per
band, scoped to the company. That is deliberately over-inclusive. Only the top 25 candidates —
ranked by matched bands, then by their MinHash estimate, both computed from data already in
memory — pay for an exact Jaccard.

**A title normalizer projects every company's ladder onto one.** `SDE-1`, `Software Engineer I`
and `Software Development Engineer 1` all become `(software engineer, ENTRY)`. Without it, the
deduplicator is choosing between missing every real repost and merging every posting a company
has, because the text they share is boilerplate that matches half the corpus.

**The scorer is not a weighted sum.** That was the first design, and on the labelled set it scored
0.65 precision. Two postings at the same company with identical text but two rungs apart on the
ladder scored 0.924; a different company scored 0.850. The problem is structural — to make
"different company" outweigh a 0.97 Jaccard and an identical title, its weight has to be so large
that nothing else in the formula can move the result. **A linear model cannot express a
constraint.** Company identity, ladder distance and remote-versus-onsite became multiplicative
gates over the weighted score. Precision went to 1.00.

Two bugs the labelled set caught, both ordering:

- the bare `staff` rule matched the word inside "member of technical staff", classifying every
  MTS posting a rung too high;
- the numeric-ladder rule consumes the role noun (`engineer ii`), stranding `software` as a
  specialization and making `Software Engineer II` differ from `SDE-2`.

→ [ADR 4](docs/adr/0004-two-stage-deduplication.md)

### 3. Releasing a resume to exactly one person, for five minutes

A resume is the most sensitive thing this platform holds. The requirement is encryption at rest,
release only through short-TTL signed URLs, and a working hard-delete path.

The conventional answer — server-side bucket encryption plus an S3 presigned URL — fails on three
counts. With SSE, anyone holding bucket credentials reads resumes in plaintext, and that set only
grows: backup jobs, analytics exports, a debugging session. A presigned URL cannot be revoked
before it expires, so a link minted thirty seconds before a referral is withdrawn still works. And
the access decision moves to the object store, where it is not audited.

So: **AES-256-GCM inside the application**, before the bytes reach MinIO. The object store never
holds plaintext. Release is via an **HMAC-signed token this application mints and redeems**, valid
five minutes, with the referral request id bound into the signature. Access is checked twice —
when the link is minted and again when it is redeemed, against the referral's *current* state.

GCM's authentication tag plus a stored SHA-256 means tampering fails loudly rather than returning
altered bytes. Deletion removes the object first, then the row: a hard delete, because soft-deleted
PII is still PII.

The two crypto secrets have **no defaults**. The application refuses to start without them, because
a default secret ships, nobody notices, and every deployment shares it.

→ [ADR 6](docs/adr/0006-application-side-resume-encryption.md)

---

## What is and is not implemented

Being precise about this, because a README that overstates is worse than one that omits.

**Fully implemented and tested**

- Ingestion: three ATS adapters, conditional fetch, dual hashing, adaptive scheduling, distributed
  rate limiting, raw payload persistence, outbox emission.
- Dedup: title canonicalization, MinHash + LSH banding, gated scoring, canonical job / job source
  model, precision-recall gate in CI.
- Search: hybrid BM25 + kNN in one `_msearch`, RRF, freshness decay, cursor pagination, filters
  applied to both legs.
- Referral: the full state machine with idempotent transitions, audit log, resume encryption and
  gated release, hard delete, expiry sweeper.
- Trust: work-email OTP verification with lease expiry, seeker quotas, referrer capacity,
  Wilson-bound reputation.
- Matcher: capacity-respecting, fairness-weighted assignment as a pure function.
- Infrastructure: transactional outbox with SKIP LOCKED relay, idempotent consumers, DLQ,
  Flyway migrations namespaced per module, Prometheus metrics, Docker Compose, multi-stage
  image, Kubernetes manifests, CI with real Testcontainers.

**Deliberately simplified — and why**

- **Embeddings are not neural.** `ConceptHashingEmbeddingModel` is a signed random projection over
  literal tokens plus a curated job-domain concept lexicon. It makes "k8s" retrieve "container
  orchestration" with zero shared tokens, which is the property the product needs, and it knows
  exactly the synonyms in that lexicon and no others. It cannot represent word order or negation.
  Calling its output "semantic" would be overselling it.
  [ADR 7](docs/adr/0007-offline-embedding-model.md)
- **The labelled dedup set is 24 pairs.** Enough to gate regressions on known-hard cases; not a
  claim about production accuracy. Every negative pair shares company boilerplate and several
  share a title, level or location, so the set is adversarial rather than easy — but it can be
  overfitted, and 24 pairs is 24 pairs.
- **No authentication.** Every endpoint takes actor ids as parameters. Adding OAuth2 resource
  server config is a well-understood afternoon; leaving it out keeps the demo runnable. It does
  mean this is not deployable as-is.
- **Notifications are events, not emails.** The platform emits `NotificationRequested` through the
  outbox; no SMTP client exists. The verification code is therefore visible only in the event
  payload.
- **Referrer profiles are empty.** The matcher scores on department, stack, seniority and
  responsiveness, but referrers currently have no profile to populate the first three, so those
  terms contribute nothing rather than contributing a wrong guess. The matcher itself is complete
  and tested against synthetic profiles.
- **`referralhub.storage.encryption-key` rotation is manual.** The `key_id` column exists so a
  rotation is trackable per object; the re-encryption job does not.
- **pgvector is installed but the serving path is OpenSearch.** Embeddings are stored in Postgres
  as the source of truth; the `vector` column type is available for a future in-database kNN
  fallback.

---

## Benchmarks

JMH, `Mode.SampleTime` for latency (p50/p99/p999) and `Mode.AverageTime` for throughput-shaped
work. Synthetic corpus generated by `SyntheticCorpus`, seeded so two machines compare like for
like: heavy shared boilerplate, ~19 title families, and a 15% near-duplicate rate — a corpus
without those properties makes the LSH index look far better than it is.

**Hardware.** Apple M4, 10 cores (10 physical), 16 GB, macOS 26.6.2. JDK 25 running the harness,
compiled to Java 21 bytecode. Single fork, 3 warmup and 5 measurement iterations.

These are **single-machine microbenchmarks of the CPU-bound work**, not end-to-end system
throughput. Network, Postgres and OpenSearch are excluded, which is the point: they measure the
parts this codebase controls.

### Deduplication — a 200,000-job corpus

| benchmark | p50 | p99 | p999 |
|---|---:|---:|---:|
| LSH candidate generation (16 band lookups) | 62.7 µs | 104.8 µs | 199.9 µs |
| LSH retrieval + exact scoring of the top 25 | 77.6 µs | 143.9 µs | 254.2 µs |

| baseline | mean |
|---|---:|
| Fingerprint one posting (normalize, shingle, 128 permutations) | 41.6 µs ± 0.77 |
| Exact scoring, 2,000 comparisons (no index) | 1.647 ms ± 0.12 |

The second table is the argument for the first. Exact scoring costs **0.82 µs per pair**, so
comparing one posting against all 200,000 canonical jobs would take roughly **165 ms**. The
indexed path — band lookup plus exact scoring of a 25-candidate shortlist — does the same job in
**77.6 µs at p50**, about **2,100× less work**.

That ratio is the whole reason the two-stage design exists, and it is why the linear-scan
baseline is measured over a 2,000-row slice rather than the full corpus: at 165 ms per operation
the harness would spend its entire budget on a handful of iterations.

### Ingestion — cost of one crawl, network excluded

| benchmark | 200 postings | 800 postings |
|---|---:|---:|
| Raw content hash only (the short-circuit) | 46.8 µs | 186.3 µs |
| Parse the board response | 0.749 ms | 3.029 ms |
| Semantic hash (normalize + hash every posting) | 3.101 ms | 13.366 ms |
| Full ingest path (hash → parse → hash → per-posting hash) | 7.387 ms | 27.842 ms |

Three tiers, and the gaps between them are the design:

- A **304** costs one `UPDATE`. No body is transferred and none of the rows above run.
- A board that offers no validators but resends identical bytes costs the **raw hash only**:
  186 µs against 27.8 ms for the full path, or **150× cheaper**.
- Only genuinely new bytes pay the full 27.8 ms — and even then, the semantic hash decides
  whether any event is worth emitting.

### Search — the application's share of query latency

| benchmark | depth 50 | depth 200 | depth 500 |
|---|---:|---:|---:|
| Embed the query | 6.24 µs | 6.24 µs | 6.20 µs |
| RRF fusion (p50) | 2.25 µs | 10.91 µs | 27.90 µs |
| Fusion + freshness decay + final sort (p50) | 5.62 µs | 25.57 µs | 65.79 µs |
| **Full application path (p50 / p99 / p999)** | **13.7 / 19.1 / 41.1 µs** | **33.0 / 41.0 / 107.3 µs** | **72.2 / 85.4 / 356.9 µs** |

Query embedding is flat, as it must be — it does not depend on how deep retrieval goes. Everything
after the OpenSearch response scales linearly with `candidate-depth`, which is exactly why that
setting is configuration and not a constant: raising it from 200 to 500 more than doubles the
application's contribution to latency.

The OpenSearch round trip is **not** included here and dominates in practice. A single-node
container's latency says nothing useful about a real cluster's, so it is measured in the
integration environment instead of being reported as a number that would not survive contact with
production.

### Referral matching

| pending requests | referrers | p50 | p99 | p999 |
|---:|---:|---:|---:|---:|
| 50 | 3 | 7.8 µs | 9.9 µs | 18.0 µs |
| 400 | 3 | 10.4 µs | 13.1 µs | 20.9 µs |
| 2,000 | 3 | 27.8 µs | 34.5 µs | 39.8 µs |
| 50 | 25 | 289.3 µs | 329.9 µs | 586.6 µs |
| 400 | 25 | 308.7 µs | 351.7 µs | 596.0 µs |
| 2,000 | 25 | 568.3 µs | 815.4 µs | 884.3 µs |

Worth reading carefully, because the shape is not the obvious one: **2,000 requests against 3
referrers (27.8 µs) is an order of magnitude cheaper than 50 requests against 25 (289.3 µs)**,
despite similar nominal pair counts.

The reason is that the matcher short-circuits. Once every referrer is at capacity, the inner loop
skips them without scoring, so cost tracks *placements actually attempted* rather than queue
length. Three referrers with capacities of one to five saturate after a handful of assignments and
the remaining 1,990 requests cost almost nothing. That is the realistic case — a marketplace is
supply-constrained — and it means queue growth does not translate into matcher cost.

### A benchmark bug worth recording

The matcher benchmark produced no results at all on the first run. Its setup built referrer
profiles with `Set.of(tech[random], tech[random])`, and `Set.of` throws on duplicate elements —
drawing twice from a six-element array collides almost immediately. JMH reported the failure and
carried on with the other classes, so the suite went green with one benchmark silently missing.

Recorded here rather than quietly fixed because it is the failure mode benchmark suites actually
have: not wrong numbers, but absent ones that nobody notices.

**Reproduce:** `make bench` (several minutes). Raw JSON lands in
`modules/benchmarks/build/reports/jmh/results.json`.

---

## Testing

```
module        tests  passed  skipped  failed
common           28      18       10       0
dedup            70      70        0       0
ingestion        40      29       11       0
referral         74      63       11       0
search           40      34        6       0
trust            25      25        0       0
TOTAL           277     239       38       0
```

The 38 skipped are Testcontainers integration tests. They are **not optional** — CI always has a
Docker daemon and always runs them. `@RequiresDocker` disables them on a machine without one so
`./gradlew test` is honestly green rather than a wall of connection errors that trains people to
ignore test output. Force them locally with `make verify`.

What the integration tests actually prove, as opposed to asserting that a mock was called:

- `FOR UPDATE SKIP LOCKED` gives two concurrent relays disjoint batches (no in-memory database
  implements this faithfully).
- A rolled-back business transaction leaves no outbox row.
- 20 threads cannot collectively exceed one host's token bucket.
- A 304 transfers no body, writes no payload row and emits no event — asserted over a real socket
  against a real HTTP server, and again through the whole pipeline.
- A cosmetically reordered board response produces new bytes and zero events.
- A semantically equivalent query with zero token overlap retrieves the right job through
  OpenSearch.
- Two equally relevant jobs order by recency, with the fused scores asserted equal to prove the
  decay is what separated them.
- Replaying a referral transition with the same idempotency key writes one audit row, not two,
  and does not inflate the referrer's reputation counters.

Some claims are proved at the level where they are actually decided rather than only end-to-end.
Whether "k8s" finds "container orchestration" is a property of the vector space: if the vectors
are close, HNSW returns them, and if they are not, no index tuning helps. So that runs on every
build with no Docker, and the integration test confirms it survives the round trip.

---

## Running it

```bash
make up            # everything, including the app
make deps          # dependencies only, then ./gradlew :app:bootRun
make observability # + Prometheus (:9090) and Grafana (:3000, admin/admin)
make seed          # register real public ATS boards
make smoke         # verify a running stack actually answers
make verify        # full suite including Testcontainers
make bench         # JMH
make secrets       # generate a fresh pair of secrets for .env
make down / clean
```

Requires Docker and a JDK. Gradle provisions JDK 21 through the toolchain resolver, so the JDK on
your PATH does not have to be 21 — this was developed on JDK 25.

| service | url |
|---|---|
| Console | http://localhost:8080 |
| OpenAPI | http://localhost:8080/docs |
| Health | http://localhost:8080/actuator/health |
| Metrics | http://localhost:8080/actuator/prometheus |
| OpenSearch | http://localhost:9200 |
| MinIO console | http://localhost:9001 |
| Grafana | http://localhost:3000 |

**Configuration** is environment variables only; see [`.env.example`](.env.example). Two secrets
have no default and the app will not start without them.

---

## Operations

**Metrics.** `referralhub.crawl.outcome{status}`, `referralhub.crawl.duration`,
`referralhub.outbox.pending`, `referralhub.outbox.poisoned`, `referralhub.outbox.published`,
`referralhub.dedup.decision{outcome}`, `referralhub.search.query` (p50/p95/p99),
`referralhub.search.indexed`, `referralhub.referral.expired`. A Grafana dashboard is provisioned
at `docker/grafana/provisioning/dashboards/`.

`referralhub.outbox.poisoned` is the one to alert on: rows past the attempt ceiling have stopped
being retried and need a human.

**Deployment.** `deploy/k8s/` — Deployment with startup/liveness/readiness probes (the startup
probe exists so Flyway migrations are not killed mid-run), HPA, PodDisruptionBudget,
NetworkPolicy, non-root read-only container. `kubectl apply -k deploy/k8s/`, after creating the
Secret out of band.

**CI.** Build and test with real containers; container image built and pushed to GHCR on `main`;
a compose smoke test that proves the one-command claim; CodeQL and Trivy on a schedule as well as
on push, because dependencies rot without any commit touching them.

**Scaling.** Every replica runs its own outbox relay and crawl worker pool. Coordination is
`SKIP_LOCKED` on the outbox and an atomic ZSET pop on the crawl queue, so adding replicas adds
throughput with no leader election.

---

## What I would do differently at 100x

At roughly 20M canonical jobs and 400k crawls an hour, five things break in a specific order.

**1. Ingestion outgrows one process.** The crawl queue is a single Redis ZSET and every replica
polls it. At 400k crawls an hour that key becomes the bottleneck and its failure takes down all
crawling. I would shard the queue by `hash(boardId) % N` with one consumer group per shard, and
move the crawler out of the API deployment entirely — it has a completely different scaling
profile from a latency-sensitive search endpoint, and today they share a connection pool.

**2. The outbox relay becomes the write bottleneck.** Polling with `SKIP LOCKED` is fine at
thousands of events a second and stops being fine somewhere above that: the relay competes with
business writes for the same rows and the same WAL. I would switch to Debezium reading the
Postgres replication slot — no polling, no lock contention, and the outbox table becomes an
append-only log nothing ever reads. That is a real operational cost (a Connect cluster, slot
monitoring, a schema-change process), which is exactly why it is not there now.

**3. Dedup needs a real vector index and a blocking key.** The LSH band table works because
candidates are scoped to one company. At 20M jobs the largest companies alone have 50k canonical
jobs and the band buckets get long. I would add a coarse blocking key —
`(company, canonical_role, canonical_level)` — before the band lookup, and move candidate
generation out of Postgres into a purpose-built ANN index. I would also replace the hand-tuned
weighted score with a gradient-boosted model trained on merge decisions, keeping the hard gates,
because those encode facts rather than preferences and a learned model would happily trade them
away for a fraction of a point of accuracy.

**4. Search needs the fusion pushed down.** Fusing in the application is why deep pagination is
capped at 1,000 — there is no single sort key for `search_after`. OpenSearch's native hybrid query
with a normalization processor moves fusion into the cluster and restores real cursor pagination.
I would also split the index by recency (hot: last 30 days; cold: everything else) and query the
cold tier only when the hot tier under-fills, since the freshness decay already means almost
nothing older than a quarter reaches a first page.

**5. Resumes need envelope encryption and a real KMS.** One application-level key for every object
means rotation is a full re-encryption pass over the entire corpus. Per-object data keys wrapped
by a KMS master key make rotation a metadata operation. The `key_id` column exists so this is a
migration rather than a redesign.

Two things I would *not* change. The transactional outbox stays — at any scale, the alternative is
events for state that never existed. And the modules stay compile-time separated; if one has to
become a service, the events it consumes and produces are already on Kafka and its tables are
already disjoint, so it is a deployment change rather than a rewrite.

The thing I would fix first, before any of this, is the honest gap: **there is no authentication**,
and everything above assumes a system that has it.

---

## Design decisions

| ADR | Decision |
|---|---|
| [1](docs/adr/0001-modular-monolith-over-microservices.md) | A modular monolith, not six services |
| [2](docs/adr/0002-transactional-outbox.md) | Transactional outbox for every event |
| [3](docs/adr/0003-adaptive-crawl-scheduling.md) | Adaptive crawl intervals, not a fixed schedule |
| [4](docs/adr/0004-two-stage-deduplication.md) | LSH retrieval plus gated exact scoring |
| [5](docs/adr/0005-hand-written-opensearch-client.md) | Hand-written OpenSearch REST client |
| [6](docs/adr/0006-application-side-resume-encryption.md) | Application-side resume encryption |
| [7](docs/adr/0007-offline-embedding-model.md) | A deterministic offline embedding model |

---

## Ethics and scope of crawling

Only documented public ATS board APIs are used: Greenhouse `boards-api.greenhouse.io`, Lever
`api.lever.co/v0/postings`, Ashby `api.ashbyhq.com/posting-api`. These are the same endpoints that
power the companies' own careers pages. The crawler identifies itself with a contact URL, honours
`ETag`/`If-Modified-Since`, and applies a per-host token bucket shared across the whole fleet.

LinkedIn, Naukri and other sites whose terms prohibit automated access are not crawled, and no
adapter exists for them.

## Licence

MIT.
