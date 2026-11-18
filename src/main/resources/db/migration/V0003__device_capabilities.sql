-- Device capability registry (decision: payments-service-owned, designed to
-- accept capture-time hardware metadata later — spec §4).
-- A device is only payable when its capabilities are VERIFIED.

CREATE TABLE device_capabilities (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  -- normalized match key: lowercased device_model, or a hardware fingerprint
  -- supplied by the mobile app in future capture-time metadata
  device_key          TEXT NOT NULL,
  device_model        TEXT,
  tier                TEXT NOT NULL CHECK (tier IN ('FULL','IPHONE','BASIC')),
  -- capability evidence captured at registration time
  has_gyroscope       BOOLEAN,
  has_accelerometer   BOOLEAN,
  imu_frequency_hz    INT,
  camera_fov_degrees  INT,
  aspect_ratio_169    BOOLEAN,
  fps                 INT,
  verification        TEXT NOT NULL DEFAULT 'UNVERIFIED'
                      CHECK (verification IN ('UNVERIFIED','VERIFIED','INVALID')),
  source              TEXT NOT NULL DEFAULT 'MANUAL',
  notes               TEXT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (device_key)
);

CREATE INDEX idx_device_caps_tier ON device_capabilities(tier);
