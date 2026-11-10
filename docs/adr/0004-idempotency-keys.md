# ADR 0004 — `Idempotency-Key` on every money movement

**Status:** accepted

## Context
Networks fail after the server has acted. A client that retries a transfer must not pay twice; a client that
double-clicks must see one transfer.

## Decision
`POST /transfers` and `POST /accounts/{id}/deposits` require an `Idempotency-Key` header. In the same transaction
as the transfer, the use case claims `(scope, key)` with `INSERT … ON CONFLICT DO NOTHING`, storing a SHA-256 of the
canonical request and the transfer id.

- **Same key, same request:** the stored transfer is returned with `Idempotent-Replayed: true`, even if it was a
  rejection.
- **Same key, different request:** `422 idempotency-key-reused`.
- **Concurrent duplicates:** the second insert waits for the first transaction. If that commits, the second replays
  its result; if it rolls back, the second goes ahead.
- **Not-found errors** roll the claim back, so the key can be used again.

The claim's foreign key to `transfers` is `DEFERRABLE INITIALLY DEFERRED`: the key is claimed before the transfer
row exists, and integrity is checked at commit.

## Consequences
Eight parallel requests with one key produce exactly one transfer (`ConcurrencySpec`). Keys are kept forever here;
a real system would expire them after a retention window agreed with clients.
