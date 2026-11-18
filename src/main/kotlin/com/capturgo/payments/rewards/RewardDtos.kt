package com.capturgo.payments.rewards

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class RewardStatus { PENDING, AVAILABLE, RESERVED_FOR_PAYOUT, PAID, REVERSED }

data class RewardLedgerRow(
  val id: UUID,
  val submissionId: UUID,
  val capturUserId: UUID,
  val activityId: UUID?,
  val activityTitle: String?,
  val amountUsdcUnits: Long,
  val status: String,
  val metadata: Map<String, Any?>?,
  val createdAt: Instant?,
)

data class ReadyRewardDto(
  val rewardId: UUID,
  val submissionId: UUID,
  val userId: UUID,
  val username: String?,
  val email: String?,
  val solanaAddress: String?,
  val walletStatus: String,
  val amountUsdc: BigDecimal,
  val amountUsdcUnits: Long,
  val activityTitle: String?,
  val createdAt: Instant?,
)

data class RewardSyncResult(
  val scanned: Int,
  val created: Int,
  val skippedExisting: Int,
  val skippedIneligible: Int,
  val walletsResolved: Int,
  val walletsMissing: Int,
  val errors: List<String>,
  val finishedAt: Instant,
)

data class RewardBalanceDto(
  val availableUsdc: BigDecimal,
  val paidUsdc: BigDecimal,
  val reservedUsdc: BigDecimal,
)

fun usdcUnits(units: Long): BigDecimal =
  BigDecimal.valueOf(units).movePointLeft(6)

fun usdcToUnits(amount: BigDecimal): Long =
  amount.movePointRight(6).longValueExact()
