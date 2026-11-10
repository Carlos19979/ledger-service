-- A disposable read model: everything here can be rebuilt by replaying ledger.entries.v1 from the start.

CREATE TABLE account_activity (
    account_id           uuid PRIMARY KEY,
    currency             char(3)     NOT NULL,
    balance_minor        bigint      NOT NULL,
    last_account_version bigint      NOT NULL,
    credits_minor        bigint      NOT NULL,
    debits_minor         bigint      NOT NULL,
    entry_count          bigint      NOT NULL,
    last_posted_at       timestamptz NOT NULL
);

-- At-least-once delivery means duplicates: an event is applied only if its id is new.
CREATE TABLE processed_events (
    event_id     uuid PRIMARY KEY,
    processed_at timestamptz NOT NULL DEFAULT now()
);
