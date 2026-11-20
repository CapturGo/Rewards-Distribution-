package com.capturgo.payments.payouts

import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class PayoutRepository(private val jdbc: JdbcTemplate) {

  // ---------- batches ----------

  fun insertBatch(
    status: String,
    totalUnits: Long,
    itemCount: Int,
    cluster: String,
    idempotencyKey: String,
    createdBy: UUID,
  ): UUID? {
    return try {
      jdbc.update(
        """
        INSERT INTO payout_batches
          (status, total_usdc_units, item_count, cluster, idempotency_key, created_by)
        VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        status, totalUnits, itemCount, cluster, idempotencyKey, createdBy,
      )
      jdbc.queryForObject(
        "SELECT id FROM payout_batches WHERE idempotency_key = ?",
        UUID::class.java, idempotencyKey,
      )
    } catch (e: org.springframework.dao.DuplicateKeyException) {
      null
    }
  }

  fun findBatch(id: UUID): PayoutBatch? =
    jdbc.query(
      """
      SELECT id, status, total_usdc_units, item_count, cluster, idempotency_key,
             created_by, approved_by, approved_at, executed_at, created_at
      FROM payout_batches WHERE id = ?
      """.trimIndent(),
      { rs, _ -> mapBatch(rs) }, id,
    ).firstOrNull()

  fun findBatchByIdempotencyKey(key: String): PayoutBatch? =
    jdbc.query(
      """
      SELECT id, status, total_usdc_units, item_count, cluster, idempotency_key,
             created_by, approved_by, approved_at, executed_at, created_at
      FROM payout_batches WHERE idempotency_key = ?
      """.trimIndent(),
      { rs, _ -> mapBatch(rs) }, key,
    ).firstOrNull()

  fun listBatches(limit: Int, offset: Int, status: String?): List<PayoutBatch> {
    val sql = """
      SELECT id, status, total_usdc_units, item_count, cluster, idempotency_key,
             created_by, approved_by, approved_at, executed_at, created_at
      FROM payout_batches
      ${if (status != null) "WHERE status = ?" else ""}
      ORDER BY created_at DESC LIMIT ? OFFSET ?
    """.trimIndent()
    return if (status != null) {
      jdbc.query(sql, { rs, _ -> mapBatch(rs) }, status, limit, offset)
    } else {
      jdbc.query(sql, { rs, _ -> mapBatch(rs) }, limit, offset)
    }
  }

  fun countBatches(status: String?): Long {
    val sql = "SELECT COUNT(*) FROM payout_batches ${if (status != null) "WHERE status = ?" else ""}"
    return if (status != null) {
      jdbc.queryForObject(sql, Long::class.java, status) ?: 0
    } else {
      jdbc.queryForObject(sql, Long::class.java) ?: 0
    }
  }

  /**
   * Atomically claim an APPROVED batch for execution. Exactly one concurrent
   * caller can transition APPROVED → PROCESSING; everyone else gets false.
   */
  fun claimBatchForExecution(id: UUID): Boolean =
    jdbc.update(
      """
      UPDATE payout_batches
      SET status = 'PROCESSING', updated_at = now()
      WHERE id = ? AND status = 'APPROVED'
      """.trimIndent(),
      id,
    ) == 1

  fun updateBatchStatus(id: UUID, status: String, approvedBy: UUID? = null, executed: Boolean = false) {
    jdbc.update(
      """
      UPDATE payout_batches SET
        status = ?,
        approved_by = COALESCE(?, approved_by),
        approved_at = CASE WHEN ? IS NOT NULL THEN now() ELSE approved_at END,
        executed_at = CASE WHEN ? THEN now() ELSE executed_at END,
        updated_at = now()
      WHERE id = ?
      """.trimIndent(),
      status, approvedBy, approvedBy, executed, id,
    )
  }

  /**
   * Atomically approve a batch. Only one caller can transition a pre-execution
   * batch to APPROVED — prevents approve-overwriting PROCESSING mid-execution.
   */
  fun approveBatchAtomically(id: UUID, approverId: UUID): Boolean =
    jdbc.update(
      """
      UPDATE payout_batches SET
        status = 'APPROVED',
        approved_by = ?,
        approved_at = now(),
        updated_at = now()
      WHERE id = ? AND status IN ('PENDING_APPROVAL','CREATED')
      """.trimIndent(),
      approverId, id,
    ) == 1

  /**
   * Atomically cancel a batch. Only wins from pre-execution states — can never
   * overwrite PROCESSING or later, so in-flight payouts can't be orphaned.
   */
  fun claimBatchForCancellation(id: UUID): Boolean =
    jdbc.update(
      """
      UPDATE payout_batches SET status = 'CANCELLED', updated_at = now()
      WHERE id = ? AND status IN ('PENDING_APPROVAL','CREATED','APPROVED')
      """.trimIndent(),
      id,
    ) == 1

  // ---------- items ----------

  fun insertItem(
    batchId: UUID,
    rewardId: UUID,
    userId: UUID,
    solanaAddress: String,
    amountUnits: Long,
  ): UUID? = try {
    jdbc.update(
      """
      INSERT INTO payout_batch_items
        (batch_id, reward_id, captur_user_id, solana_address, amount_usdc_units)
      VALUES (?, ?, ?, ?, ?)
      """.trimIndent(),
      batchId, rewardId, userId, solanaAddress, amountUnits,
    )
    jdbc.queryForObject(
      "SELECT id FROM payout_batch_items WHERE reward_id = ?",
      UUID::class.java, rewardId,
    )
  } catch (e: org.springframework.dao.DuplicateKeyException) {
    null
  }

  fun listItems(batchId: UUID): List<PayoutBatchItem> =
    jdbc.query(
      """
      SELECT id, batch_id, reward_id, captur_user_id, solana_address,
             amount_usdc_units, status, tx_signature, failure_reason, created_at
      FROM payout_batch_items WHERE batch_id = ? ORDER BY created_at
      """.trimIndent(),
      { rs, _ -> mapItem(rs) }, batchId,
    )

  fun updateItemStatus(itemId: UUID, status: String, txSignature: String? = null, failureReason: String? = null) {
    jdbc.update(
      """
      UPDATE payout_batch_items SET
        status = ?,
        tx_signature = COALESCE(?, tx_signature),
        failure_reason = COALESCE(?, failure_reason),
        updated_at = now()
      WHERE id = ?
      """.trimIndent(),
      status, txSignature, failureReason, itemId,
    )
  }

  // ---------- reconciliation ----------

  data class ReconcilableItem(
    val item: PayoutBatchItem,
    val batchStatus: String,
  )

  /** Items awaiting on-chain confirmation that have a signature to check. */
  fun listReconcilableItems(limit: Int): List<ReconcilableItem> =
    jdbc.query(
      """
      SELECT i.id, i.batch_id, i.reward_id, i.captur_user_id, i.solana_address,
             i.amount_usdc_units, i.status, i.tx_signature, i.failure_reason, i.created_at,
             b.status AS batch_status
      FROM payout_batch_items i
      JOIN payout_batches b ON b.id = i.batch_id
      WHERE i.status IN ('SUBMITTED','REQUIRES_RECONCILIATION')
        AND i.tx_signature IS NOT NULL
      ORDER BY i.updated_at ASC
      LIMIT ?
      """.trimIndent(),
      { rs, _ ->
        ReconcilableItem(
          item = PayoutBatchItem(
            id = rs.getObject("id", UUID::class.java),
            batchId = rs.getObject("batch_id", UUID::class.java),
            rewardId = rs.getObject("reward_id", UUID::class.java),
            capturUserId = rs.getObject("captur_user_id", UUID::class.java),
            solanaAddress = rs.getString("solana_address"),
            amountUsdcUnits = rs.getLong("amount_usdc_units"),
            status = rs.getString("status"),
            txSignature = rs.getString("tx_signature"),
            failureReason = rs.getString("failure_reason"),
            createdAt = rs.getTimestamp("created_at")?.toInstant(),
          ),
          batchStatus = rs.getString("batch_status"),
        )
      },
      limit,
    )

  /**
   * Atomically confirm an item. Only one reconciler (or executor) can win the
   * SUBMITTED/REQUIRES_RECONCILIATION → CONFIRMED transition — this is the
   * duplicate-payment guard: the reward is marked PAID only by the winner.
   */
  fun confirmItemIfUnresolved(itemId: UUID): Boolean =
    jdbc.update(
      """
      UPDATE payout_batch_items SET status = 'CONFIRMED', updated_at = now()
      WHERE id = ? AND status IN ('SUBMITTED','REQUIRES_RECONCILIATION')
      """.trimIndent(),
      itemId,
    ) == 1

  /** Record the on-chain outcome on the transaction row. */
  fun updatePayoutTransaction(
    txSignature: String,
    cluster: String,
    status: String,
    slot: Long? = null,
  ) {
    jdbc.update(
      """
      UPDATE payout_transactions SET
        status = ?,
        slot = COALESCE(?, slot),
        block_time = CASE WHEN ? IN ('CONFIRMED','FAILED','DROPPED') THEN now() ELSE block_time END
      WHERE tx_signature = ? AND cluster = ?
      """.trimIndent(),
      status, slot, status, txSignature, cluster,
    )
  }

  /**
   * Recompute a batch's status from its items after reconciliation.
   * Only transitions batches still in flight — never resurrects CANCELLED.
   * CONFIRMED only when EVERY item confirmed; any unresolved or failed item
   * keeps the batch in REQUIRES_RECONCILIATION for manual review.
   */
  fun recomputeBatchStatus(batchId: UUID) {
    val counts = jdbc.queryForMap(
      """
      SELECT
        COUNT(*) FILTER (WHERE status = 'CONFIRMED') AS confirmed,
        COUNT(*) FILTER (WHERE status IN ('FAILED')) AS failed,
        COUNT(*) FILTER (WHERE status NOT IN ('CONFIRMED','FAILED')) AS unresolved,
        COUNT(*) AS total
      FROM payout_batch_items WHERE batch_id = ?
      """.trimIndent(),
      batchId,
    )
    val total = (counts["total"] as Number).toLong()
    if (total == 0L) return
    val confirmed = (counts["confirmed"] as Number).toLong()
    val unresolved = (counts["unresolved"] as Number).toLong()
    val newStatus = when {
      unresolved > 0 -> "REQUIRES_RECONCILIATION"
      confirmed == total -> "CONFIRMED"
      else -> "REQUIRES_RECONCILIATION" // mixed confirmed+failed — manual review
    }
    jdbc.update(
      """
      UPDATE payout_batches SET status = ?, updated_at = now()
      WHERE id = ? AND status IN ('PROCESSING','SUBMITTED','REQUIRES_RECONCILIATION')
      """.trimIndent(),
      newStatus, batchId,
    )
  }

  // ---------- reward reservation (called inside batch-create transaction) ----------

  /** Atomically reserve rewards for a batch. Returns count actually reserved. */
  @Transactional
  fun reserveRewards(rewardIds: List<UUID>): Int {
    if (rewardIds.isEmpty()) return 0
    val placeholders = rewardIds.joinToString(",") { "?" }
    return jdbc.update(
      """
      UPDATE usdc_reward_ledger
      SET status = 'RESERVED_FOR_PAYOUT', updated_at = now()
      WHERE id IN ($placeholders) AND status = 'AVAILABLE'
      """.trimIndent(),
      *rewardIds.toTypedArray(),
    )
  }

  fun releaseRewards(rewardIds: List<UUID>) {
    if (rewardIds.isEmpty()) return
    val placeholders = rewardIds.joinToString(",") { "?" }
    jdbc.update(
      """
      UPDATE usdc_reward_ledger
      SET status = 'AVAILABLE', updated_at = now()
      WHERE id IN ($placeholders) AND status = 'RESERVED_FOR_PAYOUT'
      """.trimIndent(),
      *rewardIds.toTypedArray(),
    )
  }

  /**
   * Mark a reward PAID — only from RESERVED_FOR_PAYOUT. Guards against
   * overwriting PAID/REVERSED/AVAILABLE states (e.g. a late-confirming
   * transaction after manual review).
   */
  fun markRewardPaid(rewardId: UUID): Boolean =
    jdbc.update(
      "UPDATE usdc_reward_ledger SET status = 'PAID', updated_at = now() WHERE id = ? AND status = 'RESERVED_FOR_PAYOUT'",
      rewardId,
    ) == 1

  fun insertPayoutTransaction(
    batchItemId: UUID,
    txSignature: String,
    cluster: String,
    status: String,
    rawResponse: String?,
  ): UUID? = try {
    jdbc.update(
      """
      INSERT INTO payout_transactions (batch_item_id, tx_signature, cluster, status, raw_response)
      VALUES (?, ?, ?, ?, ?::jsonb)
      """.trimIndent(),
      batchItemId, txSignature, cluster, status, rawResponse,
    )
    jdbc.queryForObject(
      "SELECT id FROM payout_transactions WHERE tx_signature = ? AND cluster = ?",
      UUID::class.java, txSignature, cluster,
    )
  } catch (e: org.springframework.dao.DuplicateKeyException) {
    null
  }

  // ---------- audit ----------

  fun audit(actorId: UUID?, actorEmail: String?, action: String, entityType: String, entityId: UUID?, before: String?, after: String?, ip: String?, ua: String?) {
    jdbc.update(
      """
      INSERT INTO payout_audit_logs
        (actor_user_id, actor_email, action, entity_type, entity_id, before_state, after_state, ip_address, user_agent)
      VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
      """.trimIndent(),
      actorId, actorEmail, action, entityType, entityId, before, after, ip, ua,
    )
  }

  // ---------- history ----------

  fun paymentHistory(limit: Int, offset: Int, status: String?, userId: UUID?): List<PaymentHistoryRow> =
    jdbc.query(
      """
      SELECT i.id, i.captur_user_id, i.solana_address, i.amount_usdc_units, i.status,
             i.tx_signature, i.created_at,
             b.id AS batch_id, b.status AS batch_status
      FROM payout_batch_items i
      JOIN payout_batches b ON b.id = i.batch_id
      WHERE (?::text IS NULL OR i.status = ?)
        AND (?::uuid IS NULL OR i.captur_user_id = ?)
      ORDER BY i.created_at DESC
      LIMIT ? OFFSET ?
      """.trimIndent(),
      { rs, _ ->
        PaymentHistoryRow(
          id = rs.getObject("id", UUID::class.java),
          userId = rs.getObject("captur_user_id", UUID::class.java),
          username = null,
          email = null,
          amountUsdc = usdc(rs.getLong("amount_usdc_units")),
          solanaAddress = rs.getString("solana_address"),
          batchId = rs.getObject("batch_id", UUID::class.java),
          batchStatus = rs.getString("batch_status"),
          txSignature = rs.getString("tx_signature"),
          status = rs.getString("status"),
          createdAt = rs.getTimestamp("created_at")?.toInstant(),
          paidAt = null,
        )
      },
      status, status, userId, userId, limit, offset,
    )

  fun countPaymentHistory(status: String?, userId: UUID?): Long =
    jdbc.queryForObject(
      """
      SELECT COUNT(*) FROM payout_batch_items i
      WHERE (?::text IS NULL OR i.status = ?)
        AND (?::uuid IS NULL OR i.captur_user_id = ?)
      """.trimIndent(),
      Long::class.java, status, status, userId, userId,
    ) ?: 0

  // ---------- internals ----------

  private fun mapBatch(rs: java.sql.ResultSet) = PayoutBatch(
    id = rs.getObject("id", UUID::class.java),
    status = rs.getString("status"),
    totalUsdcUnits = rs.getLong("total_usdc_units"),
    itemCount = rs.getInt("item_count"),
    cluster = rs.getString("cluster"),
    idempotencyKey = rs.getString("idempotency_key"),
    createdBy = rs.getObject("created_by", UUID::class.java),
    approvedBy = rs.getObject("approved_by", UUID::class.java),
    approvedAt = rs.getTimestamp("approved_at")?.toInstant(),
    executedAt = rs.getTimestamp("executed_at")?.toInstant(),
    createdAt = rs.getTimestamp("created_at")?.toInstant(),
  )

  private fun mapItem(rs: java.sql.ResultSet) = PayoutBatchItem(
    id = rs.getObject("id", UUID::class.java),
    batchId = rs.getObject("batch_id", UUID::class.java),
    rewardId = rs.getObject("reward_id", UUID::class.java),
    capturUserId = rs.getObject("captur_user_id", UUID::class.java),
    solanaAddress = rs.getString("solana_address"),
    amountUsdcUnits = rs.getLong("amount_usdc_units"),
    status = rs.getString("status"),
    txSignature = rs.getString("tx_signature"),
    failureReason = rs.getString("failure_reason"),
    createdAt = rs.getTimestamp("created_at")?.toInstant(),
  )
}
