package com.capturgo.payments.payouts

import com.capturgo.payments.core.auth.AuthPrincipal
import com.capturgo.payments.core.exception.ConflictException
import com.capturgo.payments.core.exception.FeatureDisabledException
import com.capturgo.payments.solana.PayoutExecutor
import com.capturgo.payments.solana.UsdcPayoutExecutor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PayoutExecutionServiceTest {

  private val payoutRepo = mockk<PayoutRepository>(relaxed = true)
  private val rewardRepo = mockk<com.capturgo.payments.rewards.RewardRepository>(relaxed = true)
  private val executor = mockk<PayoutExecutor>()

  private fun service(enabled: Boolean = true, cluster: String = "devnet") = PayoutExecutionService(
    payoutRepository = payoutRepo,
    rewardRepository = rewardRepo,
    executor = executor,
    solanaEnabled = enabled,
    cluster = cluster,
  )

  private fun admin() = AuthPrincipal("u", "a@t.com", UUID.randomUUID(), setOf("ADMIN"), "t")

  private fun batch(status: String = "APPROVED") = PayoutBatch(
    id = UUID.randomUUID(), status = status, totalUsdcUnits = 5_000_000L,
    itemCount = 1, cluster = "devnet", idempotencyKey = "k",
    createdBy = UUID.randomUUID(), approvedBy = UUID.randomUUID(),
    approvedAt = Instant.now(), executedAt = null, createdAt = Instant.now(),
  )

  private fun item() = PayoutBatchItem(
    id = UUID.randomUUID(), batchId = UUID.randomUUID(), rewardId = UUID.randomUUID(),
    capturUserId = UUID.randomUUID(), solanaAddress = "So11111111111111111111111111111111111111112",
    amountUsdcUnits = 5_000_000L, status = "PENDING", txSignature = null,
    failureReason = null, createdAt = Instant.now(),
  )

  @Test
  fun `disabled solana rejects execute`() {
    val b = batch()
    every { payoutRepo.findBatch(b.id) } returns b
    assertThrows(FeatureDisabledException::class.java) {
      service(enabled = false).executeBatch(b.id, admin())
    }
    verify(exactly = 0) { executor.execute(any(), any()) }
  }

  @Test
  fun `mainnet cluster is rejected even when enabled`() {
    val b = batch()
    every { payoutRepo.findBatch(b.id) } returns b
    assertThrows(FeatureDisabledException::class.java) {
      service(enabled = true, cluster = "mainnet").executeBatch(b.id, admin())
    }
    verify(exactly = 0) { executor.execute(any(), any()) }
  }

  @Test
  fun `non-approved batch rejected`() {
    val b = batch(status = "PENDING_APPROVAL")
    every { payoutRepo.findBatch(b.id) } returns b
    assertThrows(ConflictException::class.java) {
      service().executeBatch(b.id, admin())
    }
  }

  @Test
  fun `confirmed transfer marks reward paid`() {
    val b = batch()
    val it = item()
    every { payoutRepo.findBatch(b.id) } returnsMany listOf(b, b.copy(status = "CONFIRMED"))
    every { payoutRepo.claimBatchForExecution(b.id) } returns true
    every { payoutRepo.listItems(b.id) } returns listOf(it)
    every { executor.execute(it.solanaAddress, it.amountUsdcUnits) } returns
      UsdcPayoutExecutor.TransferResult("sig123", confirmed = true, confirmationStatus = "confirmed")

    val result = service().executeBatch(b.id, admin())
    assertEquals("CONFIRMED", result.status)
    verify { payoutRepo.markRewardPaid(it.rewardId) }
    verify { payoutRepo.insertPayoutTransaction(it.id, "sig123", "devnet", "CONFIRMED", any()) }
  }

  @Test
  fun `ambiguous send marks item for reconciliation - never auto retry`() {
    val b = batch()
    val it = item()
    every { payoutRepo.findBatch(b.id) } returnsMany listOf(b, b.copy(status = "REQUIRES_RECONCILIATION"))
    every { payoutRepo.claimBatchForExecution(b.id) } returns true
    every { payoutRepo.listItems(b.id) } returns listOf(it)
    every { executor.execute(any(), any()) } throws RuntimeException("rpc timeout")

    val result = service().executeBatch(b.id, admin())
    assertEquals("REQUIRES_RECONCILIATION", result.status)
    verify { payoutRepo.updateItemStatus(it.id, "REQUIRES_RECONCILIATION", failureReason = any()) }
    verify(exactly = 0) { payoutRepo.markRewardPaid(any()) }
    verify(exactly = 1) { executor.execute(any(), any()) } // no blind retry
  }

  @Test
  fun `concurrent claim loser is rejected - batch cannot execute twice`() {
    val b = batch()
    every { payoutRepo.findBatch(b.id) } returns b
    every { payoutRepo.claimBatchForExecution(b.id) } returns false // already claimed

    assertThrows(ConflictException::class.java) {
      service().executeBatch(b.id, admin())
    }
    verify(exactly = 0) { executor.execute(any(), any()) }
    verify(exactly = 0) { payoutRepo.updateBatchStatus(any(), any(), any(), any()) }
  }
}
