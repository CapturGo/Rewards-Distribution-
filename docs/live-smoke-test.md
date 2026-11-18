# Live Smoke Test — Payments Service ↔ captur-api

Verifies the four live integrations before Devnet E2E. Mock/unit tests do NOT
cover these paths — this test requires **real Privy tokens** against the
deployed `captur-api`.

Run against a locally started service or a deployed dev instance. All calls
are read-only except `/sync`, which only writes to the payments service's own
database — captur-api is never modified.

## Prerequisites

### Tokens needed (obtain from real logged-in sessions — do NOT paste into docs/tickets)

| Token | How to get | Used for |
|---|---|---|
| `ADMIN_TOKEN` | Privy access token of an ADMIN or FINANCE user (e.g. dashboard login) | Checks 1, 3, 4 |
| `CONTRIBUTOR_TOKEN` | Privy access token of a contributor who has ≥1 APPROVED submission and a `seeker_wallet` | Check 2 |

Tokens expire (~1h typical for Privy). Refresh before running.

### Service environment

```bash
export BASE_URL=http://localhost:8081          # or deployed service URL
export PRIVY_APP_ID=<capturgo privy app id>    # required — JWT verification fails without it
export DATABASE_URL=jdbc:postgresql://<host>:5432/captur_payments
export DB_USER=...  DB_PASSWORD=...            # payments DB only
# CAPTUR_API_URL defaults to the live service — override only for staging
```

`SOLANA_*` vars are NOT needed — nothing on-chain happens in this test.
`REWARD_AUTO_SYNC` stays `false`; sync is triggered manually via the endpoint.

## Check 0 — Service health (no token)

```bash
curl -s $BASE_URL/health    # expect: {"status":"UP"}
curl -s $BASE_URL/ready     # expect: {"status":"UP", ...db check...}
```

## Check 1 — Approved submissions sync (ADMIN_TOKEN)

```bash
curl -s -X POST $BASE_URL/api/v1/admin/payments/sync \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Expected (200):**
```json
{
  "scanned": <n>, "created": <n>, "skippedExisting": <n>,
  "skippedIneligible": <n>, "walletsResolved": <n>, "walletsMissing": <n>,
  "errors": [], "finishedAt": "<iso>"
}
```

This proves: admin auth accepted → captur-api `GET /admin/activities/submissions`
returned APPROVED rows with the forwarded token → rewards written to OUR db.
`skippedIneligible` > 0 is normal (unverified devices). `errors` non-empty =
investigate before continuing.

Verify persistence:
```bash
curl -s "$BASE_URL/api/v1/admin/payments/ready?limit=5" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
# expect: list of AVAILABLE rewards with amounts, device tiers, userIds
```

## Check 2 — Privy identity + contributor wallet (CONTRIBUTOR_TOKEN)

First call resolves and caches the Privy→CapturGO mapping:

```bash
curl -s "$BASE_URL/api/v1/rewards/balance" \
  -H "Authorization: Bearer $CONTRIBUTOR_TOKEN"
```

**Expected (200):**
```json
{
  "userId": "<captur-go uuid>",
  "availableUsdc": "...", "reservedUsdc": "...",
  "paidUsdc": "...", "totalEarnedUsdc": "..."
}
```

Then wallet:
```bash
curl -s "$BASE_URL/api/v1/wallet" \
  -H "Authorization: Bearer $CONTRIBUTOR_TOKEN"
# expect: {"userId":"...","solanaAddress":"<base58>","verification":"..."}
```

**What this proves:** Privy JWT verified locally → `users/me?userId=` on
captur-api accepted the forwarded token for that userId (ownership proven
server-side) → mapping cached in `contributor_identities` → per-user balance
scoped correctly → `seeker_wallet` retrieved as payout destination.

Optional first-call claim: `?userId=<uuid>` may be passed ONCE; it is verified
against captur-api before being stored. A wrong userId must yield 4xx and NO
mapping.

## Check 3 — Admin authorization boundary

```bash
# ADMIN_TOKEN → expect 200
curl -s $BASE_URL/api/v1/admin/auth/verify -H "Authorization: Bearer $ADMIN_TOKEN"
# expect: {"authenticated":true,"roles":["ADMIN"],"email":"...","userId":"..."}

# CONTRIBUTOR_TOKEN → expect 403 (authenticated but not admin)
curl -s -o /dev/null -w "%{http_code}" \
  "$BASE_URL/api/v1/admin/payments/ready" -H "Authorization: Bearer $CONTRIBUTOR_TOKEN"

# No token → expect 401
curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/admin/payments/ready"

# MODERATOR token (if available) → expect 403 on payout endpoints
curl -s -o /dev/null -w "%{http_code}" \
  "$BASE_URL/api/v1/admin/payments/ready" -H "Authorization: Bearer $MODERATOR_TOKEN"
```

Roles come from captur-api `/admin/auth/verify`, filtered by
`PAYMENTS_ADMIN_ROLES` (default `ADMIN,FINANCE`). MODERATOR must NOT pass.

## Check 4 — Read paths + audit surface

```bash
curl -s "$BASE_URL/api/v1/admin/payments/batches" -H "Authorization: Bearer $ADMIN_TOKEN"
curl -s "$BASE_URL/api/v1/admin/payments" -H "Authorization: Bearer $ADMIN_TOKEN"
curl -s "$BASE_URL/api/v1/payments/history" -H "Authorization: Bearer $CONTRIBUTOR_TOKEN"
```

All should return 200 with empty or populated lists — proves repository reads
against the real schema (all 5 migrations applied).

## Failure cases — expected vs actual

| Symptom | Likely cause | Action |
|---|---|---|
| 401 on every call | `PRIVY_APP_ID` unset/wrong, or expired token | Check env; refresh token |
| 403 for ADMIN_TOKEN | captur-api role not ADMIN/FINANCE, or `PAYMENTS_ADMIN_ROLES` misconfigured | Verify role via captur-api directly |
| sync `errors` non-empty | captur-api unreachable, token rejected upstream, or submission DTO drift | Read error strings; check `CAPTUR_API_URL` |
| balance returns wrong/zero userId | identity mapping failed; userId claim rejected | Check `contributor_identities` table + captur-api `users/me` behavior |
| `solanaAddress: null` / `verification: MISSING` | contributor has no `seeker_wallet` in captur-api | Expected for users who never onboarded wallet — NOT a payments bug |
| Flyway errors on startup | DB unreachable or migrations partially applied | Check `DATABASE_URL`; inspect `flyway_schema_history` |

## Pass criteria

- [ ] Check 1: sync returns 200, `errors: []`, rewards visible in `/ready`
- [ ] Check 2: balance + wallet return 200 with correct per-user scoping
- [ ] Check 3: admin 200 / contributor 403 / no-token 401 / moderator 403
- [ ] Check 4: all list endpoints 200
- [ ] DB inspection: `contributor_identities` has 1 row for contributor;
      `usdc_reward_ledger` rows match synced APPROVED submissions;
      `submission_device_evidence` rows exist for synced submissions

Only after ALL pass → proceed to Devnet E2E (docs/solana-devnet-setup.md).
