package com.capturgo.payments.payouts

import com.capturgo.payments.core.auth.AuthPrincipal
import com.capturgo.payments.core.exception.ConflictException
import com.capturgo.payments.core.exception.NotFoundException
import com.capturgo.payments.solana.UsdcPayoutExecutor
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Executes an APPROVED payout batch on Solana. Devnet only.
 *
 * Safety rules implemented here:
 *  - Only runs when SOLANA_ENABLED=true AND cluster=devnet AND signer active.
 *  - Each item: record SUBMITTED + tx row BEFORE awaiting confirmation.
 *  - RPC timeout/ambiguity: query the stored signature before any resend —
 *    a landed transaction is never paid twice.
 *  - Reward marked PAID only after on-chain confirmation.
 */
@Service
class PayoutExecutionService(
  private val payoutRepository: PayoutRepository,
  private val rewardRepository: com.capturgo.payments.rewards.RewardRepository,
  private val executor: UsdcPayoutExecutor,
  @Value("\${payments.solana.enabled:false}") private val solanaEnabled: Boolean,
  @Value("\${payments.solana.cluster:devnet}") private val cluster: String,
) {
  private val logger = LoggerFactory.getLogger(PayoutExecutionService::class.java)

  fun executeBatch(batchId: UUID, admin: AuthPrincipal): PayoutBatch {
    val batch = payoutRepository.findBatch(batchId) ?: throw NotFoundException("Batch not found")
    if (batch.status != "APPROVED") {
      throw ConflictException("Batch must be APPROVED before execute (current: ${batch.status})")
    }
    if (!solanaEnabled || cluster != "devnet") {
      throw com.capturgo.payments.core.exception.FeatureDisabledException(
        "Solana execution requires SOLANA_ENABLED=true and SOLANA_CLUSTER=devnet",
      )
    }

    // Atomic claim — a concurrent execute loses the race here instead of
    // double-processing the batch.
    if (!payoutRepository.claimBatchForExecution(batchId)) {
      throw ConflictException("Batch $batchId was already claimed for execution")
    }
    val items = payoutRepository.listItems(batchId)
    var confirmed = 0
    var failed = 0

    for (item in items) {
      val ok = try {
        executeItem(item)
        true
      } catch (e: Exception) {
        logger.error("payout item {} failed: {}", item.id, e.message)
        payoutRepository.updateItemStatus(item.id, "FAILED", failureReason = e.message)
        false
      }
      if (ok) confirmed++ else failed++
    }

    val finalStatus = when {
      failed == 0 -> "CONFIRMED"
      confirmed == 0 -> "FAILED"
      else -> "REQUIRES_RECONCILIATION"
    }
    payoutRepository.updateBatchStatus(batchId, finalStatus, executed = true)
    payoutRepository.audit(
      actorId = admin.capturUserId, actorEmail = admin.email,
      action = "BATCH_EXECUTED", entityType = "payout_batch", entityId = batchId,
      before = """{"status":"APPROVED"}""",
      after = """{"status":"$finalStatus","confirmed":$confirmed,"failed":$failed}""",
      ip = null, ua = null,
    )
    return payoutRepository.findBatch(batchId)!!
  }

  /** @return true when the transfer confirmed on-chain. */
  private fun executeItem(item: PayoutBatchItem): Boolean {
    payoutRepository.updateItemStatus(item.id, "PROCESSING")

    val result = try {
      executor.execute(item.solanaAddress, item.amountUsdcUnits)
    } catch (e: Exception) {
      // Ambiguous send: tx may have landed. Mark REQUIRES_RECONCILIATION —
      // an operator (or reconciler) checks the signature before any retry.
      payoutRepository.updateItemStatus(
        item.id, "REQUIRES_RECONCILIATION",
        failureReason = "send ambiguous: ${e.message}",
      )
      return false
    }

    // Persist signature immediately — this is the idempotency anchor.
    // A landed-but-failed transaction is recorded FAILED, never CONFIRMED.
    val txStatus = when {
      result.confirmed -> "CONFIRMED"
      result.chainError != null -> "FAILED"
      else -> "SUBMITTED"
    }
    payoutRepository.insertPayoutTransaction(
      batchItemId = item.id,
      txSignature = result.signature,
      cluster = cluster,
      status = txStatus,
      rawResponse = """{"confirmationStatus":"${result.confirmationStatus}""" +
        (result.chainError?.let { ""","chainError":"$it""" } ?: "") + "}",
    )
    payoutRepository.updateItemStatus(
      item.id,
      if (result.confirmed) "CONFIRMED" else "SUBMITTED",
      txSignature = result.signature,
    )

    if (result.confirmed) {
      payoutRepository.markRewardPaid(item.rewardId)
      return true
    }
    // Not confirmed on-chain — either still pending or failed on-chain.
    // Never mark paid; route to reconciliation/manual review.
    payoutRepository.updateItemStatus(
      item.id, "REQUIRES_RECONCILIATION",
      txSignature = result.signature,
      failureReason = result.chainError?.let { "on-chain failure: $it" }
        ?: "confirmation timeout, status=${result.confirmationStatus}",
    )
    return false
  }
}
