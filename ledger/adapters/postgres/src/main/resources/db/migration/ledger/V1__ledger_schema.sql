-- The ledger's source of truth. Rules the domain already enforces are repeated here on purpose:
-- if a bug ever slips past the code, the database refuses to store a broken ledger.

CREATE TABLE accounts (
    id                      uuid PRIMARY KEY,
    owner                   text        NOT NULL,
    type                    text        NOT NULL CHECK (type IN ('CUSTOMER', 'INTERNAL')),
    currency                char(3)     NOT NULL,
    balance_minor           bigint      NOT NULL,
    status                  text        NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED')),
    daily_debit_limit_minor bigint CHECK (daily_debit_limit_minor > 0),
    version                 bigint      NOT NULL,
    opened_at               timestamptz NOT NULL,
    CONSTRAINT customer_balance_not_negative CHECK (type = 'INTERNAL' OR balance_minor >= 0)
);

-- Exactly one funding account per currency: every deposit's counterpart.
CREATE UNIQUE INDEX one_funding_account_per_currency ON accounts (currency) WHERE type = 'INTERNAL';

CREATE TABLE transfers (
    id                uuid PRIMARY KEY,
    kind              text        NOT NULL CHECK (kind IN ('TRANSFER', 'DEPOSIT')),
    source_account_id uuid        NOT NULL REFERENCES accounts (id),
    target_account_id uuid        NOT NULL REFERENCES accounts (id),
    amount_minor      bigint      NOT NULL CHECK (amount_minor > 0),
    currency          char(3)     NOT NULL,
    reference         text CHECK (length(reference) <= 140),
    status            text        NOT NULL CHECK (status IN ('SETTLED', 'REJECTED')),
    rejection_code    text,
    rejection_detail  text,
    created_at        timestamptz NOT NULL,
    CONSTRAINT rejection_only_when_rejected CHECK ((status = 'REJECTED') = (rejection_code IS NOT NULL))
);

CREATE INDEX transfers_by_source ON transfers (source_account_id, created_at);

CREATE TABLE ledger_entries (
    sequence            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id                  uuid        NOT NULL UNIQUE,
    account_id          uuid        NOT NULL REFERENCES accounts (id),
    transfer_id         uuid        NOT NULL REFERENCES transfers (id),
    direction           text        NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor        bigint      NOT NULL CHECK (amount_minor > 0),
    currency            char(3)     NOT NULL,
    balance_after_minor bigint      NOT NULL,
    account_version     bigint      NOT NULL,
    posted_at           timestamptz NOT NULL,
    UNIQUE (account_id, account_version)
);

CREATE INDEX ledger_entries_statement ON ledger_entries (account_id, sequence DESC);
CREATE INDEX ledger_entries_debits_by_day ON ledger_entries (account_id, posted_at) WHERE direction = 'DEBIT';

-- History is append-only: corrections are new transfers, never edits.
CREATE FUNCTION forbid_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END
$$;

CREATE TRIGGER ledger_entries_append_only BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER ledger_entries_no_truncate BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER transfers_append_only BEFORE UPDATE OR DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER transfers_no_truncate BEFORE TRUNCATE ON transfers
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- Double entry, checked at commit: every transfer has exactly one debit and one credit that net to zero.
CREATE FUNCTION assert_transfer_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    net     bigint;
    entries integer;
BEGIN
    SELECT coalesce(sum(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0), count(*)
    INTO net, entries
    FROM ledger_entries
    WHERE transfer_id = NEW.transfer_id;

    IF net <> 0 OR entries <> 2 THEN
        RAISE EXCEPTION 'transfer % is unbalanced: net %, % entries', NEW.transfer_id, net, entries;
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transfer_balanced();

-- A key is claimed before its transfer row exists, so the reference is only checked at commit.
CREATE TABLE idempotency_keys (
    scope           text        NOT NULL,
    idempotency_key text        NOT NULL,
    request_hash    text        NOT NULL,
    transfer_id     uuid        NOT NULL REFERENCES transfers (id) DEFERRABLE INITIALLY DEFERRED,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, idempotency_key)
);

CREATE TABLE outbox (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       uuid        NOT NULL UNIQUE,
    topic          text        NOT NULL,
    message_key    text        NOT NULL,
    event_type     text        NOT NULL,
    schema_version integer     NOT NULL,
    payload        jsonb       NOT NULL,
    occurred_at    timestamptz NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz,
    attempts       integer     NOT NULL DEFAULT 0,
    last_error     text
);

CREATE INDEX outbox_pending ON outbox (id) WHERE published_at IS NULL;
