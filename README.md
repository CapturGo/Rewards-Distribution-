# captur-payments

Independent Kotlin/Spring Boot service for CapturGO USDC rewards ledger and
Solana payout batches. Reads approved submissions from **captur-go-api**
(read-only, caller's Bearer token forwarded) and owns its own PostgreSQL
tables. Deployed on Cloud Run as `captur-payments`.

**API contract for external integrators:** `docs/BLOCKCHAIN_PAYOUT_API.md`

## What it does

```
approved submissions (captur-go-api)
  → POST /api/v1/admin/payments/sync        → usdc_reward_ledger rows (AVAILABLE)
  → POST /api/v1/admin/payments/batches     → payout batch (PENDING_APPROVAL)
  → POST .../batches/{id}/approve           → second admin approves (maker-checker)
  → POST .../batches/{id}/execute           → Solana USDC transfers
  → on-chain verification + reconciliation  → rewards marked PAID
```

Reward math: `duration_seconds / 3600 × tier rate`
(FULL $5/hr · IPHONE $4.50/hr · BASIC $3.75/hr), stored as integer USDC base
units (`BIGINT`, 6 decimals). Only `VERIFIED` device-capability entries and
`APPROVED` submissions are eligible; sync is idempotent per `submission_id`.

## Payout providers

`PAYOUT_PROVIDER` selects the transfer implementation (see
`application.yml` → `payments.payout`):

| Provider | Implementation | Status |
|---|---|---|
| `keypair` (default) | `UsdcPayoutExecutor` — raw Solana keypair signs transfers | Active on Devnet |
| `privy` | `PrivyPayoutExecutor` + `PrivyWalletClient` — Privy Wallet Actions API against a Privy-managed treasury wallet | Implemented, Devnet-only (`chain=solana_devnet`) |

Ledger/batch/approval logic is identical for both providers.

## Safety posture

- Solana execution is **Devnet only** — no mainnet until custody is decided.
- Maker-checker enforced: batch creator cannot approve their own batch.
- All money is integer USDC base units — no floating point.
- Treasury keypair / Privy credentials live in env vars / Secret Manager,
  never in the repo (`.devnet-keys/` gitignored).

## Build & test

Requires JDK 21 + Maven:

```bash
mvn test        # unit + embedded-Postgres Flyway migration tests
mvn package     # builds target/captur-payments-*.jar
```

Local JDK/Maven used during development live in `.tools/` (gitignored).

## Run locally

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/captur_payments
export DB_USER=... DB_PASSWORD=...
export PRIVY_APP_ID=...        # required — all API auth fails without it
mvn spring-boot:run
```

Endpoints: `GET /health`, `GET /ready`, admin API under
`/api/v1/admin/payments/*` (Privy JWT + admin role resolved via captur-api),
contributor API under `/api/v1/payments/*`, `/api/v1/rewards/*`, `/api/v1/wallet`.

## Deploy

Cloud Run via `cloudbuild.yaml` (image:
`asia-east2-docker.pkg.dev/capturgo-478506/captur-release/captur-payments`).
Configure env/secrets per `.env.example` — `PRIVY_APP_ID` and `DATABASE_URL`
are required; Solana stays Devnet (`SOLANA_CLUSTER=devnet`).
