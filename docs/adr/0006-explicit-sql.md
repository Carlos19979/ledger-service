# ADR 0006 — Hand-written SQL with `JdbcClient`, not JPA

**Status:** accepted

## Context
The correctness of this service depends on exactly which rows are locked, in which order, and on constraints
that fire at commit. An ORM hides those behind flush timing and dirty checking.

## Decision
Repositories use Spring's `JdbcClient` with named parameters and map rows to domain objects by hand. The schema
is owned by Flyway migrations and carries its own guarantees:

- customer balances cannot be stored negative (`CHECK`);
- `ledger_entries` and `transfers` are append-only (triggers reject `UPDATE`, `DELETE` and `TRUNCATE`);
- a deferred constraint trigger rejects, at commit, any transfer whose entries are not one debit and one credit
  that net to zero.

## Consequences
Every query is visible and reviewable, and the database refuses a broken ledger even if application code had a
bug (`LedgerIntegritySpec`). The cost is mapping code; for six tables that is small.
