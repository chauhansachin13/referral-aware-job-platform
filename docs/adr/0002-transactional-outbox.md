# 2. Transactional outbox for every event

- Status: Accepted
- Date: 2026-08-29

## Context

Several flows must change state and tell the rest of the system about it: a crawl stores postings
and announces them, a referral transitions and notifies both parties. Doing the database write
and the Kafka publish as two independent operations means one of them can succeed alone.

Both failure directions are bad, and the quiet one is worse. Publishing before committing
produces events for state that never existed — dedup canonicalizes a posting that is not there.
Committing before publishing produces state nobody hears about — a job that is ingested,
searchable to nobody, forever.

## Decision

Every event is written to an `outbox_event` row inside the same transaction as the business
change. A relay polls the table, publishes to Kafka, and marks rows published.

`TransactionalEventPublisher` uses `Propagation.MANDATORY`: publishing outside a transaction
throws rather than silently writing a row that could be published without its business change.

The relay claims rows with `SELECT ... FOR UPDATE SKIP LOCKED`, so every replica runs one
without leader election.

## Alternatives considered

**Publish directly, accept the gap.** One less table, and a class of bug that appears only under
partial failure — which is to say, only in production and only under load.

**Kafka transactions with a Postgres XA coordinator.** Genuine atomicity across both. Also a
distributed transaction coordinator, two-phase commit latency on every write, and a new
operational failure mode. Not a reasonable trade for a system whose consumers can be made
idempotent instead.

**Debezium change data capture.** Excellent, and the right answer at scale. It needs a Kafka
Connect cluster, replication slot management and a schema-change process. Too much standing
infrastructure for a platform that otherwise runs from one compose file.

## Consequences

- Delivery is at-least-once, so every consumer is idempotent by construction. `processed_message`
  makes redelivery a no-op, keyed on the event's identity rather than on a Kafka offset.
- Publish latency is bounded by the poll interval (500 ms by default), not by the commit.
- The outbox is a queue, so it has a depth: `referralhub.outbox.pending` and
  `referralhub.outbox.poisoned` are exported as gauges, and rows past the attempt ceiling stop
  being retried so one poison row cannot consume the whole relay budget.
- The relay holds a transaction open across the Kafka send. A broker outage therefore ties up a
  connection for the send timeout. Bounded by `sendTimeout` and by the batch size.
