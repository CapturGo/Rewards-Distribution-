# FINAL TECHNICAL AUDIT — CapturGO Payments Service

Date: 2026-11-18 · Scope: full codebase `D:\CapturGo\reward` (62 files, 5 migrations, 9 test classes)
Method: complete source inspection + live Cloud Run/Cloud SQL verification + `mvn test` (71/71 pass, BUILD SUCCESS).
Status labels: **VERIFIED** = proven by test or live check · **CODE-REVIEW** = confirmed by reading, not executed · **RISK** = potential, unproven.

---

## 1. Authentication & contributor identity

| Check | Result |
|---|---|
| Privy JWT signature via JWKS (ES256, per-app JWKS URL) | ✅ VERIFIED — `PrivyJwtVerifier.processorFor` |
| Expiry enforced | ✅ manual `exp` check + Nimbus defaults |
| Issuer/audience (`aud` = app id) explicitly validated | ⚠️ **NOT validated** — `appId` is injected but never checked. Mitigated: JWKS URL is app-scoped so foreign-app tokens can't verify. MEDIUM-LOW, defense-in-depth gap |
| `nbf` / clock-skew leeway | ⚠️ not configured — LOW |
| Client-supplied `userId` trusted? | ✅ NO — `ContributorIdentityService.resolve` only caches after captur-api `users/me?userId=` returns 200 with the caller's own token; email cross-check when both present |
| Identity cache/fallback | ✅ persistent `contributor_identities` table; no in-memory staleness issue |
| Wallet = Solana `seeker_wallet`? | ⚠️ `resolveWallet` uses `seekerWallet ?: walletAddress` — **fallback to `walletAddress` (possibly EVM)**. Format check rejects `0x…` (contains excluded `0`/`x`? `x` is allowed but `0` is excluded → `0x` fails) so EVM is incidentally blocked, but the fallback is still wrong-headed. MEDIUM |
| `/users/me` ownership enforcement | ⚠️ ASSUMED — captur-api contract suggests it, never proven live. First real contributor call is the test |
| `getMe`/`getUser` swallow all exceptions → null | ⚠️ captur-api outage indistinguishable from "wrong userId" → 422 instead of 502. LOW-MEDIUM |

## 2. Submission sync & eligibility

| Check | Result |
|---|---|
| Only `APPROVED` processed | ✅ filter param + re-check `sub.status != "APPROVED"` → INELIGIBLE. Case-sensitivity assumed ("APPROVED" exact) — LOW |
| Pagination | ✅ offset/limit loop, `maxPages=20` cap, page-size 100 → up to 2000 submissions/run. Offset pagination on mutable `updated_at` sort — rows shifting mid-sync can skip/dup pages; dup inserts are idempotent, skips self-heal next run. LOW |
| Device eligibility = registry `VERIFIED` only | ✅ `deviceCapabilityRepository.findByDeviceKey` + `verification == "VERIFIED"` + valid tier; unknown → INELIGIBLE, never auto-BASIC |
| Idempotency | ✅ `UNIQUE(submission_id)` + `INSERT … ON CONFLICT DO NOTHING` |
| Auto-sync disabled | ✅ `REWARD_AUTO_SYNC=false` default; scheduler also requires `CAPTUR_API_SERVICE_TOKEN` |
| Concurrent sync (multi-instance) | ⚠️ `running` flag is per-JVM — two Cloud Run instances can sync simultaneously; `ON CONFLICT` makes it benign (duplicate HTTP work only). LOW |
| `@Transactional` wraps entire sync incl. HTTP calls | ⚠️ DB connection held for whole multi-page run; an upstream exception on page N rolls back ALL rewards created that run (consistent but wasteful). LOW-MEDIUM |

## 3. Reward calculation

| Check | Result |
|---|---|
| Formula `seconds/3600 × rate` | ✅ `RewardCalculator.calculate` |
| BigDecimal only | ✅ `BigDecimal(eligibleDurationSeconds).divide(3600, DECIMAL64)` |
| 6-dp HALF_UP → integer base units | ✅ `setScale(6, HALF_UP)` then `movePointRight(6).longValueExact()` |
| Rates $5.00/$4.50/$3.75 configurable | ✅ env-backed, defaults correct |
| Unknown tier / bad device | ✅ rejected before calc (registry gate) |
| `classifyTier()` fallback → BASIC | ⚠️ **dead code today** (sync uses registry tier), but if ever wired in it auto-classifies unknowns as BASIC — contradicts the product rule. Footgun — LOW, recommend delete or guard |
| Config change alters history? | ✅ NO — amount + `hourly_rate_usdc_units` + `calculation_version` persisted per row |
| Edge cases | ✅ tested: zero/negative rejected (`<=0` → INELIGIBLE, `require >= 0`), huge durations → `longValueExact` throws → caught per-submission → error list. Rounding boundary covered by tests |

## 4. Database & ledger integrity

| Check | Result |
|---|---|
| `UNIQUE(submission_id)`, `UNIQUE(reward_id)` on items, `UNIQUE(tx_signature, cluster)`, `UNIQUE(device_key)`, `UNIQUE(idempotency_key)` | ✅ all present (V0001–V0005, verified live in `captur_payments`) |
| FKs | ✅ items→batch, items→ledger, tx→item, evidence→ledger(submission_id) `ON DELETE CASCADE` |
| Indexes | ✅ user+status, status, batch, item status, evidence user/tier/status |
| Status transitions | ⚠️ `updateBatchStatus`/`updateItemStatus` are **unconditional UPDATEs** — no `WHERE status=…` guard except `claimBatchForExecution` and `confirmItemIfUnresolved`. See §5 race findings |
| Paid records overwritten | ⚠️ `markRewardPaid` is unconditional `SET status='PAID'` — no `WHERE status='RESERVED_FOR_PAYOUT'` guard; could resurrect a manually-REVERSED reward if its tx later confirms. LOW-MEDIUM |
| Evidence failure handling | ✅ nested savepoint, warn-logged, non-fatal |
| Migration rollback | ⚠️ no down-migrations (Flyway community) — standard, note only |

## 5. Payout batch lifecycle — **two significant findings**

**FINDING C-1 (CRITICAL, CODE-REVIEW): cancel-during-execute race → double payment**
`PayoutService.cancelBatch` reads status, then `updateBatchStatus(CANCELLED)` **unconditionally** and `releaseRewards()` flips rewards back to `AVAILABLE`. `PayoutExecutionService.executeBatch` claims APPROVED→PROCESSING atomically, then sends real transactions over seconds/minutes.
Interleaving: cancel reads `APPROVED` → execute claims → `PROCESSING`, starts sending USDC → cancel's unconditional UPDATE overwrites `PROCESSING`→`CANCELLED` and releases the same rewards → a new batch can reserve them → **same money paid twice** (first execution still confirms on-chain; `markRewardPaid` doesn't check).
Fix: make cancel atomic — `UPDATE … SET status='CANCELLED' WHERE id=? AND status IN ('PENDING_APPROVAL','CREATED','APPROVED')` and only release rewards when the update wins; re-read status inside the same transaction (`SELECT … FOR UPDATE`). **NOT YET FIXED.**

**FINDING H-1 (HIGH, CODE-REVIEW): batch items can pay INVALID/UNVERIFIED wallets**
`PayoutService.createBatch` uses `wallet?.solanaAddress` with **no verification check**. `resolveWallet` stores INVALID rows for malformed addresses; `createBatch` will happily pay them → on-chain failure or loss. Fix: require `verification == 'VERIFIED'` when building items. **NOT YET FIXED.**

| Check | Result |
|---|---|
| Only AVAILABLE rewards batched | ✅ status filter + atomic `reserveRewards` (`WHERE status='AVAILABLE'`) |
| Batch total = Σ items | ✅ computed from reward rows; 200-item / 100-USDC caps enforced |
| Two-person approval | ⚠️ mostly — creator≠approver enforced via `capturUserId`. **Edge:** if creator's `capturUserId` was null at creation, `createdBy` = nil UUID (`UUID.fromString("0000…")` — the `let{}` ignores privyUserId, always nil) → approver check can never match → single-admin approve possible. MEDIUM, requires captur-api verify to omit userId |
| Approve race | benign — concurrent approves both set APPROVED, audit duplicated only |
| Unauthorized approve/execute | ✅ filter enforces `isAdmin` (ADMIN,FINANCE) on all `/admin/*`; execute additionally needs `SOLANA_ENABLED` + devnet |
| Concurrent execute | ✅ atomic claim — second caller gets 409, executor never invoked (tested) |
| Retry safety | ✅ no auto-retry path exists; ambiguous → REQUIRES_RECONCILIATION |

## 6. Solana / USDC execution — **CRITICAL finding**

**FINDING C-2 (CRITICAL, CODE-REVIEW): executor marks rewards PAID without checking on-chain `err`**
`UsdcPayoutExecutor.awaitConfirmation` returns `"confirmed"/"finalized"` — but a transaction that **lands and fails on-chain also confirms**. `PayoutExecutionService.executeItem` calls `markRewardPaid` on `result.confirmed` with no `meta.err` check. Consequences: insufficient treasury USDC, bad ATA, or any instruction error → reward marked PAID, item CONFIRMED, batch CONFIRMED — while no USDC moved. The reconciler DOES check `err` (via `getTransactionDetail`) but the executor path does not.
Fix: after confirmation, fetch `getTransaction` meta and require `err == null` before CONFIRMED/PAID; otherwise REQUIRES_RECONCILIATION. **NOT YET FIXED — must fix before any devnet execution.**

| Check | Result |
|---|---|
| Devnet-only enforcement | ✅ triple-gated: `SOLANA_ENABLED` + `cluster=="devnet"` in service AND executor AND signer; mainnet throws |
| Correct mint/decimals | ⚠️ config-driven (`SOLANA_USDC_MINT`, decimals=6) — devnet mint TBD; mainnet mint constant known but unused |
| ATA handling | ✅ `createIdempotent` + `transferChecked` with mint+decimals — correct SPL pattern |
| Signature persisted before confirm | ✅ `insertPayoutTransaction` before awaiting result usage; UNIQUE(sig,cluster) |
| Treasury balance/fee pre-check | ❌ MISSING — `treasurySolBalance()` exists but unused; `/admin/treasury/balance` returns nulls (stub). Insufficient funds → on-chain failure → (with C-2) false PAID |
| RPC timeout/ambiguity | ✅ send-throw → REQUIRES_RECONCILIATION, no blind retry; `findExistingSignature` exists for manual recovery |
| Key management | ⚠️ base58 secret via env var — Secret Manager intended but **DB password + any future treasury key currently plaintext env** on Cloud Run (visible via `run.services.get`). Devnet-only today; must move to Secret Manager/KMS before real funds |

## 7. Reconciliation engine

| Check | Result |
|---|---|
| Scans only SUBMITTED/REQUIRES_RECONCILIATION + signature | ✅ `listReconcilableItems` |
| Confirmed + `err==null` → PAID | ✅ via atomic `confirmItemIfUnresolved` (tested) |
| Confirmed + `err!=null` → stays manual review | ✅ tested |
| Pending/unknown/RPC-error → untouched | ✅ tested |
| Concurrent reconcile double-pay | ✅ atomic claim — loser does nothing (tested) |
| Mainnet activation | ✅ `cluster != "devnet"` → no-op; `reconcile-enabled=false` default; read-only (never sends) |
| Batch status recompute | ✅ all-confirmed→CONFIRMED else REQUIRES_RECONCILIATION; can't resurrect CANCELLED |

## 8. API inventory

| Method/Path | Auth | Role | Side effects | Notes |
|---|---|---|---|---|
| GET `/health`, `/ready` | none | — | none | `/ready` does `SELECT 1` |
| GET `/api/v1/admin/auth/verify` | **filter-exempt** | — | none | ⚠️ **BUG:** filter skips this path → principal never set → always returns `authenticated:false`. Intended as session check; currently useless. LOW-MEDIUM |
| GET `/api/v1/admin/payments/ready` | Privy JWT | ADMIN/FINANCE | none | reward queue + wallet status |
| POST `/api/v1/admin/payments/sync` | JWT | ADMIN/FINANCE | ledger+evidence inserts | forwards caller token to captur-api |
| POST `/api/v1/admin/payments/batches` | JWT | ADMIN/FINANCE | batch+items+reserve | idempotency-key replay safe |
| GET `/api/v1/admin/payments/batches[/{id}]` | JWT | ADMIN/FINANCE | none | |
| POST `…/batches/{id}/approve` | JWT | ADMIN/FINANCE, ≠creator | status→APPROVED | see §5 edge case |
| POST `…/batches/{id}/cancel` | JWT | ADMIN/FINANCE | status→CANCELLED, release rewards | **race — C-1** |
| POST `…/batches/{id}/execute` | JWT | ADMIN/FINANCE + SOLANA_ENABLED | **on-chain USDC sends** | financial — gated devnet-only |
| GET `/api/v1/admin/payments` | JWT | ADMIN/FINANCE | none | payment history |
| GET `/api/v1/admin/payments/{id}` | JWT | ADMIN/FINANCE | none | stub "not implemented" |
| GET/POST `/api/v1/admin/devices` | JWT | ADMIN/FINANCE | device registry writes | ⚠️ **no audit log** on tier/verification changes — the eligibility gate is mutable without trail. MEDIUM |
| GET `/api/v1/admin/treasury/balance` | JWT | ADMIN/FINANCE | none | stub — nulls |
| GET `/api/v1/rewards/balance` | JWT | contributor (self) | identity upsert on first call | per-user sums ✅ |
| GET `/api/v1/payments/history` | JWT | contributor (self) | none | |
| GET `/api/v1/wallet` | JWT | contributor (self) | none | |

IDOR check: contributor endpoints resolve identity server-side; `userId` param is verified-claim-only. No endpoint lets one user read another's data. ✅
Input handling: UUID path/param binding, limit/offset coerced, batch size + total caps. Device upsert lacks value validation (bad tier/verification → DB CHECK → generic 500). LOW.

## 9. Security & configuration

- Secrets: DB password + any future keys = **plaintext Cloud Run env vars** (Secret Manager write denied during deploy). captur-api does the same today — consistent but should be remediated. MEDIUM
- CORS: none configured — browser cross-origin blocked; dashboard must proxy server-side (planned). OK by design
- Logging: no token/key logging found; `logger.debug` on JWT failures only. `rawToken` stored on AuthPrincipal in request scope only ✅
- Error responses: generic 500 hides internals; `UpstreamException` message may echo captur-api error text — LOW
- `AdminAuthService` cache: per-token, 60s TTL, **unbounded map** — memory growth with token churn. LOW
- Devnet/prod separation: enforced in code at 3 layers ✅

## 10. Test & failure coverage

71/71 pass. Coverage vs. required list:

| Scenario | Covered? |
|---|---|
| Duplicate sync / duplicate batch / concurrent execute / self-approve / MODERATOR 403 | ✅ tested |
| On-chain failure during EXECUTE (err≠null → false PAID) | ❌ **the C-2 bug — no test exists because executor lacks the check** |
| Cancel-during-execute race | ❌ untested (C-1) |
| INVALID wallet in batch | ❌ untested (H-1) |
| Insufficient treasury/SOL | ❌ no pre-check exists |
| RPC outage during execute | ✅ ambiguous-send test |
| Confirmation delayed → reconcile | ✅ reconciler tests (pending/unknown/err/race) |
| DB failure mid-payout | ⚠️ partial — per-item catch marks FAILED; crash mid-batch leaves PROCESSING (reconciler recovers items, batch needs manual look) |
| Reward calc edges | ✅ calculator tests |
| Missing/incorrect device evidence | ✅ evidence tests |

## 11. Verdict

**A. Logically correct?** Mostly — reward math, eligibility gates, idempotency are sound. Two lifecycle races and one executor gap are real defects.
**B. Financial ledger safe?** Schema + constraints are solid; `markRewardPaid` lacks a status guard and cancel can release in-flight rewards — needs the two fixes.
**C. Duplicate-payment prevention?** Strong at the DB level (unique constraints, atomic claims) — **but C-1 opens a real double-pay window** and C-2 can mark unpaid rewards PAID.
**D. Solana path correct?** Instruction-building is correct SPL; confirmation handling is incomplete (no `err` check).
**E. Proven on devnet?** **Nothing on-chain yet** — zero real transactions. Only unit tests + a ready script.
**F. Lynn's scope:** real treasury verification (address/balance/custody — still unknown), devnet keypair ops, Secret Manager/KMS custody, mainnet decision, tx monitoring.
**G. Before production:** fix C-1, C-2, H-1; live smoke test; devnet E2E; Sai's decision; secrets into Secret Manager; wallet re-verification policy.
**H. Ready for Phase D dashboard?** **Yes for read/admin-batch UI on devnet** — but execute button must stay disabled until C-2 is fixed and devnet E2E passes.

### Required fixes before any execution (ordered)
1. **C-2:** executor must check `meta.err` post-confirmation (reuse `getTransactionDetail`) — ~20 lines + test
2. **C-1:** atomic cancel (`WHERE status IN (…)`) + release rewards only on won claim — ~15 lines + test
3. **H-1:** `createBatch` requires `verification=='VERIFIED'` — ~5 lines + test
4. Small: nil-UUID `createdBy` fallback, `markRewardPaid` status guard, device-upsert audit log, JWT `aud` check

### Addendum — 2026-11-20 live-state verification + fixes applied

Live DB inspection (prod `captur_payments` via Cloud SQL proxy) found the
database **completely empty**: `device_capabilities`=0, `contributor_wallets`=0,
`usdc_reward_ledger`=0, `contributor_identities`=0. Prod `activity_submissions`
has 193 APPROVED rows across ~18 device models / 25 users — but **zero** of
those users have `seeker_wallet` set (3 have EVM `wallet_address` only).

Additional defects found and fixed this session (uncommitted):

- **CRITICAL — status case mismatch:** captur-api serializes submission status
  via `ActivityStatus.toDisplayName()` → `"approved"` (lowercase).
  `RewardSyncService` required `"APPROVED"` exactly → every submission was
  INELIGIBLE. Fixed with case-insensitive compare + regression test.
- **`/api/v1/admin/auth/verify` returned `authenticated:false` always** —
  `AuthFilter.shouldNotFilter` exempted it, so no principal was ever set.
  Fixed: filter now runs for it like other admin paths (still 401 without a
  token). Verified live: deployed service currently returns
  `{"authenticated":false}` — will be correct after redeploy.
- Prior-session fixes now in tree (still uncommitted): atomic batch
  approve/cancel (C-1), on-chain `meta.err` check before PAID (C-2),
  VERIFIED-wallet gate on batch creation (H-1), `markRewardPaid` status
  guard, non-nil `createdBy`. Tests added for wallet gate + atomic approve.

`mvn test`: **74/74 pass** (was 71; +3 new). `mvn package` jar built.

**Remaining hard blockers for E2E (all operational, not code):**
1. `device_capabilities` empty → every device INELIGIBLE. Admin must register
   each real `device_model` (lowercased) via `POST /api/v1/admin/devices`.
   No dashboard UI exists for this yet.
2. Zero approved-submission users have `seeker_wallet` → post-H-1, batch
   creation will reject all rewards. Contributors must onboard a Solana
   wallet in the mobile app, or a test wallet must be set deliberately.
3. `SOLANA_*` env unset on Cloud Run → execute stays disabled; devnet mint,
   treasury keypair and funding still needed (docs/solana-devnet-setup.md).
4. Deployed `captur-payments` runs the pre-fix build — redeploy required.
5. Dashboard payouts UI (`src/app/(console)/payouts/`) is uncommitted in
   `new_dashboared` — must be committed + deployed for Sai to see it.

---

## Appendix A — Capture-evidence field verification (LIVE DB inspection, 2026-11-18)

Verified by read-only inspection of production `activity_submissions` via Cloud SQL proxy (193 APPROVED rows exist; sample = 5 most recent, all `iPhone 11`, `sync_tier=15`, ~598s, IMU 100Hz, ~59.5k samples each).

### Mobile → captur-api → Payments Service → DB mapping

| Mobile field | captur-api column/DTO | Payments DTO | Evidence table | Status |
|---|---|---|---|---|
| deviceModel | `device_model` → `deviceModel` | ✅ | `device_model` | ✅ live-verified populated |
| syncTier | `sync_tier` (smallint 10/15/20) → `syncTier` (string) | ✅ | `sync_tier` | ✅ populated; name mapping (BRONZE..DIAMOND) still unverified — unused for tier anyway |
| imuFrequencyHz | `imu_frequency_hz` numeric → number | ✅ `Double?` | `imu_frequency_hz` float8 | ✅ populated (100.00) |
| gyro/accel sample counts | ✅ int columns → DTO | ✅ | ✅ int columns | ✅ populated (~59.5k) |
| gyro/accel stream URLs | **no DB columns** — API derives `gyroStreamUrl`/`accelStreamUrl` (likely from `metadata_urls`) | ✅ mapped | ✅ columns | ⚠️ values unverified until API fetch; harmless either way |
| videoUrl | ✅ | ✅ | `video_url` | ✅ |
| durationSeconds | ✅ | ✅ | `duration_seconds` | ✅ drives reward calc |
| metadataUrls | `metadata_urls` JSONB → object | ✅ `Map` | `metadata_urls` JSONB | ✅ populated: `{gyro, accel, frames}` CSV URLs — **frame metadata URL present** |
| **metadata{} blob** | `metadata` JSONB → object | ✅ `Map` | **`raw_metadata` JSONB** | ✅ populated — contains ALL of: `lens` ("0.5x"), `fov_vertical_deg` (75.3), `fov_horizontal_deg` (107.8), `recordedAt` (ISO ts), `imu_start_epoch_ns`, `video_start_epoch_ns`, `platform`, `os_version`, `app_version`, `sync_method`, `sync_offset_ns` |
| **deviceSignature** | `device_signature` text column = `"DeviceTier_Tier 1.5"` — **NOT in response DTO** | ❌ not mapped | ❌ not stored | 🔴 **captur-api gap — see below** |

### Findings

1. **No mobile changes required.** The app already sends everything; captur-api stores it; our DTO + evidence table preserve it losslessly via `raw_metadata`/`metadata_urls` JSONB.
2. **FOV, lens, capture timestamps, frame-metadata URLs: all live-verified populated** inside `metadata`/`metadata_urls` and preserved by `submission_device_evidence`.
3. **deviceSignature gap (captur-api, not mobile, not payments):** the column exists and is populated (`DeviceTier_Tier 1.5`) but `ActivitySubmissionDto` does not expose it.
   **Required change for Rahul:** add `deviceSignature: string` to `ActivitySubmissionDto` (read from `activity_submissions.device_signature`). One-line DTO addition + mapper; no schema change. Optionally also expose `gyroStreamUrl`/`accelStreamUrl` source-of-truth if they are derived.
4. **Evidence status:** capture evidence is **partially live-verified** — storage layer proven against real DB content; the final hop (API response → our sync → evidence row) still awaits the authenticated smoke test (`docs/live-smoke-test.md`).
5. **sync_tier semantics:** DB stores smallint (10/15/20); API returns string; name mapping unverified — irrelevant to rewards since tier comes from the VERIFIED device registry only.

### Device-registry consequence (informational)

All sampled APPROVED submissions are `iPhone 11`. For rewards to be created, `device_capabilities` must contain `device_key='iphone 11'` (normalized lowercase) with `verification='VERIFIED'` and a valid tier — otherwise every synced submission lands INELIGIBLE. Populate via `POST /api/v1/admin/devices` before the first real sync.
