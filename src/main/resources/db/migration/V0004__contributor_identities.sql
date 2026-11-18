-- Trusted Privy DID → captur-api user mapping for contributor endpoints.
-- Populated only after captur-api verifies the caller's token against the
-- claimed userId (users/me ownership check). Never populated from client
-- input alone.

CREATE TABLE contributor_identities (
  privy_user_id   TEXT PRIMARY KEY,
  captur_user_id  UUID NOT NULL,
  verified_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
