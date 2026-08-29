# 7. A deterministic offline embedding model

- Status: Accepted
- Date: 2026-08-29

## Context

Hybrid retrieval needs a dense vector per job and per query. The requirement that motivates it:
a query with zero token overlap — "k8s" against a posting that says "container orchestration" —
should still retrieve the right job. BM25 scores that pair at exactly zero.

## Decision

`ConceptHashingEmbeddingModel`: a signed random projection into 256 dimensions over two feature
spaces — the literal tokens of the text, and the `JobDomainOntology` concepts those tokens map
to, with concepts weighted four times higher.

## Alternatives considered

**A hosted embedding API.** Best quality. Requires an API key, which breaks the "runs from one
compose file with no credentials" property, and puts a network call on the indexing path.

**A bundled ONNX sentence encoder.** No API key and genuinely semantic. Costs roughly 90 MB in
the repository, a heavier inference path, and a much slower test suite.

**BM25 only.** Half the design, and it fails the requirement that motivated the design.

## Consequences

- **This is not a neural encoder, and the README says so.** It cannot represent word order or
  negation, and it knows exactly the synonyms in the curated lexicon and no others. Calling its
  output "semantic" would be overselling it.
- What it does do is make zero-overlap retrieval work for this domain, which is the property the
  product needs. `ConceptEmbeddingTest` proves it at the vector-space level on every build, with
  no Docker required, and the integration test confirms it survives the round trip through
  OpenSearch.
- Deterministic and dependency-free, so a signature computed today matches one computed last
  month and the index does not need a rebuild after a restart.
- `EmbeddingModel` exists precisely so that swapping in a real encoder is one class plus a
  reindex. `dimensions()` is part of the contract and the indexer asserts on it.
