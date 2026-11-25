# Solana Addresses — Real vs Test Reference

> **Rule: only use the ✅ REAL addresses below for payouts and verification.
> The ❌ TEST mint is internal bring-up only — never share it as the payout token.**

Last updated: 2026-11-25

---

## Wallets

| Address | Network | Status | Notes |
|---|---|---|---|
| `5QLPVVcCUwRoUqF3dC9sgM2mPQbS27Y3FcZj4MVdN88N` | Solana **Mainnet** | ✅ **ACTIVE TREASURY** | Reward-distribution wallet provided by Lynn (blockchain dev). Holds **~50.70 USDC + ~0.017 SOL**. Private key configured on `captur-payments` (`SOLANA_TREASURY_SECRET_KEY`) — never share or commit. |
| `GVCcANPUcjPcw3vQ2EEKJZMknec3ERUebTZVsrFxqqts` | Solana **Devnet** | ✅ TEST WALLET | Legacy devnet treasury (~7.5 devnet SOL + test USDC). Kept for pipeline testing only — **not used for real payouts**. |

> An earlier generated mainnet keypair (`3LtTvM1R...` / rotated `JE9tATMt...`)
> was superseded by Lynn's wallet — do not use those addresses.

## Tokens (USDC mints)

| Mint address | Network | Issuer | Status | Use for |
|---|---|---|---|---|
| `EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v` | Solana **Mainnet** | Circle (official) | ✅ **REAL USDC — IN USE** | All mainnet payouts. Real money. |
| `4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU` | Solana **Devnet** | Circle (official) | ✅ REAL USDC (test funds) | Devnet pipeline testing only. |
| `A6jadAHqc2y8LzwFYFoBmKEMw88tUiVV4CxfzvF1b95H` | Solana Devnet | **CapturGO (self-issued)** | ❌ **TEST-ONLY — DO NOT USE FOR PAYOUTS** | Internal pipeline bring-up mint. NOT USDC — Solscan shows it as an unknown token. |

## Network cheat-sheet

Solana has **three separate networks** — funds and addresses do not cross between them:

| Network | Money | Used by CapturGO? |
|---|---|---|
| **Mainnet** | Real dollars | ✅ **YES — live payouts** (`captur-payments` revision `00013-nq7`, `SOLANA_CLUSTER=mainnet-beta`) |
| **Devnet** | Free test tokens | ✅ YES — pipeline testing |
| **Testnet** | Free test tokens (different chain!) | ❌ Do not confuse with Devnet |

## Verification checklist (for reviewers)

When checking transactions on Solscan:

1. For real payouts: network = **Mainnet**, treasury = `5QLPVVcCUwRoUqF3dC9sgM2mPQbS27Y3FcZj4MVdN88N`
2. For devnet tests: switch network to **Devnet**, treasury = `GVCcANPUcjPcw3vQ2EEKJZMknec3ERUebTZVsrFxqqts`
3. Token to look for: **USD Coin** — mainnet mint `EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v`
4. If you see an unrecognized token → it's the old test mint, flag it

## Current state (2026-11-25)

- [x] Payments service live on **Solana mainnet** with real USDC mint
- [x] Mainnet treasury configured: Lynn's wallet `5QLPVV...` — **50.70 USDC + ~0.017 SOL**
- [x] ~$132.45 USDC owed to 25 contributors — all verified wallets, staged in `usdc_reward_ledger`
- [ ] **~$82 USDC still needed** in treasury to cover full payout run
- [ ] $1 mainnet test payout — ready to run once desired
- [ ] Real payout batch — pending treasury top-up + two-person approval

## Never do

- ❌ Send funds to a mint address (`EPjF...`, `4zMMC...`, `A6jad...`) — those are token contracts, not wallets
- ❌ Share treasury private key / `SOLANA_TREASURY_SECRET_KEY` / any Secret Manager value
- ❌ Point `SOLANA_USDC_MINT` at the devnet mint while `SOLANA_CLUSTER=mainnet-beta` (or vice versa)
- ❌ Claim payouts happened on-chain before a batch signature exists
- ❌ Send the exposed Lynn wallet key material anywhere — it was shared in chat; rotate/move funds to a fresh wallet after payouts complete
