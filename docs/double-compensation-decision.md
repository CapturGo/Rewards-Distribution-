# Double-Compensation Decision — Required Before USDC Payouts at Scale

**Status:** AWAITING Sai's written decision. Do not enable `REWARD_AUTO_SYNC`
or execute payouts until resolved.

## Verified existing behavior (captur-api, production)

When an admin approves an activity submission (`POST /api/v1/admin/activities/submissions/{id}/approve` with `status=APPROVED`), captur-api **atomically**:

1. Updates `activity_submissions.status` → APPROVED (status smallint `1`)
2. Inserts a `token_transaction` row with `activity_type = 29` (ACTIVITY_REWARD)
   crediting the user's **token/points balance** with the submission's
   `reward_amount` / `reward_currency`

Evidence: live OpenAPI (`/v3/api-docs`, 131 paths) + prod schema
(`activity_submissions.reward_amount`, `token_transaction`, `reward_type` map)
+ Go rebuild mirror `internal/activity/admin.go:143-230` (approve handler is
atomic: lock row → update status → insert token_transaction on APPROVED).

There is **no ACCEPTED status** in the current prod enum mapping
(`0`=PENDING, `1`=APPROVED, `-1`=REJECTED) — the old dashboard `/payouts` page
querying `status:"ACCEPTED"` is stale.

## The overlap

The Payments Service sync also keys on `APPROVED`. The same submission
therefore generates **two economic rewards**:

- token/points credit via `token_transaction` (existing system — untouched)
- USDC ledger entry → admin payout batch → Solana transfer (new system)

## Alternatives for Sai's decision

| Option | Description | captur-api change? | Payments change? | Risk |
|---|---|---|---|---|
| **A. Replace** | USDC becomes the only reward; captur-api stops crediting tokens on APPROVED | Yes (Rahul) | Sync stays as-is | Breaks existing points economy; mobile UI shows points |
| **B. Coexist** | Tokens = gamification/points, USDC = cash; both intended | No | None | Contributors earn twice per clip — confirm budget intent |
| **C. Cutoff date** | USDC only for submissions approved after a cutoff; historical APPROVED excluded | No | Add `reviewedAt >= cutoff` filter in `RewardSyncService` | Clean, no upstream change; needs date decision |
| **D. Later status trigger** | USDC triggers on a post-approval status (e.g. SETTLED) after ops review | Maybe | Change sync filter + verify enum semantics live | Adds ops gate; enum mapping for SETTLED unverified in prod |

**Recommendation:** C (cutoff) or D (later trigger) — preserves the existing
token economy, gives an explicit review gate, requires no captur-api change.
Option A only if product intends USDC to fully replace points.

## Implementation hooks already in place

- `RewardSyncService.processSubmission` — single point where an additional
  eligibility predicate (cutoff date, alternate status) can be added.
- `usdc_reward_ledger.metadata` JSONB — can record the policy version applied.
- Sync remains manual (`POST /admin/payments/sync`) and scheduled polling is
  disabled by default (`REWARD_AUTO_SYNC=false`).
