CREATE INDEX IF NOT EXISTS idx_transactions_wallet_id_created_at
    ON transactions (wallet_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_transactions_idempotency_key
    ON transactions (idempotency_key)
    WHERE idempotency_key IS NOT NULL;  -- partial index: only rows that have a key

CREATE INDEX IF NOT EXISTS idx_wallets_player_id
    ON wallets (player_id);             -- already UNIQUE, but explicit is cleaner