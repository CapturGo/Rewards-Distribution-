package com.capturgo.payments.payouts

import com.capturgo.payments.solana.SolanaRpcClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PayoutReconcilerTest {

  private val payoutRepo = mockk<PayoutRepository>(relaxed = true)
  private val rpc = mockk<SolanaRpcClient>()

  private fun reconciler(enabled: Boolean = true, cluster: String = "devnet") = PayoutReconciler(
    payoutRepository = payoutRepo,
    rpc = rpc,
    enabled = enabled,
    batchSize = 50,
    cluster = cluster,
  )

  private fun reconcilable(status: String = "REQUIRES_RECONCILIATION", sig: String? = "sigABC") =
    PayoutRepository.ReconcilableItem(
      item = PayoutBatchItem(
        id = UUID.randomUUID(), batchId = UUID.randomUUID(), rewardId = UUID.randomUUID(),
        capturUserId = UUID.randomUUID(), solanaAddress = "addr",
        amountUsdcUnits = 5_000_000L, status = status, txSignature = sig,
        failureReason = null, createdAt = Instant.now(),
      ),
      batchStatus = "REQUIRES_RECONCILIATION",
    )

  private fun detail(status: String?, err: Any? = null) = SolanaRpcClient.TxDetail(
    confirmationStatus = status, err = err, feeLamports = 5000L, slot = 12345L,
  )

  @Test
  fun `confirmed on-chain with no error marks item and reward paid`() {
    val entry = reconcilable()
    every { payoutRepo.listReconcilableItems(50) } returns listOf(entry)
    every { rpc.configured } returns true
    every { rpc.getTransactionDetail("sigABC") } returns detail("confirmed", err = null)
    every { payoutRepo.confirmItemIfUnresolved(entry.item.id) } returns true

    val report = reconciler().reconcileOnce()

    assertEquals(1, report.confirmed)
    verify { payoutRepo.updatePayoutTransaction("sigABC", "devnet", "CONFIRMED", slot = 12345L) }
    verify { payoutRepo.markRewardPaid(entry.item.rewardId) }
    verify { payoutRepo.recomputeBatchStatus(entry.item.batchId) }
  }

  @Test
  fun `finalized status also confirms`() {
    val entry = reconcilable(status = "SUBMITTED")
    every { payoutRepo.listReconcilableItems(50) } returns listOf(entry)
    every { rpc.configured } returns true
    every { rpc.getTransactionDetail("sigABC") } returns detail("finalized", err = null)
    every { payoutRepo.confirmItemIfUnresolved(entry.item.id) } returns true

    val report = reconciler().reconcileOnce()
    assertEquals(1, report.confirmed)
    verify { payoutRepo.markRewardPaid(entry.item.rewardId) }
  }

  @Test
  fun `confirmed but on-chain error stays in manual review - never paid`() {
    val entry = reconcilable()
    every { payoutRepo.listReconcilableItems(50) } returns listOf(entry)
    every { rpc.configured } returns true
    every { rpc.getTransactionDetail("sigABC") } returns
      detail("confirmed", err = mapOf("InstructionError" to listOf(0, "Custom(1)")))

    val report = reconciler().reconcileOnce()

    assertEquals(1, report.failedOnChain)
    verify { payoutRepo.updatePayoutTransaction("sigABC", "devnet", "FAILED", slot = 12345L) }
    verify {
      payoutRepo.updateItemStatus(
        entry.item.id, "REQUIRES_RECONCILIATION", failureReason = any(),
      )
    }
    verify(exactly = 0) { payoutRepo.confirmItemIfUnresolved(any()) }
    verify(exactly = 0) { payoutRepo.markRewardPaid(any()) }
    // reward must NOT be released back to AVAILABLE either
    verify(exactly = 0) { payoutRepo.releaseRewards(any()) }
  }

  @Test
  fun `still pending on-chain leaves item untouched`() {
    val entry = reconcilable()
    every { payoutRepo.listReconcilableItems(50) } returns listOf(entry)
    every { rpc.configured } returns true
    every { rpc.getTransactionDetail("sigABC") } returns detail("processed", err = null)

    val report = reconciler().reconcileOnce()

    assertEquals(1, report.stillPending)
    verify(exactly = 0) { payoutRepo.confirmItemIfUnresolved(any()) }
    verify(exactly = 0) { payoutRepo.markRewardPaid(any()) }
    verify(exactly = 0) { payoutRepo.updateItemStatus(any(), any(), any(), any()) }
  }

  @Test
  fun `unknown signature on cluster stays pending`() {
    val entry = reconcilable()
    every { payoutRepo.listReconcilableItems(50) } returns listOf(entry)
    every { rpc.configured } returns true
    every { rpc.getTransactionDetail("sigABC") } returns null

    val report = reconciler().reconcileOnce()
    assertEquals(1, report.stillPending)
    verify(exactly = 0) { payoutRepo.markRewardPaid(any()) }
  }

  @Test
  fun `rpc error leaves item for next run`() {
    val entry = reconcilable()
    every { payoutRepo.listReconcilableItems(50) } returns listOf(entry)
    every { rpc.configured } returns true
    every { rpc.getTransactionDetail("sigABC") } throws RuntimeException("rpc down")

    val report = reconciler().reconcileOnce()
    assertEquals(1, report.rpcErrors)
    verify(exactly = 0) { payoutRepo.markRewardPaid(any()) }
  }

  @Test
  fun `item without signature is skipped`() {
    val entry = reconcilable(sig = null)
    every { payoutRepo.listReconcilableItems(50) } returns listOf(entry)
    every { rpc.configured } returns true

    val report = reconciler().reconcileOnce()
    assertEquals(1, report.skippedNoSignature)
    verify(exactly = 0) { rpc.getTransactionDetail(any()) }
  }

  @Test
  fun `concurrent reconcile loser cannot double-pay`() {
    val entry = reconcilable()
    every { payoutRepo.listReconcilableItems(50) } returns listOf(entry)
    every { rpc.configured } returns true
    every { rpc.getTransactionDetail("sigABC") } returns detail("confirmed", err = null)
    every { payoutRepo.confirmItemIfUnresolved(entry.item.id) } returns false // already claimed

    val report = reconciler().reconcileOnce()

    assertEquals(0, report.confirmed)
    verify(exactly = 0) { payoutRepo.markRewardPaid(any()) }
    verify(exactly = 0) { payoutRepo.updatePayoutTransaction(any(), any(), "CONFIRMED", any()) }
  }

  @Test
  fun `non-devnet cluster refuses to reconcile`() {
    every { rpc.configured } returns true
    val report = reconciler(cluster = "mainnet").reconcileOnce()
    assertEquals(0, report.checked)
    verify(exactly = 0) { payoutRepo.listReconcilableItems(any()) }
    verify(exactly = 0) { rpc.getTransactionDetail(any()) }
  }

  @Test
  fun `unconfigured rpc refuses to reconcile`() {
    every { rpc.configured } returns false
    val report = reconciler().reconcileOnce()
    assertEquals(0, report.checked)
    verify(exactly = 0) { payoutRepo.listReconcilableItems(any()) }
  }
}
