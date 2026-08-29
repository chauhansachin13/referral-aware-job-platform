# 3. Adaptive crawl intervals, not a fixed schedule

- Status: Accepted
- Date: 2026-08-29

## Context

Crawl frequency is a single knob with two opposite failure modes, and any fixed value gets both
wrong at once.

A company posting thirty roles a day, crawled every six hours, means seekers see openings a day
late — in a market where the first fifty applicants get read. A ten-person startup posting twice
a year, crawled every six hours, spends 1,460 requests a year to learn nothing, on someone else's
infrastructure.

## Decision

Derive each board's interval from its own observed posting rate, smoothed with an EWMA, and back
it off multiplicatively while it keeps coming back unchanged.

```
base     = clamp(86400 * targetPostingsPerCrawl / observedPostingsPerDay, min, max)
interval = clamp(base * backoffFactor ^ min(consecutiveUnchanged, maxSteps), min, max)
```

`AdaptiveInterval` is a pure function of its inputs, with no clock, repository or Spring context.

## Alternatives considered

**Fixed interval per board, set by hand.** Works until there are more than about twenty boards,
then becomes a configuration file nobody updates.

**A learned model over posting history.** Better in principle. Needs history the system does not
have on day one, and produces a scheduler whose behaviour cannot be explained to the person
being crawled.

**Crawl on webhook.** Correct where it exists. Greenhouse, Lever and Ashby's public board APIs do
not offer one, which is the whole reason this problem exists.

## Consequences

- Scheduling policy is exhaustively testable: nine unit tests cover busy boards, dead boards,
  both clamps, compounding backoff and rate decay, and they run in milliseconds.
- A board that goes quiet decays toward the maximum interval within a few weeks rather than
  staying hot forever.
- The EWMA smoothing factor is a tuning parameter with no principled default. 0.3 was chosen so a
  single quiet week moves the estimate noticeably without a single busy day dominating it.
- Redis holds the schedule and Postgres holds the state. A flushed Redis costs one burst of
  crawls, not a permanently stalled crawler — and the per-host token bucket keeps that burst
  from reaching any single ATS as a burst.
