# FINAL TECHNICAL AUDIT — CapturGO Reward & USDC Payout System

Date: 2026-11-21 · Scope: reward distribution only · Mode: read-only (no code/deploy/transfer changes made)

Evidence basis: source inspection of `reward`, `New Backend`, `new_dashboared`, mobile repo; live prod DB queries (`captur_payments` on `captur-go-v2-dest` via Cloud SQL proxy); Cloud Run env/IAM inspection; Cloud Build test run `83c628d5` (SUCCESS, 84/84 tests); Privy docs (docs.privy.io).

---

## PART 1 — Architecture and ownership

| Concern | Owner | Evidence |
|---|---|---|
| Authoritative reward ledger | **Payments Service** (`usdc_reward_ledger`, `captur_payments` DB) | `reward/src/main/resources/db/migration/V0001__payments_init.sql:16` |
| Batch create/approve/execute | **Payments Service** | `payouts/PayoutService.kt`, `PayoutExecutionService.kt` |
| Payout reconciliation | **Payments Service** | `payouts/PayoutReconciler.kt` |
| Submission storage + QA approval | **captur-api** (Kotlin) + **captur-go-api** (Go, QA console) | `New Backend/internal/qa/*` |
| User/DID identity | captur-api `users` table (`external_id` = Privy DID) | prod schema |
| Wallet execution | **Privy Wallet Actions** (new) or raw keypair (legacy) — executor only | `solana/PrivyPayoutExecutor.kt`, `UsdcPayoutExecutor.kt` |
| Go API financial role | **None** — auth, users, QA, media; no reward ledger, no batches | `New Backend` repo inspection |

**Answers:**
1. Ledger owner: Payments Service. ✅ single ledger.
2. Batch owner: Payments Service. ✅
3. Reconciliation: Payments Service (`PayoutReconciler`, opt-in via `SOLANA_RECONCILE_ENABLED`).
4. Dashboard → correct backend: YES — `payments-svc/*` → captur-payments; `go-svc/*` → captur-go-api; default → captur-api (`new_dashboared/src/app/api/backend/[...path]/route.ts:15`).
5. Privy role: execution transport only — no financial logic in Privy. ✅ correct boundary.
6. Duplicate financial responsibilities: **None found.** Go API has no ledger/batch code.

## PART 2 — Reward lifecycle trace

**Stage 1 — Activity/submission:** Mobile → captur-api `activity_submissions` (status 0/1/-1). Privy DID in `users.external_id`. ✅ (existing, verified previously)

**Stage 2 — Sync:** `POST /api/v1/admin/payments/sync` → `RewardSyncService.sync(token)` (`rewards/RewardSyncService.kt:34`). Fetches `APPROVED` submissions paginated from captur-api with caller's forwarded Bearer token. Per submission: skip if `submission_id` exists (`ON CONFLICT DO NOTHING`, `RewardRepository.kt:33`), skip non-APPROVED, require VERIFIED device capability + positive duration, resolve wallet, insert ledger row. **Exactly-once by `UNIQUE(submission_id)`.** Errors per-item are collected, not fatal. Rejected/pending cannot enter (status filter + case-insensitive check). ⚠️ No checkpoint/resume — full re-scan each sync (bounded by `maxPages`), acceptable at current volume.

**Stage 3 — Calculation:** `RewardCalculator.calculate` (`rewards/RewardCalculator.kt:60`): `rewardUsd = (durationSeconds/3600) × tierRate`, BigDecimal DECIMAL64, scale 6 HALF_UP → `rewardUsdcUnits` (×10⁶). Tiers FULL $5.00 / IPHONE $4.50 / BASIC $3.75 per hour (env-configurable). Tier from `device_capabilities` registry (VERIFIED only; unknown → INELIGIBLE, never auto-BASIC). `calculation_version="v1-hourly-tier"` stored per row.
**Example:** FULL tier, 3600 s → 1.0 h × $5.00 = $5.00 → **5,000,000 units**.
⚠️ `durationSeconds` is the eligible-duration source — spec §9.1 authoritative metric still unconfirmed (code comment acknowledges).

**Stage 4 — Ledger:** `usdc_reward_ledger`: `submission_id UNIQUE`, `captur_user_id`, `activity_id`, `amount_usdc_units`, `status` CHECK (PENDING/AVAILABLE/RESERVED_FOR_PAYOUT/PAID/REVERSED), `source`, `metadata` JSONB, timestamps; V0002 adds tier/rate/duration/calculation_version. History preserved; duplicate obligations prevented by UNIQUE + status machine. ✅ schema sound — **but prod table has 0 rows (never synced).**

**Stage 5 — Manual batch creation:** `POST /api/v1/admin/payments/batches` → `PayoutService.createBatch` (`payouts/PayoutService.kt:30`). Admin supplies `rewardIds[]` + optional `idempotencyKey`. Validates: non-empty, ≤200 items, all rewards `AVAILABLE`, total ≤100 USDC cap, **VERIFIED Solana wallet required per contributor** (else 422), atomic `reserveRewards` flip AVAILABLE→RESERVED_FOR_PAYOUT, `UNIQUE(reward_id)` on items prevents double-assignment, batch `UNIQUE(idempotency_key)`, audit `BATCH_CREATED` with actor/ip/ua. Batch starts `PENDING_APPROVAL`. ✅ Fully manual — no auto-batch anywhere.

**Stage 6 — Review/approve:** `POST /batches/{id}/approve` → `approveBatch` (`PayoutService.kt:128`). Atomic status transition; **approver must differ from creator** (`approverId == batch.createdBy` → 403). ⚠️ **HIGH: single-admin model conflicts** — one admin cannot approve their own batch; needs a second ADMIN/FINANCE account or explicit policy exception.

**Stage 7 — Execution:** `POST /batches/{id}/execute` → outer gate `payments.solana.enabled` (`PayoutService.kt:173`) → `PayoutExecutionService.executeBatch`: requires APPROVED, `solanaEnabled && cluster==devnet`, atomic `claimBatchForExecution`, per-item PROCESSING→executor→record reference→CONFIRMED/SUBMITTED/REQUIRES_RECONCILIATION. ⚠️ Note: outer gate still reads `payments.solana.enabled` — Privy provider also needs `SOLANA_ENABLED=true` (or `PAYOUT_ENABLED`) set.

**Stage 8 — Confirmation/reconciliation:** Executor returns `TransferResult(signature, confirmed, confirmationStatus, chainError)`; signature persisted **before** confirmation awaited; PAID only when `confirmed=true` (on-chain err==null for keypair; action `succeeded` for Privy). Timeout/ambiguity → REQUIRES_RECONCILIATION, never auto-retry; `PayoutReconciler` (opt-in) re-checks via `executor.lookup`. ✅ Design correct — never exercised in prod (0 tx rows).

## PART 3 — Privy integration audit

**A. Contributor identities/wallets:** App ID `cmj5wppqx...` consistent across mobile/dashboard/backends. `users.external_id`=DID mapping intact (6,167 users). `seeker_wallet`: 25 approved contributors missing; 96 users have it; sync path = mobile PR **#142 — state OPEN, not merged** (`gh pr view 142`). Privy-side `pregenerate-wallets`/`GET /v1/users/{id}` path built in Go (`internal/auth/privy.go`) but **BLOCKED on App Secret** (still absent — Secret Manager has only `capturgo-privy-app-id`, `capturgo-privy-verification-key`). Wallet fallback order in sync: `seekerWallet ?: walletAddress` — `walletAddress` is the legacy field; `isSolanaAddress` format check applied before VERIFIED. ⚠️ MEDIUM: legacy EVM `wallet_address` could theoretically pass if it looks base58 — verify no EVM addresses in `wallet_address` column before relying on fallback.

**B. Treasury wallet:** **Does not exist.** No Privy-managed treasury wallet created; `PRIVY_TREASURY_WALLET_ID` unset everywhere; no `SOLANA_*`/`PAYOUT_*` env on prod captur-payments; raw-keypair path also unconfigured (no `SOLANA_TREASURY_SECRET_KEY`). Both engines inactive in prod — by design until configured.

**C. Wallet Actions implementation:** `PrivyWalletClient` (REST: Basic auth appId:secret + `privy-app-id` header; `POST /v1/wallets`, `POST /v1/wallets/{id}/transfer`, `GET .../actions/{id}`), `PrivyPayoutExecutor` (solana_devnet + usdc, decimal-string amount, deterministic `privy-idempotency-key`, action-id-as-reference, status polling, devnet hard-gate), `PayoutExecutor` interface + `PayoutExecutorConfiguration` (`PAYOUT_PROVIDER=keypair|privy`, `@Primary` bean). Matches docs.verified contracts. **Actual prod executor today: NEITHER — both disabled.** Staging runs new code healthy (`/health` UP, revision `captur-payments-staging-00002-qcx`).

**D. Policies/auth keys:** Not configured — requires Privy Console; no policy_ids used at wallet creation (none created yet). Treasury protection currently = application-level admin approval only.

**E. Credentials:** `PRIVY_APP_ID` correct everywhere. `PRIVY_APP_SECRET`: **still missing** — not in Secret Manager, env, or repos. Sai's "Will do this soon" remains outstanding. `cmw8uqfx...` unused by any code (candidate for treasury-only app).

## PART 4 — Financial security & correctness

| # | Check | Status |
|---|---|---|
| 1 | DB idempotency | ✅ `submission_id`/`idempotency_key`/`reward_id`/`tx_signature+cluster` UNIQUEs |
| 2 | Unique payout refs | ✅ action-id/tx-sig persisted pre-confirmation |
| 3 | Duplicate batch | ✅ idempotency key + AVAILABLE-only reserve |
| 4 | Duplicate tx | ✅ Privy idempotency key (24h) + no blind retry + atomic claim |
| 5 | Atomic approval | ✅ `approveBatchAtomically` |
| 6 | Atomic reservation | ✅ `reserveRewards` AVAILABLE→RESERVED |
| 7 | Concurrent execution | ✅ `claimBatchForExecution` single-winner |
| 8 | Timeout recovery | ✅ REQUIRES_RECONCILIATION + reconciler |
| 9 | No blind retries | ✅ verified in code + test (`no blind retry`) |
| 10 | Partial failure | ✅ per-item status; batch → REQUIRES_RECONCILIATION/FAILED/CONFIRMED |
| 11 | Restart reconciliation | ✅ persisted refs; reconciler resumes (when enabled) |
| 12-13 | Decimal math | ✅ BigDecimal everywhere; units as Long; no float64 |
| 14 | Recipient validation | ✅ base58 format + VERIFIED wallet required at batch create |
| 15 | Treasury balance check | ⚠️ NONE — no balance pre-check before execute (Privy will reject insufficient funds → item FAILED; acceptable but note) |
| 16 | Admin authorization | ✅ `PAYMENTS_ADMIN_ROLES=ADMIN,FINANCE` + Privy JWT + captur-api role verify; approver≠creator |
| 17 | Audit logs | ✅ `payout_audit_logs` on create/approve/cancel/execute/reconcile |
| 18 | No secret logging | ✅ (reviewed; Privy errors truncated) |
| 19 | Post-approval immutability | ✅ batch contents frozen at creation; cancel only pre-execution states |
| 20 | Unconfirmed ≠ PAID | ✅ PAID only on confirmed/succeeded |

**Duplicate-payout paths:** none found. Worst case = ambiguous send → REQUIRES_RECONCILIATION → manual review (safe).

## PART 5 — Dashboard integration

`src/app/(console)/payouts/page.tsx` (819 lines) — **real, fully wired**: `useSyncRewards` (POST /sync), `useReadyRewards` (eligible unpaid list w/ wallet-missing flags), reward multi-select → create batch (individual amounts + total), `usePayoutBatches`/`usePayoutBatch` detail, approve/execute/cancel actions, status filters, audit display. All via `payments-svc` proxy → captur-payments `/api/v1/admin/payments/*`. No mock data found in this page. ⚠️ Untested with real data (all tables empty).

## PART 6 — Go API role

`New Backend` (captur-go-api): Privy JWT auth, user provisioning/roles, QA console, media, misc app routes. **No financial ledger, no batches, no payout logic.** Dashboard splits traffic correctly. No conflict — smallest correction: none needed.

## PART 7 — Testing & deployment

**Tests (Cloud Build `83c628d5`, SUCCESS):** 84/84 — incl. `RewardCalculatorTest`, `RewardSyncServiceTest`, `PayoutServiceTest` (6), `PayoutExecutionServiceTest` (6), `PayoutReconcilerTest` (10), `PrivyPayoutExecutorTest` (10), `AuthFilterTest`, `FlywayMigrationTest` (15, real embedded PG).
**Coverage gaps:** no test for `PrivyWalletClient` HTTP layer (no mock web server test), no dashboard E2E, no live Privy call test.
**Deployed vs source:** prod captur-payments = OLD image (pre-Privy code, executor disabled — functionally identical since both engines were already off). Staging = NEW image `devin-privy-1`, healthy, Privy executor inactive (no env). Dashboard prod = latest (auth/Team/payouts UI). **Devnet transfer: NEVER performed.**

## PART 8 — Sai requirements alignment

| Requirement | Agreed | Technical evidence | Status |
|---|---|---|---|
| USDC rewards | Yes | `usdc_reward_ledger`, asset "usdc" | PASS (code) |
| Existing Privy identities | Reuse | `external_id`=DID, app `cmj5wppqx` everywhere | PASS |
| Existing contributor wallets | Reuse/create | PR #142 OPEN; 25 approved missing `seeker_wallet` | PARTIAL/BLOCKED |
| Existing reward workflow | Preserve | captur-api QA untouched; payments syncs approved only | PASS |
| Manual admin batch | Required | `createBatch` manual, rewardIds[] explicit | PASS |
| Admin approval | Required | approve→execute separate; approver≠creator | PASS (see single-admin issue) |
| Privy Wallet Actions | Intended path | Implemented per docs | PARTIAL (undeployed-config) |
| Treasury wallet | Required | Does not exist | FAIL/BLOCKED |
| App Secret | From Sai | Absent everywhere | BLOCKED |
| Devnet test | Required first | Never run | NOT DONE |
| Dashboard integration | Required | payouts page fully wired | PASS (code) |

## PART 9 — Final report

### A. Engineering remaining
- Merge PR #142 + release mobile wallet sync (then verify the 25 contributors).
- Deploy new payments image to prod only after staging Privy test passes (image already built: `devin-privy-1`).
- Set `SOLANA_ENABLED`/`PAYOUT_ENABLED` + `PAYOUT_PROVIDER` + `PRIVY_*` envs when activating.
- Optional: treasury balance pre-check; `PrivyWalletClient` HTTP unit test; webhook integration (nice-to-have).

### B. External dependencies
- **Sai/Lynn: production App Secret** (`cmj5wppqx...`) → Secret Manager → unlocks employee provisioning + contributor wallet sync/pregeneration.
- **Treasury wallet creation** in a Privy app (user's `cmw8uqfx...` acceptable for treasury-only) + its App Secret.
- **Devnet funding**: SOL fees + test USDC to treasury.
- **Privy Console**: policies (USDC/devnet/limits), gas sponsorship check, webhook config (optional).
- **Second admin decision**: approver≠creator rule vs single-admin model — needs explicit policy call.

### C. Production readiness
- Devnet proof first (one controlled batch → on-chain tx → PAID).
- Then: policies on treasury, monitoring/alerts on reconciler, Secret Manager for all creds (DB password still plaintext env — known issue), mainnet decision separate.

## PART 10 — Direct answers

1. Reward calculation working? **Code correct, never run in prod** (0 ledger rows). PASS-code / NOT-VERIFIED-live.
2. Ledger authoritative/consistent? **Yes** — single ledger, UNIQUE constraints, status machine. PASS.
3. QA data reaches payments? **Path exists** (`/sync` → captur-api APPROVED), never executed. PARTIAL.
4. Admin manual batch? **Yes** — explicit rewardIds, validation, audit. PASS.
5. Review+approve before execute? **Yes** — separate endpoints, atomic transitions, approver≠creator. PASS (flag single-admin).
6. Dashboard real APIs? **Yes** — payouts page fully wired, no mocks. PASS.
7. Payments = financial middleware? **Yes** — owns ledger/batches/reconcile; captur-api owns submissions/QA. PASS.
8. Privy contributor wallets? **PARTIAL** — identities fine; wallet addresses not synced (PR #142 open, secret missing).
9. Privy treasury transfers? **BLOCKED** — no treasury wallet/credentials/funding.
10. Executor using Wallet Actions? **Code yes** (`PAYOUT_PROVIDER=privy`); prod runs neither (disabled).
11. Privy policies configured? **No** — Console work pending.
12. Amount/recipient/mint validated? **Yes** — base58 check, VERIFIED wallet, USDC asset, decimal strings, caps.
13. Duplicate payments prevented? **Yes** — multiple layers (UNIQUEs, atomic claims, idempotency keys, no blind retry).
14. Reconciliation reliable? **Design yes** — never exercised live.
15. Confirmed→PAID? **Yes** — only on confirmed/succeeded with err==null.
16. Complete: auth, QA console, sync engine, calculator, ledger schema, batch lifecycle, both executors, reconciler, dashboard UI, tests, staging deploy.
17. Before dashboard/backend delivery: first real sync + batch on staging/prod (needs only admin action — sync endpoint works today with existing creds? sync needs captur-api token forwarding — admin JWT — should work now actually).
18. Before first Devnet payout: treasury wallet + app creds + env vars + funding + build already green.
19. Before mainnet: devnet proof, policies, monitoring, custody decision, explicit authorization.
20. Sai must provide: **App Secret** (still). Sai should also decide: second approver for batches (or approve policy exception).

## FINAL VERDICTS

1. **REWARD SYSTEM READINESS: READY AFTER FIXES** — engine is correct and tested; never run end-to-end; needs first live sync + single-admin approval decision.
2. **ADMIN DASHBOARD DELIVERY: READY AFTER FIXES** — UI complete and wired; shows empty until first sync/batch; needs real-data verification.
3. **PRIVY PAYOUT READINESS: BLOCKED** — code done + staging healthy; awaiting treasury wallet, credentials, Devnet funding. Not mainnet-ready.

## Prioritized remaining tasks

1. Sai: App Secret → Secret Manager (unblocks employee provisioning + wallet sync).
2. User: create treasury wallet in Privy app + provide app id/secret → Secret Manager.
3. Fund Devnet treasury (SOL + USDC).
4. Set `PAYOUT_PROVIDER=privy` + envs on `captur-payments-staging`; run first sync → batch → approve → one-contributor test payout → verify on-chain + PAID.
5. Merge PR #142; verify the 25 contributors' `seeker_wallet`.
6. Resolve approver≠creator vs single-admin (second admin or policy exception).
7. Prod deploy of payments image only after staging proof; keep `SOLANA_ENABLED`/`PAYOUT_ENABLED` gated until authorized.
