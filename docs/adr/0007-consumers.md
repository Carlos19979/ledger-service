# ADR 0007 — Consumers are independent, idempotent and order-tolerant

**Status:** accepted

## Context
Two services read the ledger's events: an activity read model and fraud screening. Kafka delivers at least once,
and ordering is guaranteed only per partition.

## Decision
- **Shared contract only:** each consumer is its own Spring Boot service with its own database and consumer group,
  and depends only on `contracts:events`. Architecture tests forbid importing ledger code.
- **Idempotent:** every event is recorded by id (or transfer id) before it is applied, in the same transaction.
- **Order-tolerant:**
  - The projection's totals are commutative.
  - The balance is taken from the entry with the highest `accountVersion`, so a late, older event never overwrites
    a newer balance.
  - Fraud rules use event time (`occurredAt`), so a backlog replayed after an outage is judged as it happened.
- **Forward-compatible:** unknown event types are skipped, so the ledger can add events without coordinating.
- **Poison messages:** unreadable payloads or unsupported schema versions go to a per-consumer dead-letter topic
  instead of blocking the partition. Transient errors are retried in place.

## Consequences
- **Rebuildable read model:** the projection can be rebuilt by resetting its consumer group offsets to the
  beginning:
  ```
  kafka-consumer-groups --bootstrap-server … --group activity-projection --reset-offsets --to-earliest --all-topics --execute
  ```
  (after truncating its tables).
- **One alert per burst:** fraud rules have a cooldown per account and rule, so a burst raises one alert, not one per
  transfer.
