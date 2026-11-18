-- Add reward-calculation audit fields to usdc_reward_ledger (spec §3).
-- All nullable: rows created before this migration keep working.

ALTER TABLE usdc_reward_ledger
  ADD COLUMN device_tier             TEXT,
  ADD COLUMN device_model            TEXT,
  ADD COLUMN hourly_rate_usdc_units  BIGINT,   -- rate per hour in USDC base units
  ADD COLUMN eligible_duration_seconds INT,
  ADD COLUMN calculation_version     TEXT;

CREATE INDEX idx_reward_ledger_tier ON usdc_reward_ledger(device_tier);
