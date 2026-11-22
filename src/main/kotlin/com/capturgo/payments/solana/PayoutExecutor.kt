package com.capturgo.payments.solana

/**
 * Abstraction over payout signing/transport. Two implementations exist:
 *
 *  - [UsdcPayoutExecutor] — raw Solana keypair (legacy/custom path).
 *  - [PrivyPayoutExecutor] — Privy Wallet Actions API (Privy-managed treasury).
 *
 * [PayoutExecutionService] and [PayoutReconciler] depend only on this
 * interface; the reward ledger, batch approval and accounting logic are
 * provider-agnostic. Selected via `payments.payout.provider`.
 */
interface PayoutExecutor {
  /** True when the executor has the configuration it needs to send. */
  val configured: Boolean

  /** Cluster this executor operates on, e.g. "devnet". */
  val activeCluster: String

  /**
   * Execute one USDC transfer of [amountUsdcUnits] base units to
   * [destinationWallet]. Returns a [UsdcPayoutExecutor.TransferResult];
   * [UsdcPayoutExecutor.TransferResult.signature] is the provider's stable
   * reference (tx signature for keypair, wallet-action id for Privy) and is
   * the idempotency anchor persisted before confirmation.
   */
  fun execute(destinationWallet: String, amountUsdcUnits: Long): UsdcPayoutExecutor.TransferResult

  /**
   * Look up a previously submitted transfer by its stored reference.
   * Returns null when the reference is unknown to the provider.
   */
  fun lookup(reference: String): SolanaRpcClient.TxDetail?

  /** Best-effort existence check used for ambiguous-send recovery. */
  fun findExisting(reference: String): String?
}
