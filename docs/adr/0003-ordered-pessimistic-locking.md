# ADR 0003 — Lock both accounts in id order, with a version check behind it

**Status:** accepted

## Context
A transfer reads two balances, decides, and writes both. Under concurrency, either two transfers read the same
balance and both succeed (lost update, overdraft), or they wait for each other forever when A→B and B→A run
together (deadlock).

## Decision
Inside the transfer transaction, lock both rows with
`SELECT … FROM accounts WHERE id IN (…) ORDER BY id FOR UPDATE`. PostgreSQL takes the locks in sort order, so every
transaction locks any pair the same way round and a cycle cannot form. Updates also check the version
(`… WHERE id = ? AND version = ?`) and fail loudly if it moved, as a second line of defence.

## Alternatives considered
- **Optimistic locking only:** no blocking, but every conflict is a retry. Hot accounts such as the funding account
  would retry constantly.
- **SERIALIZABLE isolation:** correct, but conflicts surface as serialization failures the caller must retry, and
  the reasoning is less explicit.
- **Single writer per account (actor/partitioned queue):** scales best, but turns a synchronous API into an
  asynchronous one.

## Consequences
`ConcurrencySpec` runs 600 random transfers on 16 threads between six accounts in both directions. It checks
that there are no deadlocks, that the sum of all balances stays zero, that no customer goes negative, and that
every balance equals the sum of its entries. The funding account is a hot row; at real scale it would be sharded
into several sub-accounts.
