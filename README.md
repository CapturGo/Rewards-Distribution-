# captur-payments

Independent Kotlin/Spring Boot service for CapturGO USDC rewards ledger and
Solana payout batches. Reads approved submissions from `captur-api` (read-only,
caller's Bearer token forwarded) and owns its own PostgreSQL tables. See
`../new_dashboared/docs/usdc-payments-service-design.md` for the full design.

## Safety posture (Step 1)

- `captur-api` and the mobile app are **not modified** — this service only calls
  existing read endpoints.
- Solana execution is **disabled** (`SOLANA_ENABLED=false`) and hard-blocked in
  code until reward amount, wallet field, eligibility trigger and treasury
  signer are confirmed. Devnet only; no mainnet.
- All money is integer USDC base units (`BIGINT`, 6 decimals).

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
`/api/v1/admin/payments/*` (Privy JWT + captur-api admin role required),
contributor API under `/api/v1/payments/*`, `/api/v1/rewards/*`, `/api/v1/wallet`.

## Deploy

Cloud Run via `cloudbuild.yaml` (image:
`asia-east2-docker.pkg.dev/capturgo-478506/captur-release/captur-payments`).
Configure env/secrets per `.env.example` — `PRIVY_APP_ID` and `DATABASE_URL`
are required; keep `SOLANA_ENABLED=false` until Step 2 sign-off.
