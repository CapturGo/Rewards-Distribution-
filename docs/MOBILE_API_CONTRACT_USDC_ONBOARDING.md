# CapturGO Mobile API Contract — USDC Rewards & Household Asia Onboarding

For the mobile dev. Two backend services, both live in production.

| Service | Base URL | Auth |
|---|---|---|
| captur-go-api | `https://captur-go-api-208129623932.asia-east2.run.app/api/v1` | `Authorization: Bearer <Privy JWT>` |
| captur-payments | `https://captur-payments-208129623932.asia-east2.run.app` | `Authorization: Bearer <Privy JWT>` |

Notes:
- All requests need the Privy access token in the `Authorization` header. No token → `401`.
- Payments endpoints accept an optional `?userId=<uuid>` query param. It is only needed on the **very first** call for a user (identity mapping); after that the JWT alone resolves the user. Passing it is harmless.
- Errors return `{ "errorCode", "numericCode", "error", "message", "timestamp", "path", "requestId" }`.

---

## 1. Household Asia — "Done" button (captur-go-api)

Replaces the "Submit Email Form" stub on `HouseholdAsiaScreen` (branch `new_captur_ai`).

### Check existing status (call on screen open)

```
GET /api/v1/onboarding/partner/status
Authorization: Bearer <token>
```

Response `200`:
```json
{ "status": "SUBMITTED" }
```
Status values: `SUBMITTED` | `VERIFIED` | `REJECTED` | `NONE` (no record yet — endpoint may return 404 or empty; treat both as "show the Done button").

### Submit (tap Done)

```
POST /api/v1/onboarding/partner
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "partnerEmail": "<logged-in user's email from session>",
  "recorderCode": "QUAXTJ"
}
```

- `partnerEmail` **must equal the account email** (case-insensitive) — the app already has it from the session; do NOT ask the user to type it.
- `recorderCode` is always `"QUAXTJ"`.

Response `200` / `201`:
```json
{ "id": "...", "status": "SUBMITTED" }
```

Errors:
- `400 EMAIL_MISMATCH` — session email ≠ partnerEmail sent (shouldn't happen if you use the session email)
- `400 INVALID_RECorder_CODE` — wrong code
- `409` — already submitted/verified (idempotent — safe to treat as success and show status)
- Network failure → show retry toast; do not mark as submitted locally.

UI states:
- Tap → "We're checking your Atlas registration." (pending)
- `SUBMITTED` → "Onboarding submitted — pending review." + hide button, show status
- `VERIFIED` → eligible badge
- `REJECTED` → show reason, tell user to check Atlas email matches + recorder code entered + has recorded

---

## 2. USDC balance — Profile screen (captur-payments)

```
GET /api/v1/rewards/balance
Authorization: Bearer <token>
```

Response `200`:
```json
{
  "userId": "uuid",
  "availableUsdc": 12.50,
  "reservedUsdc": 0.00,
  "paidUsdc": 48.20,
  "totalEarnedUsdc": 60.70
}
```

| Field | Meaning | Profile display |
|---|---|---|
| `availableUsdc` | Earned, awaiting payout batch | "Pending payout" |
| `reservedUsdc` | In an approved/executing batch | "Processing" |
| `paidUsdc` | Confirmed on-chain | "Paid" |
| `totalEarnedUsdc` | Sum of all three | "Total earned" |

Amounts are USD-denominated USDC (1 USDC ≈ $1). Values are decimal numbers, not strings.

---

## 3. Payment history — Profile / wallet screen (captur-payments)

```
GET /api/v1/payments/history
Authorization: Bearer <token>
```

Response `200`:
```json
{
  "items": [
    {
      "id": "uuid",
      "userId": "uuid",
      "username": null,
      "email": null,
      "amountUsdc": 4.50,
      "solanaAddress": "5QLP...",
      "batchId": "uuid",
      "batchStatus": "EXECUTED",
      "txSignature": "5xK9...solana-tx-signature",
      "status": "PAID",
      "createdAt": "2026-11-26T10:00:00Z",
      "paidAt": null
    }
  ],
  "totalItems": 1
}
```

- `status`: `PAID` | `PENDING` | `FAILED` | `RESERVED_FOR_PAYOUT` (values from `payout_batch_items.status`)
- `txSignature` — present only when PAID; link to `https://solscan.io/tx/<signature>` or `https://explorer.solana.com/tx/<signature>` so users can verify on-chain.
- Max 100 items returned, newest first.

---

## 4. Wallet status (captur-payments)

```
GET /api/v1/wallet
Authorization: Bearer <token>
```

Response `200`:
```json
{
  "userId": "uuid",
  "solanaAddress": "5QLP..." ,
  "verification": "VERIFIED",
  "verifiedAt": "2026-11-20T..."
}
```

`verification`: `VERIFIED` | `PENDING` | `MISSING` — show a "link wallet" prompt when `MISSING`/`PENDING`.

---

## 5. Clip counts — existing endpoint (captur-go-api)

Already used by the app (`fetchActivityTallies`):

```
GET /api/v1/activities/tallies?userId=<uuid>
Authorization: Bearer <token>
```

```json
{
  "accepted": 12,
  "pending": 3,
  "rejected": 1,
  "totalEarnedUsd": 40.17,
  "totalSettledUsd": 0,
  "totalSubmitted": 16
}
```

⚠️ **`totalEarnedUsd` here is legacy** — it sums `reward_amount` on submissions, which is ~0 for Household Asia clips. **Do NOT use it for the USD display** — use `rewards/balance` instead. `accepted`/`pending`/`rejected` counts are correct.

---

## Profile screen wiring summary

| Card field | Endpoint | Field |
|---|---|---|
| Total Earned (USD) | `captur-payments /api/v1/rewards/balance` | `totalEarnedUsdc` |
| Pending payout | same | `availableUsdc` + `reservedUsdc` |
| Paid | same | `paidUsdc` |
| Accepted clips | `captur-go-api /api/v1/activities/tallies` | `accepted` |
| Payment history list | `captur-payments /api/v1/payments/history` | `items` |
| Wallet address/verify | `captur-payments /api/v1/wallet` | `solanaAddress`, `verification` |
| Household Asia status | `captur-go-api /api/v1/onboarding/partner/status` | `status` |

Refresh on: screen open, pull-to-refresh, app foreground. Never compute totals client-side.

## Environment / base URLs

- `EXPO_PUBLIC_API_BASE_URL` currently defaults to `https://captur-api-...run.app/api/v1` (legacy Kotlin). The payments service is a **different host** — add a second base URL config (e.g. `EXPO_PUBLIC_PAYMENTS_BASE_URL=https://captur-payments-208129623932.asia-east2.run.app`).
