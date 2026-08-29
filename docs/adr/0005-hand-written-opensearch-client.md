# 5. Hand-written OpenSearch REST client

- Status: Accepted
- Date: 2026-08-29

## Context

The search module needs to create an index with a `knn_vector` mapping, index documents, and run
a hybrid query with a filtered kNN leg. OpenSearch ships a generated typed Java client.

## Decision

Talk to OpenSearch over its REST API using Spring's `RestClient` and explicit JSON built with
Jackson's `ObjectNode`.

## Alternatives considered

**`opensearch-java` typed client.** The obvious choice, and it is what a team on a long-lived
codebase should probably use. Rejected here for three reasons. Its builder API changed shape
across 2.x and 3.x, so pinning it is a maintenance commitment. It pulls a substantial transitive
tree for a module that makes four kinds of request. And it hides the query behind a fluent facade
at exactly the point where the query *is* the design — the hybrid retrieval strategy, the field
boosts and the kNN filter are the interesting part of this module, and they are worth reading as
the JSON that actually goes over the wire.

**Spring Data OpenSearch.** Adds a repository abstraction over a problem that is not
repository-shaped. Hybrid retrieval with application-side fusion does not fit the interface.

## Consequences

- The query DSL is visible and reviewable in `QueryBuilder`, and its tests assert on the emitted
  JSON — including that both retrieval legs carry identical filters, which is a real bug class:
  an unfiltered kNN leg fuses on-site jobs into a "remote only" search.
- Both retrievers are issued as one `_msearch`, so hybrid retrieval costs the same number of
  round trips as a single-retriever search.
- The cost is real: no compile-time checking of the query, and a mapping change that breaks a
  query is caught by the integration test rather than by the compiler. Accepted because the
  integration test exists and runs on every CI build.
