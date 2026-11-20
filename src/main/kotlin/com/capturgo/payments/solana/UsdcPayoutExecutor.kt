package com.capturgo.payments.solana

import java.math.BigDecimal
import org.p2p.solanaj.core.PublicKey
import org.p2p.solanaj.core.Transaction
import org.p2p.solanaj.programs.AssociatedTokenProgram
import org.p2p.solanaj.programs.TokenProgram
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Builds, signs and submits a single SPL USDC transferChecked from the
 * treasury ATA to the recipient's ATA. Devnet only — [execute] refuses to run
 * unless the signer reports active and cluster is devnet.
 */
@Component
class UsdcPayoutExecutor(
  private val rpc: SolanaRpcClient,
  private val signer: TreasurySigner,
  @Value("\${payments.solana.usdc-mint:}") private val usdcMint: String,
  @Value("\${payments.solana.cluster:devnet}") private val cluster: String,
  @Value("\${payments.solana.usdc-decimals:6}") private val decimals: Int,
  @Value("\${payments.solana.confirm-timeout-ms:90000}") private val confirmTimeoutMs: Long,
  @Value("\${payments.solana.confirm-poll-ms:2000}") private val confirmPollMs: Long,
) {
  data class TransferResult(
    val signature: String,
    val confirmed: Boolean,
    val confirmationStatus: String?,
    /** Non-null when the transaction landed but failed on-chain. */
    val chainError: String? = null,
  )

  /**
   * Execute one USDC transfer. Idempotency rule: callers must persist the
   * returned signature BEFORE confirming; on any post-send ambiguity the
   * caller must call [findExistingSignature] before retrying.
   */
  fun execute(destinationWallet: String, amountUsdcUnits: Long): TransferResult {
    if (cluster != "devnet") {
      throw SolanaException("Mainnet execution is not implemented — devnet only")
    }
    if (usdcMint.isBlank()) throw SolanaException("SOLANA_USDC_MINT not configured")
    if (!signer.active) throw SolanaException("Treasury signer not active")

    val treasury = signer.requireAccount()
    val mint = PublicKey(usdcMint)
    val recipient = PublicKey(destinationWallet)
    val treasuryAta = signer.usdcTokenAccount(mint)
    val recipientAta = signer.recipientTokenAccount(recipient, mint)

    val tx = Transaction()
      // Create recipient ATA if missing (idempotent — no-op when it exists)
      .addInstruction(
        AssociatedTokenProgram.createIdempotent(treasury.publicKey, recipient, mint),
      )
      .addInstruction(
        TokenProgram.transferChecked(
          treasuryAta,          // source token account
          recipientAta,         // destination token account
          amountUsdcUnits,      // amount in base units
          decimals.toByte(),    // mint decimals
          treasury.publicKey,   // owner/authority
          mint,                 // mint (decimals check)
        ),
      )
    tx.setRecentBlockHash(rpc.latestBlockhash())
    tx.sign(treasury)

    // If send throws after the node may have accepted it, we cannot know the
    // signature — caller treats this as AMBIGUOUS and reconciles before retry.
    val signature = rpc.sendTransaction(tx, treasury)

    val status = awaitConfirmation(signature)
    val landed = status == "confirmed" || status == "finalized"

    // A landed transaction can still FAIL on-chain (insufficient funds, bad
    // account, etc.) — confirmationStatus alone cannot distinguish success.
    // Fetch meta.err before reporting success. If the detail lookup itself
    // fails we conservatively report unconfirmed → reconciliation path.
    if (!landed) {
      return TransferResult(signature, confirmed = false, confirmationStatus = status)
    }
    val detail = runCatching { rpc.getTransactionDetail(signature) }.getOrNull()
      ?: return TransferResult(signature, confirmed = false, confirmationStatus = status)
    return if (detail.err == null) {
      TransferResult(signature, confirmed = true, confirmationStatus = status)
    } else {
      TransferResult(
        signature, confirmed = false, confirmationStatus = status,
        chainError = detail.err.toString(),
      )
    }
  }

  /**
   * Check whether a previously-sent signature landed on-chain. Used for
   * RPC-timeout recovery: NEVER resend without calling this first.
   */
  fun findExistingSignature(signature: String): String? =
    runCatching { rpc.getSignatureStatus(signature) }.getOrNull()

  private fun awaitConfirmation(signature: String): String? {
    val deadline = System.currentTimeMillis() + confirmTimeoutMs
    while (System.currentTimeMillis() < deadline) {
      val status = runCatching { rpc.getSignatureStatus(signature) }.getOrNull()
      if (status == "confirmed" || status == "finalized") return status
      if (status == "expired" || status == null) {
        // null = not found yet OR RPC error; keep polling until deadline
      }
      Thread.sleep(confirmPollMs)
    }
    return runCatching { rpc.getSignatureStatus(signature) }.getOrNull()
  }

  fun treasurySolBalance(): Long = rpc.getSolBalanceLamports(signer.address)

  companion object {
    fun usdcToUnits(amount: BigDecimal): Long = amount.movePointRight(6).longValueExact()
  }
}
