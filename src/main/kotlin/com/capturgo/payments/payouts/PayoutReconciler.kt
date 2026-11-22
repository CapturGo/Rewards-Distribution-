package com.capturgo.payments.payouts

import com.capturgo.payments.solana.PayoutExecutor
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Reconciles submitted payout transactions against the chain.
 *
 * Safety rules:
 *  - Disabled by default (payments.solana.reconcile-enabled=false).
 *  - Devnet only — refuses to run on any other cluster.
 *  - Read-only against Solana: this job NEVER sends a transaction.
 *  - An item is confirmed (and its reward marked PAID) only when the
 *    on-chain transaction has err == null. A transaction that landed but
 *    failed on-chain stays under manual review.
 *  - confirmItemIfUnresolved is an atomic DB transition — two concurrent
 *    reconcilers cannot both mark the same reward PAID.
 *  - Failed/uncertain items are NEVER released back to AVAILABLE.
 */
@Component
class PayoutReconciler(
  private val payoutRepository: PayoutRepository,
  private val executor: PayoutExecutor,
  @Value("\${payments.solana.reconcile-enabled:false}") private val enabled: Boolean,
  @Value("\${payments.solana.reconcile-batch-size:50}") private val batchSize: Int,
  @Value("\${payments.solana.cluster:devnet}") private val cluster: String,
) {
  private val logger = LoggerFactory.getLogger(PayoutReconciler::class.java)

  data class ReconcileReport(
    val checked: Int,
    val confirmed: Int,
    val failedOnChain: Int,
    val stillPending: Int,
    val rpcErrors: Int,
    val skippedNoSignature: Int,
  )

  @Scheduled(
    fixedDelayString = "\${payments.solana.reconcile-interval-ms:60000}",
    initialDelayString = "\${payments.solana.reconcile-initial-delay-ms:30000}",
  )
  fun run() {
    if (!enabled) return
    try {
      val report = reconcileOnce()
      if (report.checked > 0) logger.info("reconcile run: {}", report)
    } catch (e: Exception) {
      logger.error("reconcile run failed: {}", e.message)
    }
  }

  /** One reconciliation pass. Exposed for tests and manual triggering. */
  fun reconcileOnce(): ReconcileReport {
    if (cluster != "devnet") {
      logger.warn("reconcile skipped: cluster={} (devnet only)", cluster)
      return ReconcileReport(0, 0, 0, 0, 0, 0)
    }
    if (!executor.configured) {
      logger.warn("reconcile skipped: payout executor not configured")
      return ReconcileReport(0, 0, 0, 0, 0, 0)
    }

    val items = payoutRepository.listReconcilableItems(batchSize)
    var confirmed = 0
    var failedOnChain = 0
    var stillPending = 0
    var rpcErrors = 0
    var skippedNoSignature = 0
    val touchedBatches = mutableSetOf<UUID>()

    for (entry in items) {
      val item = entry.item
      val signature = item.txSignature
      if (signature.isNullOrBlank()) {
        skippedNoSignature++
        continue
      }
      touchedBatches += item.batchId
      val detail = try {
        executor.lookup(signature)
      } catch (e: Exception) {
        rpcErrors++
        logger.warn("reconcile lookup error for item {} sig {}: {}", item.id, signature, e.message)
        continue
      } ?: run {
        stillPending++ // unknown to cluster — may still land; leave for next run
        continue
      }

      when {
        detail.confirmationStatus == "confirmed" || detail.confirmationStatus == "finalized" -> {
          if (detail.err == null) {
            // Atomic claim — loser of a concurrent reconcile does nothing.
            if (payoutRepository.confirmItemIfUnresolved(item.id)) {
              payoutRepository.updatePayoutTransaction(
                signature, cluster, "CONFIRMED", slot = detail.slot,
              )
              payoutRepository.markRewardPaid(item.rewardId)
              payoutRepository.audit(
                actorId = null, actorEmail = "reconciler",
                action = "RECONCILE_CONFIRMED", entityType = "payout_batch_item",
                entityId = item.id,
                before = """{"status":"${item.status}"}""",
                after = """{"status":"CONFIRMED","txSignature":"$signature"}""",
                ip = null, ua = null,
              )
              confirmed++
            }
          } else {
            // Landed but failed on-chain — manual review, never auto-release.
            payoutRepository.updatePayoutTransaction(signature, cluster, "FAILED", slot = detail.slot)
            payoutRepository.updateItemStatus(
              item.id, "REQUIRES_RECONCILIATION",
              failureReason = "on-chain failure: ${detail.err}",
            )
            payoutRepository.audit(
              actorId = null, actorEmail = "reconciler",
              action = "RECONCILE_ONCHAIN_FAILED", entityType = "payout_batch_item",
              entityId = item.id,
              before = null,
              after = """{"txSignature":"$signature","err":"${detail.err}"}""",
              ip = null, ua = null,
            )
            failedOnChain++
          }
        }
        else -> stillPending++ // processed / unknown — check again next run
      }
    }

    touchedBatches.forEach { payoutRepository.recomputeBatchStatus(it) }

    return ReconcileReport(
      checked = items.size, confirmed = confirmed, failedOnChain = failedOnChain,
      stillPending = stillPending, rpcErrors = rpcErrors, skippedNoSignature = skippedNoSignature,
    )
  }
}
