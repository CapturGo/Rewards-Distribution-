# CapturGO Payments Service — Audit Report

**Date:** audit performed against `D:\CapturGo\reward` working tree
**Method:** read-only inspection of actual source, migrations, config, tests, and the deployed captur-api OpenAPI spec (`New Backend/captur-api-openapi.json`, `schema-prod.txt`). Fresh `mvn test` executed. No code, schema, config, or infrastructure was modified.

---

## A. Executive summary

The Payments Service is a **real, working Kotlin/Spring Boot codebase** — not a skeleAton. Reward calculation, eligibility gating, idempotent ledger, admin batch lifecycle, Privy auth, and a devnet-only Solana execution path are all implemented and **34/34 tests pass** on a fresh `mvn test` run.

**Honest readiness:**

| Question | Answer |
|---|---|
| Ready for reward calculation? | **PARTIAL** — engine is correct, but `durationSeconds` is unverified as "eligible duration" and tier depends on a manually-populated registry |
| Ready for admin payout batches? | **YES (code-level)** — lifecycle, idempotency, audit trail all implemented and tested |
| Ready for Devnet transfers? | **PARTIAL** — code complete and unit-tested, but **never executed against devnet**; treasury/mint/key unverified |
| Ready for production USDC? | **NO** — mainnet deliberately unsupported, treasury signer unverified, dashboard not integrated, double-compensation question open |

**Key findings not previously surfaced:**

1. **Double-pay risk is real and verified**: captur-api inserts a `token_transaction` (ACTIVITY_REWARD, reward_type 29) atomically when a submission is APPROVED (`New Backend/internal/activity/admin.go:143,223`). APPROVED already earns the existing token/points reward. The USDC flow on the same trigger is a **second economic payout** unless product confirms replacement.
2. **Dashboard is not integrated at all**: `/payouts` page calls `/api/v1/payouts` + `/api/v1/payouts/distribute` — **these endpoints do not exist in captur-api's OpenAPI spec**. The page is stale UI for the old flow; nothing calls `/api/v1/admin/payments/*`.
3. **Contributor endpoints have a contract bug**: `GET /api/v1/users/me` **requires a `userId` query param** (verified in OpenAPI); `CapturApiClient.getMe()` doesn't send it → contributor identity resolution will fail.
4. **`GET /rewards/balance` returns global totals, not per-user** — `sumUnitsByStatus` has no user filter.
5. **`.env.example` is stale** — still documents the abandoned flat `REWARD_USDC_PER_CLIP` model.
6. **`payments.admin-roles` config is dead** — `AuthPrincipal.isAdmin` hardcodes `ADMIN/FINANCE/MODERATOR`, so **MODERATORs can approve payouts**.

---

## B. Implementation matrix

| Component | Status | Evidence | Missing work | Priority |
|---|---|---|---|---|
| Reward calculation | COMPLETE | `RewardCalculator.kt` — BigDecimal, HALF_UP 6dp, `v1-hourly-tier`, configurable rates | Confirm eligible-duration source | High |
| Device validation | PARTIAL | `device_capabilities` table + `VERIFIED`-only gate; unknown→INELIGIBLE | Capture-time evidence; `imuFrequencyHz`/`gyroSampleCount`/`accelSampleCount`/`syncTier` are in the API response but **ignored** by `CapturSubmission` DTO | High |
| Captur-api integration | PARTIAL | `CapturApiClient` — paginated, timeout, token-forwarded; `/users/{id}` verified in OpenAPI | `/users/me` missing required `userId` param; no retry/backoff; `poller_checkpoints` unused | High |
| Privy authentication | COMPLETE | `PrivyJwtVerifier` — JWKS, ES256, expiry check, fail-closed when unconfigured | Issuer/audience not explicitly validated (Nimbus defaults) | Medium |
| Admin authorization | PARTIAL | `AdminAuthService` → captur-api `/admin/auth/verify`, 60s cache, 401/403 in filter | `MODERATOR` hardcoded as admin; `admin-roles` env ignored | High |
| Contributor wallet resolution | PARTIAL | `seeker_wallet` confirmed = Solana (Go `wallet/handler.go:59` + `schema-prod.txt`); base58 check | Privy auto-generation not verified; `wallet_address` fallback may pick EVM address | High |
| Reward ledger | COMPLETE | `usdc_reward_ledger` — BIGINT units, `UNIQUE(submission_id)`, audit columns, status CHECK | Eligibility rejection reason not persisted (ineligible subs leave no row) | Medium |
| Payout batches | COMPLETE | create/approve/cancel/execute, `UNIQUE(idempotency_key)`, `UNIQUE(reward_id)`, approver≠creator, audit logs | Concurrent-execute race (no row lock on status transition) | Medium |
| Solana Devnet | PARTIAL | `SolanaRpcClient`/`TreasurySigner`/`UsdcPayoutExecutor` — transferChecked, ATA idempotent, devnet-only ×2 | **Never run live**; no balance check before send; no reconciler job | High |
| Transaction reconciliation | PARTIAL | Ambiguous send → `REQUIRES_RECONCILIATION`; signature persisted pre-confirmation; `findExistingSignature` exists | **No code path calls it** — reconciliation is manual only; no scheduled re-check | High |
| Admin dashboard | MISSING | `/payouts` page targets non-existent captur-api endpoints | Entire payments-service UI + proxy route needed | High |
| Production treasury | BLOCKED | `AdminTreasuryController` returns nulls; signer devnet-only by design | Treasury address/mint/signer custody all unverified | Critical |
| Deployment | PARTIAL | Dockerfile (non-root, JRE alpine) + cloudbuild.yaml (image only) | No Cloud Run service config, no Secret Manager wiring, no deploy step | Medium |

---

## C. Critical blockers

### 1. Code blockers
- `CapturApiClient.getMe()` missing required `userId` param → contributor endpoints broken
- `ContributorPaymentsController.rewardsBalance` returns **global** sums, not per-user
- Concurrent `executeBatch` race: status check-then-set isn't atomic
- `.env.example` documents removed flat-rate config (`REWARD_USDC_PER_CLIP`)
- `payments.admin-roles` env var unused; `MODERATOR` role can approve payouts

### 2. Database blockers
- None structural; schema is sound.

### 3. captur-api integration blockers
- `durationSeconds` = uploaded video duration, **not verified eligible capture duration** — spec §9.1 unresolved
- Submission DTO ignores `imuFrequencyHz`, `gyroSampleCount`, `accelSampleCount`, `syncTier` — all present in deployed response
- `poller_checkpoints` table exists but sync always restarts at offset 0, capped at `maxPages×pageSize` = 2,000 APPROVED submissions per run
- No machine/service auth on captur-api — auto-sync needs a long-lived admin token (`CAPTUR_API_SERVICE_TOKEN`)

### 4. Mobile blockers
- No capture-time hardware metadata contract (IMU Hz actually used, FOV, fps, lens) — registry is deviceModel-string matching only, spoofable and collision-prone
- `deviceSignature` field exists on submissions — unused for tier evidence

### 5. Privy wallet blockers
- Whether Privy actually auto-provisions the Solana wallet vs. user-onboarded via `/wallets/onboard` is **unverified** — the onboard endpoint accepts any address for SOLANA/TRON
- `seeker_wallet` vs `wallet_address` resolved: **SOLANA→`seeker_wallet`**, EVM→`wallet_address` (verified in Go rebuild; deployed Kotlin assumed same contract — NOT VERIFIED live)

### 6. Treasury/signer blockers
- Treasury address, cluster, USDC mint, ATA, balance, and signing custody **all unverified**; code refuses mainnet by design

### 7. Solana blockers
- Devnet RPC/mint/keypair not provisioned; executor never executed; no on-chain test evidence

### 8. Dashboard blockers
- Zero integration with payments service; existing `/payouts` UI calls non-existent endpoints

### 9. Product-policy blockers
- **Double compensation**: APPROVED already triggers `token_transaction` credit in captur-api. USDC-on-APPROVED is a second reward unless it replaces the token path — **needs explicit Sai/product ruling**
- USD rates → USDC 1:1 assumption documented but flagged pending confirmation (`application.yml:46`)

---

## D. Exact source evidence

### Reward engine
`rewards/RewardCalculator.kt:60-72`: `duration/3600 × rate`, `MathContext.DECIMAL64`, `setScale(6, HALF_UP)`, `movePointRight(6).longValueExact()` → integer base units. No floats. `calculation_version="v1-hourly-tier"` stamped per row (`RewardSyncService.kt:144`).

### Eligibility gate
`RewardSyncService.kt:99-117`: registry lookup on `lowercase(deviceModel)` → null or `verification != "VERIFIED"` → INELIGIBLE; invalid tier enum → INELIGIBLE; `durationSeconds == null || <= 0` → INELIGIBLE; zero reward → INELIGIBLE. Unknown devices can never auto-pay BASIC.

### Idempotency
- `RewardRepository.insertIfAbsent` → `INSERT ... ON CONFLICT (submission_id) DO NOTHING`
- `UNIQUE(submission_id)` (`V0001__payments_init.sql:31`)
- `UNIQUE(reward_id)` on batch items (`V0001:69`)
- `UNIQUE(idempotency_key)` on batches (`V0001:52`)
- Atomic reservation: `UPDATE ... WHERE status='AVAILABLE'` (`PayoutRepository.kt:152-163`)

### Payout lifecycle
`PayoutService.kt`: create (PENDING_APPROVAL, replay-safe) → approve (status gate + `approverId != createdBy`, `:126-130`) → execute → `PayoutExecutionService.kt:32-74`:
- requires APPROVED + `SOLANA_ENABLED` + `cluster=="devnet"`
- per-item PROCESSING → execute → persist signature → CONFIRMED only on `confirmed|finalized`
- partial failure → `REQUIRES_RECONCILIATION`
- reward `PAID` only via `markRewardPaid` after on-chain confirm (`:106-108`)

### Solana
`UsdcPayoutExecutor.kt:37-74`: refuses non-devnet (`:38`), requires mint+active signer, builds `createIdempotent` ATA + `transferChecked` (decimals-checked), signs treasury, `sendTransaction`, polls `getSignatureStatuses` until timeout.
`TreasurySigner.kt:20`: `active = enabled && cluster=="devnet" && key present`. Base58 key from env only — no key material in repo (`.env.example` placeholders only).

### Auth
`AuthFilter.kt`: all `/api/v1/**` except `/admin/auth/verify` require Bearer; admin paths additionally require captur-api role check; 401/403 written directly (filters precede `@RestControllerAdvice`).
`PrivyJwtVerifier.kt:68-79`: JWKS from URL, ES256 only, expiry enforced, **fail-closed when unconfigured** (`:41-44`).

### Schema
- `V0001__payments_init.sql` — 7 tables (contributor_wallets, usdc_reward_ledger, payout_batches, payout_batch_items, payout_transactions, payout_audit_logs, poller_checkpoints)
- `V0002__reward_calculation_fields.sql` — 5 audit columns on ledger
- `V0003__device_capabilities.sql` — capability registry with evidence fields + VERIFIED gate
- All money `BIGINT`. Service-owned only — no captur-api tables touched.

### Deployment
`Dockerfile`: multi-stage, non-root `app` user, JRE alpine.
`cloudbuild.yaml`: builds+tags image only — **no deploy step, no env/secret substitution**.

---

## E. Test report

**Command executed (fresh, this audit):** `mvn test` in `D:\CapturGo\reward` — **34 tests, 0 failures, 0 errors, BUILD SUCCESS**.

| Suite | Tests | Covers |
|---|---|---|
| `FlywayMigrationTest` | 6 | Real embedded PG: all tables, dup submission rejected, zero amount rejected, reward can't double-batch, dup idempotency key, bad status rejected |
| `AuthFilterTest` | 6 | Public-path skip, 401 missing/garbage token, valid token passes, admin role detection |
| `RewardCalculatorTest` | 6 | Spec examples (Full 1hr=$5, iPhone, Basic, 30min), partial seconds, zero/negative, conservative tier |
| `RewardSyncServiceTest` | 7 | Dup submission skip, APPROVED+verified creates row, unknown/unverified/missing-device INELIGIBLE, non-APPROVED skip, Solana address validation |
| `PayoutServiceTest` | 4 | Execute blocked when disabled, unavailable rewards rejected, idempotency replay, approver≠creator |
| `PayoutExecutionServiceTest` | 5 | Disabled reject, **mainnet reject even when enabled**, non-APPROVED reject, confirmed→PAID, ambiguous→reconciliation |

**Missing coverage:** no live devnet transaction test, no `CapturApiClient` contract test against real API shape, no repository-level batch SQL test (PayoutServiceTest mocks repo), no test for the `/users/me` userId bug, no concurrent-execute test, no dashboard contract test.

---

## F. Recommended implementation plan

### Phase 1 — Correctness fixes (no product input needed)
- Fix `getMe` to pass `userId` (or drop it — sync uses `getUser` anyway); fix `rewardsBalance` per-user filter; wire `admin-roles` config or remove MODERATOR; update `.env.example`; add batch-execute row lock (`UPDATE ... WHERE status='APPROVED'` claim pattern)
- **Acceptance:** contributor endpoints return correct per-user data; env config matches code
- **Risk:** low

### Phase 2 — Evidence & eligibility (needs mobile/product)
- Extend `CapturSubmission` DTO with `imuFrequencyHz`, `gyroSampleCount`, `accelSampleCount`, `syncTier`; persist per-submission evidence in ledger metadata; define whether registry-tier or submission-evidence wins
- **Acceptance:** tier decision auditable per submission; unknown evidence → INELIGIBLE
- **Depends on:** mobile contract for capture-time metadata; product ruling on duration source
- **Risk:** medium — changing tier logic retroactively affects pending rewards

### Phase 3 — Resolve double-compensation
- Get written ruling: does USDC-on-APPROVED replace or coexist with `token_transaction` credit? If coexist, is that intended?
- **Blocks:** any real payout at meaningful scale

### Phase 4 — Devnet end-to-end
- Provision devnet USDC mint + treasury keypair (Secret Manager), register VERIFIED devices via `POST /admin/devices`, run sync→batch→approve→execute against devnet, verify signature on explorer, exercise timeout/reconciliation path
- **Acceptance:** real devnet USDC lands in test wallet; `payout_transactions` shows CONFIRMED; reward PAID only post-confirmation
- **Depends on:** treasury decision, Phase 1-2

### Phase 5 — Dashboard + production
- New `/payouts` UI (or rewrite) calling `/api/v1/admin/payments/*` through `/api/backend` proxy or dedicated payments proxy route; payment detail endpoint (currently stub); reconciler job for `REQUIRES_RECONCILIATION`; monitoring/alerting; mainnet custody decision (KMS/multisig — code currently refuses mainnet)
- **Acceptance:** admin can run full lifecycle from UI; production checklist signed off

---

## G. Final readiness verdict

| # | Question | Verdict | Evidence |
|---|---|---|---|
| 1 | Correct hourly USDC reward? | **YES** (duration-source caveat) | `RewardCalculator.kt` + spec-example tests pass |
| 2 | Reliable device eligibility? | **PARTIAL** | Registry gate works; evidence is deviceModel-string only; capture-time fields ignored |
| 3 | Correct Privy Solana wallet? | **PARTIAL** | `seeker_wallet` verified as Solana field; live response shape NOT VERIFIED; Privy auto-provisioning unconfirmed |
| 4 | APPROVED without paying VERIFIED? | **YES** | Status filter + double status check; VERIFIED never enters flow |
| 5 | Duplicate rewards prevented? | **YES** | DB unique + ON CONFLICT + atomic reservation, all tested |
| 6 | Admin batch lifecycle? | **YES** | create/review/approve/cancel/execute implemented, approver≠creator, audited |
| 7 | Safe Devnet USDC transfer? | **PARTIAL** | Code complete, unit-tested, devnet-locked; never executed on-chain |
| 8 | Production treasury signer? | **NO** | Devnet keypair-from-env only; no KMS/multisig; treasury details unverified |
| 9 | Confirm & reconcile on-chain? | **PARTIAL** | Confirmation polling + signature persistence + reconciliation states exist; no automated reconciler; `findExistingSignature` unused |
| 10 | Ready for production USDC? | **NO** | Blocked on double-pay ruling, treasury custody, devnet E2E proof, dashboard integration, contract bugs |

---

*Audit rules honored: no code modified, no migrations applied, nothing deployed, no transactions sent, no secrets accessed.*
