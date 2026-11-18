# Solana Devnet Setup — captur-payments

Everything below is **devnet only**. Mainnet execution is not implemented and
`SOLANA_CLUSTER` must remain `devnet`.

## 1. Devnet USDC mint

There is no official Circle USDC mint on devnet — create your own test mint:

```bash
# with Solana CLI (devnet)
solana config set --url devnet
solana-keygen new --outfile mint.json          # mint authority keypair
solana address -k mint.json                    # -> MINT_ADDRESS

spl-token create-token --decimals 6 --mint-authority mint.json \
  --fee-payer mint.json --program-ids TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA
# -> TOKEN_MINT_ADDRESS (use this as SOLANA_USDC_MINT)
```

Mint test USDC to the treasury wallet (see step 2) and to contributor test
wallets with `spl-token mint`.

## 2. Treasury wallet + keypair

```bash
solana-keygen new --outfile treasury.json
solana address -k treasury.json                # -> SOLANA_TREASURY_ADDRESS
```

Export the **base58 secret key** (the JSON array → base58 via
`solana-keygen recover` or a small script) as `SOLANA_TREASURY_SECRET_KEY`.
Store it in **Google Secret Manager** — never in the repo or dashboard.

Fund the treasury with devnet SOL for fees:

```bash
solana airdrop 2 <TREASURY_ADDRESS> --url devnet
```

Also create/fund the treasury's USDC ATA:

```bash
spl-token create-account <TOKEN_MINT_ADDRESS> \
  --owner <TREASURY_ADDRESS> --fee-payer treasury.json
spl-token mint <TOKEN_MINT_ADDRESS> <AMOUNT> \
  --owner mint.json --fee-payer mint.json \
  --recipient <TREASURY_USDC_ATA>
```

## 3. Service configuration

| Env var | Value |
|---|---|
| `SOLANA_ENABLED` | `true` (only after all checks pass) |
| `SOLANA_CLUSTER` | `devnet` — hard requirement, anything else disables execution |
| `SOLANA_RPC_URL` | `https://api.devnet.solana.com` (or a dedicated devnet RPC) |
| `SOLANA_USDC_MINT` | mint address from step 1 |
| `SOLANA_USDC_DECIMALS` | `6` |
| `SOLANA_TREASURY_ADDRESS` | treasury wallet pubkey |
| `SOLANA_TREASURY_SECRET_KEY` | base58 secret — Secret Manager only |
| `SOLANA_CONFIRM_TIMEOUT_MS` | `90000` |

## 4. Verification checklist before enabling

- [ ] `GET /api/v1/admin/treasury/balance` returns the treasury address
- [ ] Treasury has ≥0.05 devnet SOL (fees) and test USDC
- [ ] A test batch with ONE small reward (e.g. 0.01 USDC) executes
- [ ] Signature appears on `solscan.io` devnet and `payout_transactions` row is CONFIRMED
- [ ] Contributor's USDC ATA shows the transfer
- [ ] Re-running execute on the same batch does not double-pay (items are no longer PENDING)

## Safety properties (already implemented)

- `transferChecked` with mint + decimals — wrong mint/decimals fails on-chain
- Recipient ATA created idempotently in the same transaction
- Signature persisted **before** confirmation polling (idempotency anchor)
- Ambiguous RPC send → item `REQUIRES_RECONCILIATION`, never blind-resend
- Reward marked `PAID` only after `confirmed`/`finalized` status
- `SOLANA_CLUSTER != devnet` → execution refused at two layers
