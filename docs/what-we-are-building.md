# What We Are Building — CapturGO USDC Payments

> **Status update (2026-11-22):** deployed on Cloud Run as `captur-payments`,
> reading submissions via **captur-go-api**. 193 rewards synced to
> `usdc_reward_ledger` (AVAILABLE, ~132.45 USDC total, all wallets resolved).
> Payout batches not yet created/executed. API contract for external
> integrators: `BLOCKCHAIN_PAYOUT_API.md`. The table below reflects the
> original build status; see README.md for current posture.

## The one-paragraph version

We are building a **standalone Payments Service** that decides how much USDC each contributor earned for approved capture work, queues those earnings for admin review, and — once approved — sends real USDC on Solana to the contributor's existing Privy wallet. It is a separate service: it reads captur-go-api, keeps its own database, and never modifies the existing app or its token/points reward system.

## The system in one picture

```
Mobile app ──► captur-api (live) ──► prod database
                      │                  (submissions, approvals,
                      │                   token/points rewards)
                      │ read-only, caller's token forwarded
                      ▼
        Payments Service (this repo) ──► own database (captur_payments)
              │  calculates reward, keeps ledger, manages batches
              ▼
        Admin approves batch ──► Solana devnet/mainnet transfer
              │
              ▼
        Contributor's existing Solana wallet receives USDC
```

## How a payment happens (intended flow)

1. Contributor records a video in the mobile app and uploads it.
2. captur-api stores the submission; QA/admin reviews it.
3. Submission status becomes **APPROVED** (= ready to pay, per Sai's decision).
4. Payments Service sync reads APPROVED submissions, checks the device is a **VERIFIED** registered device, calculates `duration/3600 × tier rate`, and writes a **reward ledger** row.
5. Reward appears in the admin payout queue.
6. Admin creates a payout batch → a **different** admin approves it.
7. Service executes USDC transfers on Solana, waits for on-chain confirmation, and only then marks rewards **PAID**.

## Device tiers (hourly rates, Sai-confirmed)

| Tier | Rate/hour | Requirements |
|---|---:|---|
| FULL | $5.00 | 110° FOV, IMU 200Hz, gyro+accel, 16:9 |
| IPHONE | $4.50 | IMU 100Hz, gyro+accel, 16:9, 30fps |
| BASIC | $3.75 | IMU 200Hz, gyro+accel, 16:9, 30fps |

Unknown/unverified devices get **nothing** — never auto-BASIC.

## What exists today vs. what doesn't

| Piece | Status |
|---|---|
| Reward calculator (hourly × tier, exact USDC math) | Built + tested |
| Idempotent reward ledger (no duplicate rewards, ever) | Built + tested |
| Privy login + admin authorization (ADMIN/FINANCE only) | Built + tested |
| Privy→CapturGO identity mapping (verified via captur-api) | Built + tested, not yet run live |
| Payout batch lifecycle (create/approve/cancel/execute) | Built + tested, deployed |
| Solana devnet transfer code (keypair + Privy providers) | Built + unit-tested, deployed — **not yet executed on-chain** |
| Device capability registry + admin API | Built + tested |
| Automatic reward syncing | Manual via dashboard/API (`REWARD_AUTO_SYNC=false`) |
| Real USDC / mainnet | **Blocked by code** — devnet only |
| Admin dashboard integration | Done — Payouts page in captur-dashboard |
| Live end-to-end proof | Reward sync proven (193 rows); on-chain payout **not yet done** |

## What is NOT decided yet

1. **Double compensation** — captur-api already credits token/points on APPROVED. Does the contributor get tokens AND USDC, or USDC only? Documented in `double-compensation-decision.md`, waiting on Sai.
2. **Eligible duration** — currently uses submission `durationSeconds`; product must confirm this is the right metric.
3. **USD = USDC 1:1** — assumed; needs explicit confirmation.
4. **Treasury custody** — CapturGO has a central Solana treasury (Lynn has access); how the service gets signing access (Secret Manager vs KMS/multisig) is undecided.
5. **Capture-time device evidence** — mobile app doesn't yet send real FOV/fps/lens per recording; Phase B persists what the API already returns and documents the gap.

## Safety rules baked in

- Same submission → one reward, guaranteed by database constraint.
- Same reward → one payout batch, guaranteed by database constraint.
- Batch creator cannot approve their own batch.
- A transaction is PAID only after Solana confirms it on-chain.
- Ambiguous RPC outcomes → manual reconciliation, never blind retry.
- No private keys in code, logs, or the database.
- Mainnet is refused by code, not just config.

## Current test status

`mvn test` → **45 tests, 0 failures** (migrations on real embedded Postgres, auth, calculator, sync, payout lifecycle, execution guards).

## Documents in this folder

- `payments-service-audit.md` — full technical audit (readiness per component)
- `BLOCKCHAIN_PAYOUT_API.md` — external integrator API contract (network, token, endpoints, statuses)
- `FINAL_AUDIT_REWARD_SYSTEM.md` / `REWARD_DISTRIBUTION_FINDINGS.md` / `USER_WALLET_STATUS.md` — audit & findings docs
- `double-compensation-decision.md` — the open product question for Sai
- `solana-devnet-setup.md` — how to provision devnet treasury/mint for first test
- `device-evidence-gap.md` — what mobile must send (Phase B)
