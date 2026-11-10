-- What the rules have seen, keyed by transfer: a redelivered event is recognised and ignored.
CREATE TABLE observed_transfers (
    transfer_id  uuid PRIMARY KEY,
    account_id   uuid        NOT NULL,
    amount_minor bigint      NOT NULL,
    currency     char(3)     NOT NULL,
    settled      boolean     NOT NULL,
    occurred_at  timestamptz NOT NULL
);

CREATE INDEX observed_transfers_window ON observed_transfers (account_id, occurred_at);

CREATE TABLE alerts (
    id          uuid PRIMARY KEY,
    account_id  uuid        NOT NULL,
    rule        text        NOT NULL,
    detail      text        NOT NULL,
    transfer_id uuid        NOT NULL REFERENCES observed_transfers (transfer_id),
    occurred_at timestamptz NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX alerts_by_account ON alerts (account_id, rule, occurred_at);
