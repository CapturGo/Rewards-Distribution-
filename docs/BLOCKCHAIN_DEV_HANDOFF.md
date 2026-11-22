# CapturGO — Blockchain Developer Handoff

**Date:** 2026-11-22
**Audience:** external blockchain/payment engineer
**Status:** pipeline built & verified through reward sync; first Devnet
payout pending execution.

---

## 1. Where things stand

| Component | Status |
|---|---|
| Reward calculation (duration × device tier) | ✅ Live — `RewardCalculator`, $5/$4.50/$3.75 per hour |
| Reward sync (approved submissions → ledger) | ✅ Verified — **193 rewards, ~132.45 USDC, all wallets resolved** |
| Payout batches + maker-checker approval | ✅ Built, deployed — **0 batches created yet** |
| Solana Devnet USDC execution | ✅ Code deployed (`PAYOUT_PROVIDER=keypair`) — **never executed on-chain** |
| On-chain verification + reconciliation | ✅ Built (`PayoutReconciler`) — awaiting first real tx |
| Mainnet | ⛔ Intentionally blocked — Devnet only until custody decided |

**Treasury (Devnet, verified on-chain 2026-11-22):**

- Address: `GVCcANPUcjPcw3vQ2EEKJZMknec3ERUebTZVsrFxqqts`
- USDC balance: **1,000.0** (mint `A6jadAHqc2y8LzwFYFoBmKEMw88tUiVV4CxfzvF1b95H`)
- SOL balance: ~2.5 (fees)
- Explorer: `https://explorer.solana.com/?cluster=devnet`

**Why there's nothing on-chain yet:** no payout batch has been created or
executed. The 193 rewards sit `AVAILABLE` in `usdc_reward_ledger` — staged,
not paid. Once a batch executes, signatures appear in `payout_transactions`
and the batch-detail API.

## 2. About the Settlements page (common confusion)

The dashboard **Settlements/Participants** page reads `fiat_transaction` —
the **legacy fiat/points ledger**, a separate system from USDC:

- Activity rewards: all reversed by an intentional bulk migration
  (`BULK_STATUS_RESET_TO_APPROVED`, 2026-10-15) + a few admin rejections.
- Raffle winnings: genuinely settled via manual on-chain transfers
  (real `txHash` in metadata, Aug–Sep).

The USDC rail (`usdc_reward_ledger` → `payout_batches` → `payout_transactions`)
is independent — nothing paid yet, by design.

## 3. Systems & URLs

| Service | URL | Role |
|---|---|---|
| captur-payments | `https://captur-payments-208129623932.asia-east2.run.app` | Reward ledger + payout execution — **your integration target** |
| captur-dashboard | `https://captur-dashboard-208129623932.asia-east2.run.app` | Admin console (Payouts page runs the flow) |
| captur-go-api | `https://captur-go-api-208129623932.asia-east2.run.app` | Go backend — submissions/users read upstream |
| captur-api (legacy) | `https://captur-api-208129623932.asia-east2.run.app` | Remaining legacy routes |

API contract: `docs/BLOCKCHAIN_PAYOUT_API.md` (in this repo and at repo root
of `captur-monorepo`). `GET /` on the payments service returns a descriptor.

Auth: `Authorization: Bearer <Privy JWT>`; admin endpoints need ADMIN role
resolved server-side.

## 4. How you can help

### 4.1 Review the payout execution code
`apps/payments/src/main/kotlin/com/capturgo/payments/solana/`:

- `UsdcPayoutExecutor.kt` — raw keypair SPL transfers
- `PrivyPayoutExecutor.kt` + `PrivyWalletClient.kt` — Privy Wallet Actions provider
- `../payouts/PayoutExecutionService.kt` — batch item orchestration
- `../payouts/PayoutReconciler.kt` — on-chain confirmation loop

Focus areas: transaction construction, recent-blockhash expiry handling,
partial batch failures, double-sign/retry risks, ATA (token account)
creation for first-time recipients.

### 4.2 Mainnet readiness plan
Spec the go-live checklist:

- Mainnet RPC provider (Helius/QuickNode/ Triton etc.)
- Treasury custody model: hot keypair vs `PAYOUT_PROVIDER=privy`
  (Privy Wallet Actions) vs Squads multisig
- Mainnet USDC mint, rate limits, fee budgeting
- The `PAYOUT_PROVIDER` decision — code supports both already

### 4.3 Independent on-chain verification
After the first Devnet batch executes:

- Cross-check `payout_transactions.tx_signature` values against
  `explorer.solana.com/?cluster=devnet`
- Confirm amounts, recipients, finality status
- Flag any discrepancy between ledger state and chain state

### 4.4 Wallet edge cases
- Contributor with no Solana wallet at payout time
- Recipient without a USDC associated token account (ATA)
- Privy embedded wallet creation/UX on mobile

### 4.5 Failure-mode testing (Devnet)
- Insufficient treasury balance
- Expired blockhash mid-batch
- RPC downtime during execution
- Expected: items land `FAILED`/`REQUIRES_RECONCILIATION`, safe retry via
  new batch — never blind re-broadcast (double-pay protection)

## 5. What NOT to do

- ❌ Don't build a payout client — the API already exists end-to-end
- ❌ Don't ask for or accept treasury private keys — custody is a
  deliberate security decision (Secret Manager / Privy / multisig)
- ❌ Don't push to mainnet — `SOLANA_CLUSTER=devnet` is enforced by config
  and convention; mainnet requires explicit go-live approval
- ✅ Propose changes via PR on `captur-monorepo` (`apps/payments`)

## 6. Key files

```
apps/payments/src/main/kotlin/com/capturgo/payments/
├── api/admin/AdminPaymentsController.kt   # REST endpoints
├── api/contributor/ContributorPaymentsController.kt
├── payouts/PayoutService.kt               # batch lifecycle
├── payouts/PayoutExecutionService.kt      # execute orchestration
├── payouts/PayoutReconciler.kt            # on-chain reconciliation
├── solana/UsdcPayoutExecutor.kt           # keypair provider
├── solana/PrivyPayoutExecutor.kt          # Privy provider
├── solana/PrivyWalletClient.kt            # Privy Wallet Actions REST
└── rewards/RewardSyncService.kt           # submission → ledger sync
```

DB tables: `usdc_reward_ledger`, `payout_batches`, `payout_batch_items`,
`payout_transactions`, `payout_audit_logs`, `contributor_wallets`,
`device_capabilities`.
