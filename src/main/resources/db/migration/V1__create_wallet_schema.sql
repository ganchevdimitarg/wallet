-- V1__create_wallet_schema.sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE wallets (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    player_id   UUID NOT NULL UNIQUE,
    balance     NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version     BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_wallets_player_id ON wallets(player_id);

CREATE TABLE transactions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id        UUID NOT NULL REFERENCES wallets(id),
    amount           NUMERIC(19, 4) NOT NULL,
    type             VARCHAR(20) NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAWAL')),
    idempotency_key  VARCHAR(255) UNIQUE,   -- UNIQUE constraint = DB-level duplicate prevention
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transactions_wallet_id ON transactions(wallet_id);
CREATE INDEX idx_transactions_created_at ON transactions(created_at DESC);
