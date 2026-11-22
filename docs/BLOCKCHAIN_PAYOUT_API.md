# CapturGO — Blockchain Payout Integration API

**Audience:** external blockchain/payment engineer
**Status:** Solana **Devnet** (test USDC) for the current trial. Mainnet at go-live.
**Last updated:** 2026-11-22

---

## 1. System overview

CapturGO runs three backend services. Only **captur-payments** is relevant to
blockchain payouts — it already implements the full pipeline end-to-end.

| Service | Base URL | Role |
|---|---|---|
| **captur-payments** (reward & payout engine) | `https://captur-payments-208129623932.asia-east2.run.app` | Reward calculation, reward ledger, payout batches, Solana USDC execution, on-chain verification, reconciliation. **This is the integration target.** |
| captur-go-api | `https://captur-go-api-208129623932.asia-east2.run.app` | Go backend — activities, submissions, users, admin analytics. Read-only upstream context; not called by the blockchain layer. |
| captur-api (legacy) | `https://captur-api-208129623932.asia-east2.run.app` | Legacy Kotlin API serving remaining routes. Not part of the payout path. |
| **Admin Dashboard** (UI) | `https://captur-dashboard-208129623932.asia-east2.run.app` | Web console where admins run the payout flow (Payouts page: sync rewards → create batch → approve → execute). All actions call captur-payments with your Privy JWT. |

All API paths below are relative to the captur-payments base URL.

> **Note:** `GET /` on the base URL returns a small service descriptor
> (`service`, `health`, `ready`, `api` pointers) — there is no other root
> content. All functional endpoints are under `/api/v1/...`.

---

## 2. Blockchain details

| Item | Value |
|---|---|
| Network | Solana — **Devnet** for trial, Mainnet at go-live |
| Token | SPL **USDC**, 6 decimals |
| Amount encoding | Integer **base units** (e.g. `132451447` = 132.451447 USDC). `amountUsdcUnits: Long` |
| Recipient wallets | Solana address (base58), one per contributor — resolved server-side from their registered `seeker_wallet` / Privy embedded wallet |
| Source wallet | CapturGO treasury — keypair held server-side. **The private key is never exposed via API and must never be requested or shared.** |

Reward amounts are computed per approved submission:

```
reward = eligible_duration_seconds / 3600 × device-tier hourly rate
```

| Device tier | Rate |
|---|---|
| FULL | $5.00 / hour |
| IPHONE | $4.50 / hour |
| BASIC | $3.75 / hour |

Only submissions with `status = APPROVED`, a `VERIFIED` device-capability
registry entry, valid duration, and positive computed reward become ledger rows.
Sync is idempotent — `usdc_reward_ledger.submission_id` is unique; re-running
never double-creates rewards.

---

## 3. Payout flow (already implemented)

```
POST /api/v1/admin/payments/sync
   └─ scans APPROVED submissions → creates usdc_reward_ledger rows (AVAILABLE)

POST /api/v1/admin/payments/batches            { rewardIds: [...] }
   └─ groups AVAILABLE ledger rows → PayoutBatch (PENDING_APPROVAL)

POST /api/v1/admin/payments/batches/{id}/approve
   └─ maker-checker: approver MUST differ from batch creator → APPROVED

POST /api/v1/admin/payments/batches/{id}/execute
   └─ broadcasts Solana USDC transfers → records tx signatures
   └─ on-chain confirmation + reconciliation → rewards marked PAID
```

A batch can be `cancel`led while not yet executed. Failed items are marked
`FAILED` with a reason in the audit log; there is no automatic retry of failed
signatures — failed rewards can be included in a new batch (idempotency prevents
double-payment).

---

## 4. Authentication

Every request requires a Privy Bearer JWT:

```
Authorization: Bearer <privy_jwt>
Content-Type: application/json
```

- **Admin endpoints** (`/api/v1/admin/*`): the JWT resolves to an application
  user server-side; caller must hold the `ADMIN` role. Role is never accepted
  from the client.
- **Contributor endpoints** (`/api/v1/payments/*`, `/api/v1/rewards/*`,
  `/api/v1/wallet`): scoped to the authenticated user's own data. On first call
  a `userId` UUID query param binds the Privy identity to the CapturGO account;
  afterwards it is optional.

HTTP semantics: `401` missing/invalid token · `403` authenticated but not
permitted · `404` unknown resource · `422` semantically invalid request.

---

## 5. Admin API

Base: `/api/v1/admin`

### 5.1 Reward sync

```
POST /payments/sync
```

No request body. Returns `RewardSyncResult`:

```json
{
  "scanned": 193,
  "created": 193,
  "skippedExisting": 0,
  "skippedIneligible": 0,
  "walletsResolved": 193,
  "walletsMissing": 0,
  "errors": [],
  "finishedAt": "2026-11-22T14:41:00Z"
}
```

### 5.2 Payout batches

```
POST /payments/batches
```

Request — `CreateBatchRequest`:

```json
{
  "rewardIds": ["uuid", "uuid"],
  "idempotencyKey": "optional-client-key"
}
```

Response `201` — `PayoutBatch`:

```json
{
  "id": "uuid",
  "status": "PENDING_APPROVAL",
  "totalUsdcUnits": 132451447,
  "itemCount": 193,
  "cluster": "devnet",
  "idempotencyKey": "...",
  "createdBy": "uuid",
  "approvedBy": null,
  "approvedAt": null,
  "executedAt": null,
  "createdAt": "2026-11-22T15:00:00Z"
}
```

```
GET /payments/batches?limit=50&offset=0&status=PENDING_APPROVAL
```

```json
{ "items": [ /* PayoutBatch[] */ ], "totalItems": 0, "limit": 50, "offset": 0 }
```

```
GET /payments/batches/{id}
```

Response — `BatchDetailDto`:

```json
{
  "batch": { /* PayoutBatch */ },
  "items": [
    {
      "id": "uuid",
      "batchId": "uuid",
      "rewardId": "uuid",
      "capturUserId": "uuid",
      "solanaAddress": "base58-address",
      "amountUsdcUnits": 500000,
      "status": "PENDING",
      "txSignature": null,
      "failureReason": null,
      "createdAt": "2026-11-22T15:00:00Z"
    }
  ]
}
```

```
POST /payments/batches/{id}/approve     → PayoutBatch (status APPROVED)
POST /payments/batches/{id}/cancel      → PayoutBatch (status CANCELLED)
POST /payments/batches/{id}/execute     → PayoutBatch (status EXECUTING→…)
```

### 5.3 Payment history

```
GET /payments?limit=50&offset=0&status=&userId=
```

```json
{
  "items": [
    {
      "id": "uuid",
      "userId": "uuid",
      "username": "...",
      "email": "...",
      "amountUsdc": 5.00,
      "solanaAddress": "...",
      "batchId": "uuid",
      "batchStatus": "CONFIRMED",
      "txSignature": "solana-signature-base58",
      "status": "PAID",
      "createdAt": "...",
      "paidAt": "..."
    }
  ],
  "totalItems": 0, "limit": 50, "offset": 0
}
```

### 5.4 Treasury & readiness

```
GET /admin/payments/ready          → readiness probe
GET /admin/treasury/balance        → TreasuryBalanceDto
```

`TreasuryBalanceDto`:

```json
{
  "cluster": "devnet",
  "treasuryAddress": "base58",
  "usdcUnits": 1000000000,
  "solanaUnits": 123456789,
  "enabled": true,
  "fetchedAt": "...",
  "note": null
}
```

### 5.5 Device registry (reward tiers)

```
GET  /admin/devices
POST /admin/devices
```

Admin-managed mapping of device models → capability tier (`FULL` / `IPHONE` /
`BASIC`) and `VERIFIED` status. Only `VERIFIED` devices earn rewards.

---

## 6. Contributor API

Base: `/api/v1` (no `/admin`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/payments/history?userId=` | Contributor's payment history (max 100) |
| GET | `/rewards/balance?userId=` | `availableUsdc` / `reservedUsdc` / `paidUsdc` / `totalEarnedUsdc` |
| GET | `/wallet?userId=` | Contributor's registered Solana wallet |

---

## 7. Status models

### Reward ledger (`usdc_reward_ledger.status`)

| Status | Meaning |
|---|---|
| `PENDING` | Created, not yet eligible |
| `AVAILABLE` | Eligible for payout batches |
| `RESERVED_FOR_PAYOUT` | Locked into a batch awaiting execution |
| `PAID` | On-chain transfer confirmed |
| `REVERSED` | Clawed back (submission rejected post-reward) |

### Payout batch (`BatchStatus`)

`CREATED` → `PENDING_APPROVAL` → `APPROVED` → `PROCESSING` → `SUBMITTED` →
`CONFIRMED` · terminal/exception: `FAILED`, `CANCELLED`,
`REQUIRES_RECONCILIATION`

### Batch item (`ItemStatus`)

`PENDING` → `PROCESSING` → `SUBMITTED` → `CONFIRMED` · `FAILED`,
`REQUIRES_RECONCILIATION`

---

## 8. Transaction IDs & reconciliation

- Every broadcast stores its **Solana transaction signature** in
  `payout_transactions`, exposed as `txSignature` on batch items and payment
  history rows.
- A background **reconciliation** job re-checks `SUBMITTED`/pending signatures
  on-chain and promotes them to `CONFIRMED` (or flags
  `REQUIRES_RECONCILIATION` for manual review).
- `payout_audit_logs` records every admin action (create/approve/execute/
  cancel) with actor, IP, and user-agent.

---

## 9. Failure & pending semantics

| Case | Behavior |
|---|---|
| Broadcast fails (RPC error, insufficient funds) | Item → `FAILED`, `failureReason` populated, batch continues for other items |
| Signature submitted, confirmation pending | Item → `SUBMITTED`; reconciliation confirms asynchronously |
| On-chain tx fails / drops | Item → `FAILED` or `REQUIRES_RECONCILIATION`; never silently retried (double-pay protection via unique `submission_id` + item-level idempotency) |
| Retry | Create a new batch containing the failed reward — it is still `AVAILABLE`/unpaid |
| Batch-level abort | `POST .../cancel` while pre-execution |

---

## 10. What we need from you

**Nothing to build.** CapturGO's backend already performs Solana Devnet USDC
payouts end-to-end: reward sync → batch → maker-checker approval → transfer →
on-chain verification → reconciliation → `PAID`.

Your involvement is advisory unless scope changes:

- Mainnet RPC endpoint + treasury funding plan at go-live
- Optional: wallet-creation/UX guidance on the mobile app side
- If you intended to **own** the chain layer (e.g., custody treasury keys or
  run your own payout service), tell us now — the `PAYOUT_PROVIDER`
  abstraction supports alternate providers, but custody is a deliberate
  security decision, not an API detail.

---

## 11. Quick reference — curl examples

```bash
BASE=https://captur-payments-208129623932.asia-east2.run.app
TOKEN=<privy_jwt>

# readiness
curl -H "Authorization: Bearer $TOKEN" $BASE/api/v1/admin/payments/ready

# treasury balance
curl -H "Authorization: Bearer $TOKEN" $BASE/api/v1/admin/treasury/balance

# sync rewards from approved submissions
curl -X POST -H "Authorization: Bearer $TOKEN" $BASE/api/v1/admin/payments/sync

# create batch (rewardIds from the ledger)
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"rewardIds":["<uuid>","<uuid>"]}' \
  $BASE/api/v1/admin/payments/batches

# approve (must be a DIFFERENT admin than creator)
curl -X POST -H "Authorization: Bearer $TOKEN" \
  $BASE/api/v1/admin/payments/batches/<batch-id>/approve

# execute on Solana Devnet
curl -X POST -H "Authorization: Bearer $TOKEN" \
  $BASE/api/v1/admin/payments/batches/<batch-id>/execute

# batch detail incl. tx signatures
curl -H "Authorization: Bearer $TOKEN" \
  $BASE/api/v1/admin/payments/batches/<batch-id>
```

---

*Questions or contract gaps: reply with the endpoint and expected vs actual
behavior — the service is under active trial hardening.*
