-- One database per service: the consumers never read the ledger's tables.
CREATE DATABASE activity;
CREATE DATABASE fraud;
