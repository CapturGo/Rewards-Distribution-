package com.capturgo.payments.payouts

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class BatchStatus {
  CREATED, PENDING_APPROVAL, APPROVED, PROCESSING, SUBMITTED,
  CONFIRMED, FAILED, CANCELLED, REQUIRES_RECONCILIATION,
}

enum class ItemStatus {
  PENDING, PROCESSING, SUBMITTED, CONFIRMED, FAILED, REQUIRES_RECONCILIATION,
}

data class PayoutBatch(
  val id: UUID,
  val status: String,
  val totalUsdcUnits: Long,
  val itemCount: Int,
  val cluster: String,
  val idempotencyKey: String,
  val createdBy: UUID,
  val approvedBy: UUID?,
  val approvedAt: Instant?,
  val executedAt: Instant?,
  val createdAt: Instant?,
)

data class PayoutBatchItem(
  val id: UUID,
  val batchId: UUID,
  val rewardId: UUID,
  val capturUserId: UUID,
  val solanaAddress: String,
  val amountUsdcUnits: Long,
  val status: String,
  val txSignature: String?,
  val failureReason: String?,
  val createdAt: Instant?,
)

data class CreateBatchRequest(
  val rewardIds: List<UUID>,
  val idempotencyKey: String? = null,
)

data class BatchDetailDto(
  val batch: PayoutBatch,
  val items: List<PayoutBatchItem>,
)

data class PaymentHistoryRow(
  val id: UUID,
  val userId: UUID,
  val username: String?,
  val email: String?,
  val amountUsdc: BigDecimal,
  val solanaAddress: String?,
  val batchId: UUID?,
  val batchStatus: String?,
  val txSignature: String?,
  val status: String,
  val createdAt: Instant?,
  val paidAt: Instant?,
)

data class TreasuryBalanceDto(
  val cluster: String,
  val treasuryAddress: String?,
  val usdcUnits: Long?,
  val solanaUnits: Long?,
  val enabled: Boolean,
  val fetchedAt: Instant,
  val note: String? = null,
)

fun usdc(units: Long): BigDecimal =
  BigDecimal.valueOf(units).movePointLeft(6)
