# ADR 0001 — Hexagonal architecture, enforced by Gradle modules and tests

**Status:** accepted

## Context
A ledger's rules (double entry, no overdraft, limits) change for regulatory reasons, while its infrastructure
(database, broker, HTTP framework) changes for technical ones. Mixing both makes either change risky.

## Decision
Split the service into Gradle modules along the hexagon:

- `ledger:domain` — pure Kotlin. No Spring, no I/O.
- `ledger:application` — use cases and the ports they need (`AccountRepository`, `OutboxStore`, `MessageBroker`…).
- `ledger:adapters:{postgres,rest,kafka}` — one module per technology, implementing ports.
- `ledger:app` — the only place that knows all of them: explicit `@Bean` wiring and configuration.

The boundaries are enforced twice: by module dependencies (the domain module cannot even see Spring on its
classpath) and by Konsist tests in `architecture-tests` (for example: adapters never import each other, consumers
never import ledger code).

## Consequences
- The domain and application layers are tested in milliseconds without containers, using in-memory ports that
  live in `testFixtures` and are reused by the REST adapter's tests.
- Adding Kafka was a new adapter plus a port; no domain code changed.
- More modules and some wiring code than a single Spring Boot project. The wiring is one readable file.
