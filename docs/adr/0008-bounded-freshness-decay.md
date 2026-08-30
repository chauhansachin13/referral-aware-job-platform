# 8. Freshness decay is bounded, because RRF scores are flat

- Status: Accepted
- Date: 2026-08-29
- Supersedes part of [ADR 4's](0004-two-stage-deduplication.md) sibling decision in the search
  module; corrects the original design in this repository's slice 4.

## Context

Search fuses BM25 and kNN with reciprocal rank fusion, then applies an exponential freshness
decay to the fused score. That was the original design and it is wrong in a way that only shows
up against a real index.

Reciprocal rank fusion produces a deliberately **flat** score distribution. That is its whole
value: it discards score magnitudes, which are unbounded and corpus-dependent for BM25 and
narrow for cosine, and keeps only ordinal position. The consequence is that adjacent ranks are
very close together. At the standard `k = 60`, rank 1 scores `1/61 = 0.01639` and rank 2 scores
`1/62 = 0.01613` — a gap of 1.6%.

An exponential decay, by contrast, spans the entire range from 1.0 to 0. Multiplying one by the
other means the decay dominates completely:

| document | rank | age | fused | raw decay | product |
|---|---:|---:|---:|---:|---:|
| perfect match | 1 | 6 months | 0.01639 | 0.0026 | 0.0000426 |
| poor match | 50 | today | 0.00901 | 0.98 | 0.00883 |

The fresh, largely irrelevant document wins by more than two hundred times. Recency has stopped
being a tie-breaker and become the ranking.

This was caught by an integration test against a real OpenSearch, not by reasoning. The unit
tests passed because they used hand-picked fused scores that differed by 2x — a gap RRF never
actually produces between neighbouring results.

## Decision

Bound how much of a document's retrieval score recency may remove:

```
multiplier = 1 - maxPenalty * (1 - 0.5^(age / halfLife))
```

With `maxPenalty = 0.4`, the multiplier lives in `[0.6, 1.0]`. An arbitrarily old document loses
at most 40% of its retrieval score, which at `k = 60` corresponds to a bounded number of
positions rather than an unbounded collapse.

Half-life is retained as the product-facing knob, because "a two-week-old posting is worth half
a fresh one" is still the sentence someone can argue with. `maxPenalty` answers a different and
equally necessary question: how much is recency allowed to matter *at all*.

## Alternatives considered

**Leave it unbounded.** Defensible only if a six-month-old posting is always worthless, which is
close to true for job search but not true enough to let a rank-50 match beat a rank-1 one.

**Make recency a third ranked list and fuse all three with RRF.** More principled — it keeps
every signal on one scale and avoids multiplying across scales at all. Rejected because it makes
half-life decorative (ordering by decay is just ordering by age), and because on a corpus where
relevance genuinely separates documents, a weighted third list needs a weight large enough to
matter and small enough not to dominate — the same tuning problem, moved.

**Shrink `k` so ranks separate more.** Changes the size of the gap without changing the shape of
the problem, and gives up the damping that makes RRF robust in the first place.

## Consequences

- Relevance decides wide rank gaps; recency decides near-ties. That is the behaviour the feature
  was always meant to have.
- Two more knobs interact (`freshnessHalfLife` and `freshnessMaxPenalty`), and their combined
  effect is easiest to reason about as "how many positions can age move a result".
- The integration test corpus now holds every document at the same age except the one pair whose
  purpose is to differ in age, so each test varies exactly one thing. On a four-document corpus
  everything is within a few ranks of everything else, and an age difference would otherwise
  decide the relevance tests too — they would be measuring the decay rather than the retrieval.
