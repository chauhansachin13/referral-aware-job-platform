# 4. LSH retrieval plus gated exact scoring

- Status: Accepted
- Date: 2026-08-29

## Context

The same job appears on a company's Greenhouse board, on an aggregator, and again next quarter
under a new requisition id. Showing all three as separate results makes the product worse than
the boards it aggregates.

Comparing each new posting against every canonical job is quadratic in corpus size. At 200,000
canonical jobs and a few thousand ingested postings a day, that is billions of set comparisons.

## Decision

Two stages with different jobs.

**Retrieval** — MinHash signatures (128 permutations) banded into 16 bands of 8 rows. Two
postings become candidates when they agree on every row of at least one band. Cheap, indexed by
`(company_id, band_index, band_hash)`, and deliberately over-inclusive.

**Scoring** — over a bounded shortlist only. Candidates are ranked by matched bands and then by
their MinHash estimate, both computed from data already in memory, and only the top 25 pay for
an exact Jaccard.

The scorer is *not* a plain weighted sum. Weighted terms for Jaccard, title, company and location
are computed, and then multiplied by hard gates on company identity, ladder distance and
remote-versus-onsite.

## Alternatives considered

**Exact pairwise comparison.** Correct and unusable: see the linear-scan baseline in
`DedupBenchmark`, which is measured in milliseconds per two thousand comparisons.

**A pure weighted sum, no gates.** This is what was built first, and it failed on the labelled
set at 0.65 precision. Two postings at the same company with identical text but two rungs apart
on the ladder scored 0.924. A different company scored 0.850. The problem is structural: to make
"different company" outweigh a 0.97 Jaccard and an identical title, its weight has to be so large
that no other term can move the result. A linear model cannot express a constraint.

**An embedding model plus a vector index.** Better recall on genuinely reworded reposts.
Needs an embedding model at ingestion time and a second index to keep consistent with the first.
Deferred; the interface for it already exists in the search module.

## Consequences

- Precision and recall are gated in CI against a labelled fixture set. The build fails if
  precision drops below 0.95.
- The banding parameters are configuration, so re-tuning is an experiment rather than a change.
  `GET /api/v1/dedup/banding` returns the retrieval curve so the choice can be inspected.
- **The labelled set is 24 pairs.** That is enough to catch a regression on known-hard cases and
  is not a claim about production accuracy. It can be overfitted by tuning weights until it
  passes, and the honest mitigation is that every negative pair shares company boilerplate and
  several share a title, level or location — the set is adversarial rather than easy.
- Candidates are scoped to one company, which is both a correctness statement and the reason the
  band lookup stays fast.
