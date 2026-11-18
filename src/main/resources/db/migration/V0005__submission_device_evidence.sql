-- Phase B: Per-submission device evidence for payment auditability.
-- Read-only snapshot of what captur-api returned at sync time.
-- No changes to reward calculation, eligibility, or payment logic.

CREATE TABLE submission_device_evidence (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  submission_id       UUID NOT NULL,
  captur_user_id      UUID NOT NULL,

  -- Device provenance
  device_model        TEXT,
  sync_tier           TEXT,            -- captur-api's tier label (BRONZE/SILVER/GOLD/PLATINUM/DIAMOND)

  -- IMU integrity signals
  imu_frequency_hz    DOUBLE PRECISION,  -- captur-api reports this as JSON number
  gyro_sample_count   INT,
  accel_sample_count  INT,
  gyro_stream_url     TEXT,
  accel_stream_url    TEXT,

  -- Media / capture parameters
  video_url           TEXT,
  duration_seconds    INT,
  metadata_urls       JSONB,           -- captur-api metadataUrls object

  -- Full raw snapshot for future auditing
  raw_metadata        JSONB,           -- captur-api metadata blob as-is

  -- Evidence review state (manual admin flag, not used for eligibility)
  evidence_status     TEXT NOT NULL DEFAULT 'CAPTURED'
                      CHECK (evidence_status IN ('CAPTURED','REVIEWED','FLAGGED')),

  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- One evidence row per submission — idempotent insert
  CONSTRAINT uq_evidence_submission UNIQUE (submission_id),

  -- FK to reward ledger so evidence can't exist without a reward row
  CONSTRAINT fk_evidence_reward FOREIGN KEY (submission_id)
    REFERENCES usdc_reward_ledger(submission_id)
    ON DELETE CASCADE
);

CREATE INDEX idx_evidence_user ON submission_device_evidence(captur_user_id);
CREATE INDEX idx_evidence_tier ON submission_device_evidence(sync_tier);
CREATE INDEX idx_evidence_status ON submission_device_evidence(evidence_status);
