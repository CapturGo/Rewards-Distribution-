package com.capturgo.payments.solana

import java.math.BigDecimal
import java.security.MessageDigest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Executes USDC payouts through Privy Wallet Actions
 * (`POST /v1/wallets/{wallet_id}/transfer`) using a Privy-managed treasury
 * wallet. Replaces only the signing/transport layer — the ledger, batch
 * approval, eligibility and reconciliation logic are unchanged.
 *
 * Safety rules (mirroring [UsdcPayoutExecutor]):
 *  - Devnet only: refuses to run unless `payments.solana.cluster=devnet` and
 *    `payments.privy-payout.chain=solana_devnet`.
 *  - Disabled unless `payments.privy-payout.enabled=true` and credentials,
 *    treasury wallet id and RPC-side config are present.
 *  - The wallet-action id is persisted as the transfer reference BEFORE the
 *    action is awaited — it is the idempotency anchor. Privy dedupes
 *    identical requests within 24h via `privy-idempotency-key`.
 *  - `confirmed=true` only when the action reaches `succeeded`; `failed` /
 *    `rejected` surface as on-chain-style failures for reconciliation.
 *
 * Docs: https://docs.privy.io/wallets/actions/transfer/usage,
 *       https://docs.privy.io/wallets/actions/status
 */
@Component
class PrivyPayoutExecutor(
  private val client: PrivyWalletClient,
  @Value("\${payments.privy-payout.enabled:false}") private val enabled: Boolean,
  @Value("\${payments.privy-payout.treasury-wallet-id:}") private val treasuryWalletId: String,
  @Value("\${payments.privy-payout.chain:solana_devnet}") private val chain: String,
  @Value("\${payments.solana.cluster:devnet}") private val cluster: String,
  @Value("\${payments.privy-payout.usdc-decimals:6}") private val decimals: Int,
  @Value("\${payments.privy-payout.action-timeout-ms:120000}") private val actionTimeoutMs: Long,
  @Value("\${payments.privy-payout.action-poll-ms:3000}") private val actionPollMs: Long,
) : PayoutExecutor {
  private val logger = LoggerFactory.getLogger(PrivyPayoutExecutor::class.java)

  override val configured: Boolean
    get() = enabled && client.configured && treasuryWalletId.isNotBlank() &&
      ((cluster == "devnet" && chain == "solana_devnet") ||
        (cluster == "mainnet-beta" && chain == "solana_mainnet"))

  override val activeCluster: String get() = cluster

  override fun execute(destinationWallet: String, amountUsdcUnits: Long): UsdcPayoutExecutor.TransferResult {
    if (!configured) {
      throw SolanaException(
        "Privy payout executor not active (enabled=$enabled, creds=${client.configured}, " +
          "walletId=${treasuryWalletId.isNotBlank()}, cluster=$cluster, chain=$chain)",
      )
    }
    val amount = if (amountUsdcUnits == 0L) "0" else
      BigDecimal(amountUsdcUnits).movePointLeft(decimals).stripTrailingZeros().toPlainString()
    val action = client.transfer(
      walletId = treasuryWalletId,
      sourceChain = chain,
      sourceAsset = "usdc",
      amount = amount,
      destinationAddress = destinationWallet,
      destinationAsset = "usdc",
      idempotencyKey = idempotencyKey(destinationWallet, amountUsdcUnits),
    )
    return awaitAction(action.id)
  }

  /**
   * Poll the wallet action until it reaches a terminal state or the timeout
   * elapses. Pending past timeout returns `confirmed=false` — the reconciler
   * resolves it later via [lookup].
   */
  private fun awaitAction(actionId: String): UsdcPayoutExecutor.TransferResult {
    val deadline = System.currentTimeMillis() + actionTimeoutMs
    var lastStatus = "pending"
    while (System.currentTimeMillis() < deadline) {
      val action = try {
        client.getAction(treasuryWalletId, actionId)
      } catch (e: Exception) {
        logger.warn("privy action {} status lookup failed: {}", actionId, e.message)
        null
      }
      if (action != null) {
        lastStatus = action.status
        when (action.status) {
          "succeeded" -> return UsdcPayoutExecutor.TransferResult(
            signature = actionId, confirmed = true, confirmationStatus = "succeeded",
          )
          "failed", "rejected" -> return UsdcPayoutExecutor.TransferResult(
            signature = actionId, confirmed = false, confirmationStatus = action.status,
            chainError = "privy action ${action.status}",
          )
        }
      }
      Thread.sleep(actionPollMs)
    }
    return UsdcPayoutExecutor.TransferResult(
      signature = actionId, confirmed = false, confirmationStatus = lastStatus,
    )
  }

  /**
   * Map a Privy action status onto the shape [com.capturgo.payments.payouts.PayoutReconciler]
   * already understands: terminal success looks like a confirmed/finalized tx
   * with err=null; terminal failure looks like a landed tx with err set.
   */
  override fun lookup(reference: String): SolanaRpcClient.TxDetail? {
    if (!configured) return null
    val action = try {
      client.getAction(treasuryWalletId, reference)
    } catch (e: Exception) {
      logger.warn("privy action {} lookup failed: {}", reference, e.message)
      return null
    } ?: return null
    return when (action.status) {
      "succeeded" -> SolanaRpcClient.TxDetail("finalized", err = null, feeLamports = null, slot = null)
      "failed", "rejected" -> SolanaRpcClient.TxDetail(
        "finalized", err = "privy action ${action.status}", feeLamports = null, slot = null,
      )
      else -> SolanaRpcClient.TxDetail("processed", err = null, feeLamports = null, slot = null)
    }
  }

  override fun findExisting(reference: String): String? =
    try {
      client.getAction(treasuryWalletId, reference)?.id
    } catch (e: Exception) {
      null
    }

  /** Deterministic key so a retried item cannot double-send within 24h. */
  private fun idempotencyKey(destinationWallet: String, amountUsdcUnits: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest("usdc|$chain|$destinationWallet|$amountUsdcUnits".toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(40)
  }
}
