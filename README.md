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

### Authentication

Added last, and the README said so for most of this project's life: every endpoint used to take
the acting user's id as a parameter and believe it. Any caller could accept somebody else's
referral, or mint a resume link for a request that was not theirs.

Three tiers, and each boundary has a reason:

| tier | routes | why |
|---|---|---|
| public | search, console, health, metrics, docs, register, login | job search is the front door; an account to read public postings would be worse than pointless |
| authenticated | referrals, employee verification, standing | anything acting *for* a person |
| administrator | ATS board registration, forced crawls, reindexing | these spend somebody else's infrastructure budget |

The load-bearing detail is that **no endpoint accepts an actor id any more**. Passing one would
be an invitation to trust it, so `CurrentUser` reads the subject from the verified token and the
request body has nowhere to put an identity. A test asserts exactly this: a create request whose
body names a different user is accepted, and the referral is created for the token's owner.

Passwords are BCrypt at strength 12, and login hashes a dummy value when the account does not
exist so response time does not reveal which addresses are registered. Tokens are HS256, signed
with a secret that has no default — like the two resume secrets, the application refuses to start
without it, because a shipped signing key lets anyone mint a token for any account on every
deployment that forgot to override it.

Writing the tests surfaced one defect immediately: a wrong password returned **500**, because
`BadCredentialsException` fell through to the catch-all handler. A failed login answering
"Something went wrong" is not just wrong, it reads as an outage.

### What running it against a real board changed

The whole platform had a green pipeline before it had ever crawled anything real. Pointing it at
Stripe's public Greenhouse board — 575 live postings — found five things that neither the unit
tests nor the synthetic fixtures could.

**Seniority was being read off the role noun.** 29 product managers came back as `MANAGER`, 24
solutions architects as `PRINCIPAL`, 8 technical programme managers as `MANAGER`. The level rules
matched a bare `manager` and `architect`, so an individual contributor whose job title happens to
contain "manager" landed on the management ladder — where `levelGate` returns 0 and stops two
postings for the same role from ever merging. Seniority now has to come from a seniority word.
No synthetic fixture caught it because I had never written a fixture titled "Product Manager".

**The first crawl invented a posting rate.** A board's opening crawl reports its entire back
catalogue as changed, and there is no elapsed window to divide by, so 575 postings became an
estimated 4,140 postings per day and pinned the interval to its floor. A backlog is not a rate;
the first crawl no longer updates one.

**Then the fix over-corrected.** With no measured rate, the cadence jumped from the registered
one hour straight to the twelve hour ceiling after a single unchanged crawl — an order of
magnitude on one observation, which defeats the point of a backoff factor. Adaptation is now
damped to at most a factor of two per crawl, so the interval walks 60 → 120 → 240 → 480 minutes
as evidence accumulates.

**An unreachable dependency was a 500.** With OpenSearch not running, search answered "Something
went wrong". A 500 says this service has a bug; a 503 says a dependency is down and the request
is worth retrying. They send a caller, and whoever is on call, to different places.

**A refusal named the wrong reason.** Submitting a referral nobody had accepted reported that it
had been "accepted by someone else" — a correct refusal citing a race that never happened.

What *did* hold up: the conditional fetch. Against the real board, crawl one was HTTP 200 with
575 postings in 701 ms; crawls two and three were **HTTP 304** with no body, no parse and no
writes. Deduplication collapsed 19 of 575 postings, and every merge was a genuine one — the same
requisition posted for several cities.

### A correction the tests forced

Worth recording, because it is the kind of error that survives a green unit suite.

Search originally applied an **unbounded** exponential freshness decay to the RRF-fused score.
That is wrong, and wrong in a way that only a real index reveals.

Reciprocal rank fusion produces a deliberately *flat* score distribution — discarding score
magnitudes is the entire reason to use it. At the standard `k = 60`, rank 1 scores `1/61` and
rank 2 scores `1/62`: a gap of **1.6%**. An exponential decay spans the whole range from 1.0 to
0. Multiplying one by the other means the decay does not break ties, it *becomes* the ranking:

| document | rank | age | fused | decay | product |
|---|---:|---:|---:|---:|---:|
| perfect match | 1 | 6 months | 0.01639 | 0.0026 | 0.0000426 |
| poor match | 50 | today | 0.00901 | 0.98 | 0.00883 |

The fresh, largely irrelevant result wins by more than two hundred times.

The unit tests passed throughout, because they used hand-picked fused scores differing by 2x —
a gap RRF never produces between neighbouring results. It took an integration test against a
real OpenSearch to surface it, and the failure looked at first like a flaky assertion.

The fix bounds how much recency may take:
`multiplier = 1 - maxPenalty * (1 - 0.5^(age / halfLife))`, with `maxPenalty = 0.4`. Relevance
decides wide rank gaps; recency decides near-ties, which is what it was always for.
[ADR 8](docs/adr/0008-bounded-freshness-decay.md) records the alternatives.

---

## What is and is not implemented

Being precise about this, because a README that overstates is worse than one that omits.

**Fully implemented and tested**

- Ingestion: three ATS adapters, conditional fetch, dual hashing, adaptive scheduling, distributed
  rate limiting, raw payload persistence, outbox emission.
- Dedup: title canonicalization, MinHash + LSH banding, gated scoring, canonical job / job source
  model, precision-recall gate in CI.
- Search: hybrid BM25 + kNN in one `_msearch`, RRF, bounded freshness decay, cursor pagination,
  filters applied to both legs.
- Referral: the full state machine with idempotent transitions, audit log, resume encryption and
  gated release, hard delete, expiry sweeper.
- Trust: work-email OTP verification with lease expiry, seeker quotas, referrer capacity,
  Wilson-bound reputation.
- Authentication: BCrypt passwords, self-issued HS256 bearer tokens, three authorization tiers,
  and an acting identity that always comes from the token rather than the request body.
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
- **Tokens are symmetric and self-issued.** One process issues and verifies them, so an
  asymmetric key pair would buy nothing but key distribution. Federating with a real identity
  provider means replacing `AuthConfig`'s decoder and nothing else.
- **Authorization is coarse.** Three tiers — public, authenticated, administrator — plus
  ownership checks inside handlers. There is no per-company delegation, so an administrator is an
  administrator everywhere.
- **A role change waits for the token to expire.** Roles ride in the token so an authorization
  decision needs no database read; the cost is up to 12 hours of staleness. That is the reason
  the TTL is hours rather than weeks, and a revocation list is the obvious next step.
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

Six kinds of test, each answering a question the others cannot.

| kind | count | needs Docker |
|---|---:|---|
| Unit | 293 | no |
| Property-based (jqwik) | 46 | no |
| HTTP contract (MockMvc) | 43 | no |
| Architecture (ArchUnit) | 18 | no |
| Integration and end-to-end (Testcontainers) | 45 | yes |
| **Total** | **445** | |

| module | tests | passed | skipped | failed |
|---|---:|---:|---:|---:|
| app | 68 | 61 | 7 | 0 |
| common | 35 | 25 | 10 | 0 |
| dedup | 105 | 105 | 0 | 0 |
| ingestion | 45 | 34 | 11 | 0 |
| referral | 108 | 97 | 11 | 0 |
| search | 52 | 46 | 6 | 0 |
| trust | 32 | 32 | 0 | 0 |
| **TOTAL** | **445** | **400** | **45** | **0** |

The 45 skipped are the Docker-gated ones; on this machine no daemon is available, so they are
verified in CI. Everything else runs everywhere.

### Unit — 293

Example-based tests over pure logic: the adaptive scheduler, the title normalizer, MinHash and
LSH banding, reciprocal rank fusion, freshness decay, the Wilson bound, the scoring gates.
No mocks of things this code owns, because a test that asserts a mock was called asserts nothing
about behaviour.

Two run against real sockets rather than test doubles: `ConditionalFetcherTest` serves HTTP from
the JDK's own server to prove a 304 transfers no body, and `ConceptEmbeddingTest` proves the
zero-token-overlap claim at the vector-space level so it is verified on every build rather than
only where Docker exists.

### Property-based (jqwik) — 46

Invariants over generated inputs. These catch the input shapes nobody thinks to write a test for:

- **any** walk through the referral state machine stays on the graph, terminates within three
  steps, and never escapes a terminal state;
- **any** combination of queue length, pool size and capacities leaves no referrer over-assigned
  and no request placed twice;
- **any** string survives normalization without throwing, and normalizing twice equals
  normalizing once — the property a re-parse of stored raw payloads depends on;
- **any** posting rate and backoff count produces a crawl interval inside its configured bounds;
- **any** counter combination produces a Wilson bound inside [0, 1] that never exceeds the
  observed rate.

One of these found a real contract error: exponential freshness decay underflows to exactly
zero past roughly 1,075 half-lives, so the Javadoc's `(0, 1]` was wrong. The behaviour is
correct — a posting that old is certainly filled — but the documented contract was not.

### Architecture (ArchUnit) — 18

The module graph the README claims, enforced. Gradle stops a module depending on another at the
build-file level, but nothing stops a package reaching into a neighbour's internals once both are
on one classpath — which, in a modular monolith, they always are.

Beyond the dependency rules: controllers live only in `api` packages and are never called from
services; `*Store` classes are `@Repository`; `*Properties` classes are bound configuration;
every `DomainEvent` is a record; there is no field injection; and **no feature module may touch
`KafkaTemplate` directly**, which is what keeps the transactional outbox from being quietly
bypassed.

### HTTP contract (MockMvc) — 43

The wire contract: status codes, the single `ApiError` shape, validation messages, header
handling. These proved that an unsupported ATS is rejected before anything is written, that
`Retry-After` is populated on a 429, that a resume download is `no-store` and an attachment, and
that an internal failure returns `Something went wrong` rather than a connection string.

These found two real bugs. `@Min`/`@Max` on the search endpoint were **dead**, because the
controller lacked `@Validated` — an oversized page was silently clamped instead of refused, and a
validation annotation that does nothing is worse than none because it reads like protection. And
a wrong password returned **500**, because `BadCredentialsException` fell through to the catch-all
handler.

They also carry the authorization rules. The real `SecurityConfig` is imported rather than stubbed
away: a web-slice test that disables security proves the handler works for a caller who was never
checked, which is precisely the property that was wrong before authentication existed.

### Integration and end-to-end (Testcontainers) — 45

Real Postgres, Redis, Kafka, OpenSearch and MinIO. Not optional: CI always has a daemon and
always runs them. `@RequiresDocker` skips them on a machine without one so `./gradlew test` is
honestly green rather than a wall of connection errors that trains people to ignore output.
Force them locally with `make verify`.

What they prove, as opposed to what a mock would:

- `FOR UPDATE SKIP LOCKED` gives two concurrent relays disjoint batches — no in-memory database
  implements this faithfully;
- a rolled-back business transaction leaves no outbox row;
- 20 threads cannot collectively exceed one host's token bucket;
- a 304, and separately a validator-less resend of identical bytes, cost no payload row and no
  event;
- a cosmetically reordered board response produces new bytes and zero events;
- replaying a referral transition with the same idempotency key writes one audit row, not two,
  and does not inflate reputation counters.

`EndToEndPipelineIT` runs one job the whole way: stub ATS board → crawl → three postings stored
with three outbox events → dedup collapses a repost into one canonical job with two sources →
index → search finds it by paraphrase → resume encrypted into MinIO → referral requested,
accepted, resume released through a signed link, submitted, closed → resume hard-deleted while
the referral survives. The only stub is the ATS board itself, because a test that depends on a
third party's live listings fails for reasons unrelated to this code.

### Mutation (PIT) — opt-in

Line coverage says a line ran. A mutation score says a test would have *noticed* if that line
were wrong, which is the property that matters for decision logic.

| module | killed / covered | score |
|---|---:|---:|
| dedup | 187 / 213 | 87% |
| referral | 102 / 119 | 85% |
| trust | 65 / 70 | 93% |
| search | 91 / 113 | 80% |

This changed the tests rather than just measuring them. The first run scored dedup at 75% and
referral at 72%, with survivors concentrated in `locationGate`, `locationScore`, `titleScore`,
`seniorityFit` and `jaccard` — branches reachable only through a final aggregate score, where
several different wrong constants produce the same decision on any given example. That is exactly
how a threshold ends up silently off by a rung. Adding direct branch-level tests moved dedup to
87% and referral to 85%.

Writing those tests also caught a false belief of my own: an assertion that responsiveness
outweighs org affinity, when both are deliberately weighted 0.30.

The remaining un-covered mutations are in services and stores whose tests are Docker-gated and
therefore excluded from PIT. Run with `make mutation`; it is scheduled weekly in CI rather than
blocking a pull request, because it re-runs the suite once per surviving mutant.

### Load

`make loadtest` seeds a deterministic synthetic corpus through the real services — so the rows,
canonical jobs and index entries are what a crawl would have produced — and then drives
concurrent searches, reporting p50/p95/p99/p999 and throughput. It measures what a caller
experiences, including the OpenSearch round trip, which the JMH suite deliberately excludes.

`make seed-corpus` does the seeding alone. Both are gated behind explicit flags; neither is
something to point at a real database.

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

The thing I would fix first, before any of this, is **token revocation**. Roles and identity ride
in a self-signed token so that authorizing a request costs no database read, which is the right
trade at this size — but it means removing someone's access takes effect only when their token
expires. At a 12 hour TTL that is a real window. A short-lived access token with a refresh token
checked against a revocation list is the standard answer, and it is the first thing that should
change if this ever held real accounts.

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
| [8](docs/adr/0008-bounded-freshness-decay.md) | Freshness decay is bounded, because RRF scores are flat |

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
