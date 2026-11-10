# ADR 0005 — Transactional outbox with a single-publisher relay

**Status:** accepted

## Context
Other services need to know about every posted entry and every transfer outcome. Writing to PostgreSQL and then
to Kafka is a dual write. If Kafka fails after the commit, the event is lost; if we publish first and then roll
back, we announce money that never moved.

## Decision
Events are inserted into an `outbox` table in the same transaction as the ledger change. A relay polls the table,
publishes with `acks=all` and an idempotent producer, waits for the acknowledgement, and then marks the rows as
published.

- **Single publisher:** the relay takes `pg_try_advisory_xact_lock`, so with several instances only one publishes at
  a time and per-key order matches commit order.
- **Stop on failure:** the relay stops at the first failed message instead of skipping it, so nothing overtakes it.
- **At-least-once delivery:** a crash between Kafka's ack and the commit republishes the batch. Consumers
  deduplicate by event id.
- **Visible backlog:** `ledger.outbox.pending` is exported to Prometheus and is the metric to alert on.

## Alternatives considered
- **CDC with Debezium:** reads the WAL with lower latency and no polling. It adds Kafka Connect to operate. It is the
  right move once publish volume or latency matters; the outbox table stays the same, only the relay is replaced.
- **Kafka transactions:** they cannot span the PostgreSQL transaction, so they do not solve the dual write.

## Consequences
`KafkaOutageTest` pauses the Kafka container mid-test. Transfers keep succeeding, the backlog grows, and once Kafka
is unpaused every event is delivered.
