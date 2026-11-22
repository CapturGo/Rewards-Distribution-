# User Wallet & Payout-Readiness Audit

**Source:** production DB `captur-go-v2-dest` (instance `capturgo-478506:asia-east2:captur-go-v2-dest`), queried 2026-11-20 via cloud-sql-proxy.
**Scope:** `users` and `activity_submissions` tables — wallet fields, admin roles, approved-reward backlog.

---

## 1. User wallet fields

Table `users` contains **two** wallet columns. Only one is usable for Solana payouts.

| Column | Meaning | Count (of 6,166 users) | Payout-usable? |
|---|---|---|---|
| `seeker_wallet` | Solana address (base58) | **96** | ✅ Yes — this is the payout field |
| `wallet_address` | EVM `0x...` address | 822 | ❌ No — wrong chain, legacy field |
| Both populated | — | 10 | ✅ (Solana wallet present) |

### Data-quality checks on `seeker_wallet`

- `0x`-prefixed values: **0** — no EVM addresses leaked into the Solana field.
- Non-base58 values: **0** — all 96 values are valid Solana address format.

> ⚠️ **Trap:** payout code must read `seeker_wallet` only. The 822 `wallet_address` (EVM) values must never be used for USDC-on-Solana transfers.

---

## 2. Admin accounts

Users with `ADMIN` role in `users.roles`:

| Email | Roles | Created |
|---|---|---|
| sai@capturgo.com | USER, ADMIN | 2026-02-04 |
| rahul@capturgo.com | USER, ADMIN | 2025-12-29 |
| tin@capturgo.com | USER, ADMIN | 2026-04-22 |

**Note:** there are **3** admin accounts, not 1. If policy is single-admin approval, Sai must confirm explicitly — the payout code was built to *optionally* enforce two-admin approval.

---

## 3. Submission status & payout backlog

`activity_submissions.status` (smallint): `0`=PENDING, `1`=APPROVED, `-1`=REJECTED.

| Status | Count |
|---|---|
| APPROVED (1) | 193 |
| PENDING (0) | 281 |
| REJECTED (-1) | 195 |

### Approved submissions without a wallet

- **25 distinct users** have APPROVED submissions but **no `seeker_wallet`**.
- These users **cannot be paid** until either:
  1. They log into the app with the wallet-sync fix (**PR #142** merged + released), or
  2. An admin sets a Solana wallet via `PUT /users/me` / admin tooling.

---

## 4. Payout readiness summary

| Group | Count | Can be paid now? |
|---|---|---|
| Users with valid `seeker_wallet` | 96 | ✅ Yes, once `SOLANA_*` config is live on Devnet |
| Users with approved submissions but no wallet | 25 | ❌ Blocked on wallet sync (PR #142) or manual entry |
| Users with only EVM `wallet_address` | ~812 | ❌ Wrong chain — needs Solana wallet |
| Remaining users | ~6,050 | ⚪ No approved rewards / no wallet on file |

---

## 5. Still blocking the demo

1. **Devnet SOL for treasury** `GVCcANPUcjPcw3vQ2EEKJZMknec3ERUebTZVsrFxqqts` — balance 0; all programmatic faucet paths exhausted (RPC airdrop closed, PoW faucets empty/underfunded). Needs a human faucet action (faucet.solana.com or devnetfaucet.org) or a free Helius/QuickNode/Triton API key.
2. **Sai's policy confirmations** — single vs. two-admin approval, double-pay policy vs. legacy token rewards, `$5/hour` formula.
3. **PR #142 merge + app release** — required for automatic wallet sync for all users (not needed for the immediate demo if a test wallet is set via API).
