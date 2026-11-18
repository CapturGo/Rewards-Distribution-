-- CapturGO Payments Service — initial schema
-- All tables are owned by this service. No captur-api tables are modified.
-- Money is stored in USDC base units (BIGINT, 6 decimals: $1 = 1_000_000).

CREATE TABLE contributor_wallets (
  user_id          UUID PRIMARY KEY,
  solana_address   TEXT NOT NULL,
  privy_wallet_id  TEXT,
  verification     TEXT NOT NULL DEFAULT 'UNVERIFIED'
                   CHECK (verification IN ('UNVERIFIED','VERIFIED','INVALID')),
  verified_at      TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE usdc_reward_ledger (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  submission_id      UUID NOT NULL,
  captur_user_id     UUID NOT NULL,
  activity_id        UUID,
  activity_title     TEXT,
  amount_usdc_units  BIGINT NOT NULL CHECK (amount_usdc_units > 0),
  reward_currency    TEXT NOT NULL DEFAULT 'USDC',
  campaign_round     INT,
  status             TEXT NOT NULL DEFAULT 'AVAILABLE'
                     CHECK (status IN ('PENDING','AVAILABLE','RESERVED_FOR_PAYOUT','PAID','REVERSED')),
  source             TEXT NOT NULL DEFAULT 'CAPTUR_API_POLL',
  metadata           JSONB,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (submission_id)
);
CREATE INDEX idx_reward_ledger_user ON usdc_reward_ledger(captur_user_id, status);
CREATE INDEX idx_reward_ledger_status ON usdc_reward_ledger(status);

CREATE TABLE payout_batches (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  status           TEXT NOT NULL DEFAULT 'PENDING_APPROVAL'
                   CHECK (status IN ('CREATED','PENDING_APPROVAL','APPROVED','PROCESSING',
                                     'SUBMITTED','CONFIRMED','FAILED','CANCELLED',
                                     'REQUIRES_RECONCILIATION')),
  total_usdc_units BIGINT NOT NULL,
  item_count       INT NOT NULL,
  cluster          TEXT NOT NULL DEFAULT 'devnet',
  idempotency_key  TEXT NOT NULL,
  created_by       UUID NOT NULL,
  approved_by      UUID,
  approved_at      TIMESTAMPTZ,
  executed_at      TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (idempotency_key)
);

CREATE TABLE payout_batch_items (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  batch_id          UUID NOT NULL REFERENCES payout_batches(id),
  reward_id         UUID NOT NULL REFERENCES usdc_reward_ledger(id),
  captur_user_id    UUID NOT NULL,
  solana_address    TEXT NOT NULL,
  amount_usdc_units BIGINT NOT NULL CHECK (amount_usdc_units > 0),
  status            TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','PROCESSING','SUBMITTED','CONFIRMED',
                                      'FAILED','REQUIRES_RECONCILIATION')),
  tx_signature      TEXT,
  failure_reason    TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (reward_id)
);
CREATE INDEX idx_batch_items_batch ON payout_batch_items(batch_id);
CREATE INDEX idx_batch_items_status ON payout_batch_items(status);

CREATE TABLE payout_transactions (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  batch_item_id    UUID NOT NULL REFERENCES payout_batch_items(id),
  tx_signature     TEXT NOT NULL,
  cluster          TEXT NOT NULL,
  slot             BIGINT,
  fee_usdc_units   BIGINT,
  status           TEXT NOT NULL DEFAULT 'SUBMITTED'
                   CHECK (status IN ('SUBMITTED','CONFIRMED','FAILED','DROPPED')),
  block_time       TIMESTAMPTZ,
  raw_response     JSONB,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tx_signature, cluster)
);
CREATE INDEX idx_payout_tx_item ON payout_transactions(batch_item_id);

CREATE TABLE payout_audit_logs (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  actor_user_id UUID,
  actor_email   TEXT,
  action        TEXT NOT NULL,
  entity_type   TEXT NOT NULL,
  entity_id     UUID,
  before_state  JSONB,
  after_state   JSONB,
  ip_address    TEXT,
  user_agent    TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payout_audit_entity ON payout_audit_logs(entity_type, entity_id);

CREATE TABLE poller_checkpoints (
  id              TEXT PRIMARY KEY,
  last_offset     INT NOT NULL DEFAULT 0,
  last_run_at     TIMESTAMPTZ,
  status          TEXT NOT NULL DEFAULT 'IDLE',
  last_error      TEXT,
  metadata        JSONB
);
