# CapturGO Reward Distribution — Findings, Fixes & Remaining Blockers

Date: 2026-11-20 · Scope: `captur-payments` service, captur-api integration, admin dashboard, Solana USDC payouts.

## Status summary

| Area | State |
|---|---|
| Payments service code | Fixed, 74/74 tests pass, deployed (`captur-payments-00003-gzb`) |
| Admin dashboard payouts page | Committed + deployed (`captur-dashboard-00017-xjq`) |
| Device registry | 18 prod device models registered, all FULL/VERIFIED |
| Devnet treasury | Generated; needs faucet SOL before use |
| End-to-end devnet payout | NOT yet executed — pending faucet + test wallet |
| Production (mainnet) payouts | NOT ready — blockers below |

---

## Problems found & fixed this session

| # | Severity | Problem | Fix |
|---|---|---|---|
| 1 | CRITICAL | captur-api returns status `"approved"` (lowercase); sync required `"APPROVED"` → every submission marked INELIGIBLE, DB stayed empty | `RewardSyncService` now compares case-insensitively + regression test |
| 2 | HIGH | `/admin/auth/verify` returned `authenticated:false` always (filter exempted it) → admin role checks broken | `AuthFilter` attaches principal on verify endpoint + test |
| 3 | HIGH | Batch cancellation could race in-flight execution | Atomic cancellation claim limited to pre-execution states |
| 4 | CRITICAL | Executor could mark rewards PAID without checking on-chain `meta.err` | Fetches tx detail; `meta.err != null` → FAILED/reconciliation, never PAID |
| 5 | HIGH | Batch creation didn't require verified Solana wallet | Hard-fails batch if any selected reward lacks VERIFIED wallet |
| 6 | MEDIUM | Self-approval bypass when creator identity null | `createdBy` non-null enforced |
| 7 | MEDIUM | `markRewardPaid` could transition from wrong state | Guarded to `RESERVED_FOR_PAYOUT` only |

Deployed fix commit: `18beddf` (reward repo), `439027c` + `b50a146` (dashboard repo — dashboard commit not pushed to remote yet).

---

## Verified live facts (prod DB + live API)

- `activity_submissions`: 193 APPROVED / 281 PENDING / 195 REJECTED; 25 distinct approved users
- Approved submissions have duration + IMU telemetry populated
- `users.seeker_wallet`: **0 populated** across all approved users
- `users.wallet_address`: only 3 users, all EVM `0x...` — invalid for Solana
- `fiat_transaction` shows legacy in-app fiat rewards credited AND reversed (net zero); ACCEPTED status count = 0 — **no user has been paid via captur-api fiat path**
- `device_capabilities` was empty → now populated (18 models, FULL/VERIFIED)
- Live OpenAPI confirms `PUT /api/v1/users/me` and `POST /api/v1/users` accept `seekerWallet`; `POST /api/v1/wallets/onboard` is **EVM-only** (0x validation + gas funding)

---

## Remaining blockers

### Hard blockers (real payouts impossible until fixed)

1. **`seeker_wallet` empty for all users** — mobile app never sends the Privy-generated Solana wallet to `PUT /users/me`. App-side gap: `VerifyWalletScreen` uses Solana MWA (requires physical Solana phone; iOS path is an unfinished stub). Backend accepts the field fine.
   - Demo bypass: set `seekerWallet` on one test user via `PUT /users/me` (user token) or approved DB write.
   - Prod fix: app must read Privy embedded Solana wallet and send it, or wallet onboarding must support Solana.

2. **Lynn's treasury** — real Solana treasury wallet, USDC, signing/mainnet readiness. Sai confirmed treasury exists; Lynn holds it. Needed for mainnet only.

3. **Devnet E2E not yet run** — needs faucet SOL (~1–2 SOL to `GVCcANPUcjPcw3vQ2EEKJZMknec3ERUebTZVsrFxqqts`), then mint/fund devnet USDC, set `SOLANA_*` env, run smoke + E2E.

4. **`SOLANA_*` env vars unset on Cloud Run** — execution disabled by default (safe). For real keys use Secret Manager, not plain env vars.

### Policy/product confirmations needed from Sai

5. **Two distinct admin accounts** — creator ≠ approver enforced; need two ADMIN/FINANCE identities.

6. **Double-compensation policy** — confirm legacy fiat/token reward path stays off for USDC-eligible submissions (Sai said "ignore points system" — needs explicit confirmation no other flow credits APPROVED submissions).

7. **Reward rate** — config pays hourly prorated (FULL $5/hr, IPHONE $4.50, BASIC $3.75). Sai's answer to the rate question was images (unreadable) — confirm intended formula.

8. **ACCEPTED vs APPROVED semantics** — captur-api fiat credit fires on ACCEPTED; payments service pays on APPROVED. Confirm no submission can be double-compensated across both rails.

---

## Known limitations (not blockers, but documented)

- Sensor evidence (gyro/accelerometer) is **captured for audit but NOT enforced** as eligibility — decision needed if it should be a hard gate.
- Wallet verification = Solana address format check at sync + VERIFIED flag at batch creation. No cryptographic ownership proof (sign-in-with-wallet) — acceptable for devnet, review for mainnet.
- `raffle-reward-distribution-main` is a separate raffle-winner tool; it has the same `meta.err`-unchecked bug class — out of scope here but flagged.
- Dashboard payouts page is deployed but `/payouts` isn't in the sidebar nav (direct URL works).
- Reward repo commits are local — `origin` is `manav2010-oss/Rewards-Distribution-`; not pushed.
- Dashboard repo commits are local — not pushed.
- A second Cloud Build (`7e964957`, MUTATIONS=true, ADMIN_EMAIL=manav@) was running — live service points at the immutable tag so it's unaffected, but don't redeploy `:latest` blindly.

---

## Demo path (devnet, zero real funds)

1. Faucet SOL → treasury address
2. Create devnet USDC mint + fund treasury ATA (script ready in `.devnet-keys/scripts/`)
3. Set `SOLANA_*` env on `captur-payments` (devnet values only)
4. Set `seekerWallet` on one test user (API or approved DB write)
5. Admin login → `/payouts` → Sync → verify ledger rows
6. Create batch → approve with second admin → Execute → verify signature on devnet explorer
7. Present to Sai

## Mainnet GO/NO-GO

**NO-GO today.** GO requires: seeker_wallet population path fixed, Lynn treasury + keys in Secret Manager, devnet E2E evidence, two-admin provisioning, double-pay policy confirmed, rate confirmed.
