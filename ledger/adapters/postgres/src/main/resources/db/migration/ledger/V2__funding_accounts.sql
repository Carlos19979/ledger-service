-- The bank's side of every deposit. Fixed ids so environments and tests agree on them.
INSERT INTO accounts (id, owner, type, currency, balance_minor, status, daily_debit_limit_minor, version, opened_at)
VALUES ('00000000-0000-0000-0000-000000000978', 'Funding account (EUR)', 'INTERNAL', 'EUR', 0, 'ACTIVE', NULL, 0, now()),
       ('00000000-0000-0000-0000-000000000840', 'Funding account (USD)', 'INTERNAL', 'USD', 0, 'ACTIVE', NULL, 0, now()),
       ('00000000-0000-0000-0000-000000000826', 'Funding account (GBP)', 'INTERNAL', 'GBP', 0, 'ACTIVE', NULL, 0, now());
