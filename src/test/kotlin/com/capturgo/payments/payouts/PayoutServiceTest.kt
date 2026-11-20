package com.capturgo.payments.payouts

import com.capturgo.payments.core.auth.AuthPrincipal
import com.capturgo.payments.core.exception.ConflictException
import com.capturgo.payments.core.exception.FeatureDisabledException
import com.capturgo.payments.rewards.RewardRepository
import com.capturgo.payments.rewards.RewardLedgerRow
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PayoutServiceTest {

  private val payoutRepo = mockk<PayoutRepository>()
  private val rewardRepo = mockk<RewardRepository>()
  private val walletRepo = mockk<WalletRepository>()
  private val objectMapper = ObjectMapper()

  private fun service(solEnabled: Boolean = false) = PayoutService(
    payoutRepository = payoutRepo,
    rewardRepository = rewardRepo,
    walletRepository = walletRepo,
    objectMapper = objectMapper,
    payoutExecutionService = mockk(relaxed = true),
    solanaEnabled = solEnabled,
    solanaCluster = "devnet",
  )

  private fun admin(id: UUID = UUID.randomUUID()) =
    AuthPrincipal(privyUserId = id.toString(), email = "admin@test.com",
      capturUserId = id, roles = setOf("ADMIN"), rawToken = "t")

  private fun reward(id: UUID = UUID.randomUUID(), status: String = "AVAILABLE", userId: UUID = UUID.randomUUID()) =
    RewardLedgerRow(
      id = id, submissionId = UUID.randomUUID(), capturUserId = userId,
      activityId = UUID.randomUUID(), activityTitle = "clip",
      amountUsdcUnits = 5_000_000L, status = status, metadata = null, createdAt = Instant.now(),
    )

  @Test
  fun `execute is blocked when solana disabled`() {
    val batchId = UUID.randomUUID()
    val batch = PayoutBatch(batchId, "APPROVED", 5_000_000L, 1, "devnet", "k", UUID.randomUUID(),
      UUID.randomUUID(), Instant.now(), null, Instant.now())
    every { payoutRepo.findBatch(batchId) } returns batch
    assertThrows(FeatureDisabledException::class.java) {
      service(solEnabled = false).executeBatch(batchId, admin())
    }
  }

  @Test
  fun `unavailable rewards cannot be batched`() {
    val r = reward(status = "RESERVED_FOR_PAYOUT")
    every { payoutRepo.findBatchByIdempotencyKey(any()) } returns null
    every { rewardRepo.findByIds(listOf(r.id)) } returns listOf(r)
    every { rewardRepo.findById(r.id) } returns r

    assertThrows(ConflictException::class.java) {
      service().createBatch(CreateBatchRequest(listOf(r.id), "key-1"), admin(), null, null)
    }
  }

  @Test
  fun `idempotent replay returns existing batch`() {
    val existing = PayoutBatch(UUID.randomUUID(), "PENDING_APPROVAL", 5_000_000L, 1, "devnet", "dup-key",
      UUID.randomUUID(), null, null, null, Instant.now())
    every { payoutRepo.findBatchByIdempotencyKey("dup-key") } returns existing
    val result = service().createBatch(CreateBatchRequest(listOf(UUID.randomUUID()), "dup-key"), admin(), null, null)
    assertEquals(existing.id, result.id)
    verify(exactly = 0) { rewardRepo.findByIds(any()) }
  }

  @Test
  fun `batch creation rejects rewards without a verified solana wallet`() {
    val r = reward()
    every { payoutRepo.findBatchByIdempotencyKey(any()) } returns null
    every { rewardRepo.findByIds(listOf(r.id)) } returns listOf(r)
    every { rewardRepo.findById(r.id) } returns r
    every { payoutRepo.insertBatch(any(), any(), any(), any(), any(), any()) } returns UUID.randomUUID()
    every { payoutRepo.reserveRewards(listOf(r.id)) } returns 1
    every { walletRepo.findAll() } returns emptyMap()

    assertThrows(com.capturgo.payments.core.exception.UnprocessableException::class.java) {
      service().createBatch(CreateBatchRequest(listOf(r.id), "key-w"), admin(), null, null)
    }
  }

  @Test
  fun `approve loses atomically when batch already left pre-execution state`() {
    val creatorId = UUID.randomUUID()
    val approverId = UUID.randomUUID()
    val batchId = UUID.randomUUID()
    val batch = PayoutBatch(batchId, "PROCESSING", 5_000_000L, 1, "devnet", "k", creatorId,
      null, null, null, Instant.now())
    every { payoutRepo.findBatch(batchId) } returns batch
    every { payoutRepo.approveBatchAtomically(batchId, approverId) } returns false
    val approver = AuthPrincipal(approverId.toString(), "b@t.com", approverId, setOf("ADMIN"), "t")

    assertThrows(ConflictException::class.java) {
      service().approveBatch(batchId, approver, null, null)
    }
  }

  @Test
  fun `approver must differ from creator`() {
    val creatorId = UUID.randomUUID()
    val batchId = UUID.randomUUID()
    val batch = PayoutBatch(batchId, "PENDING_APPROVAL", 5_000_000L, 1, "devnet", "k", creatorId,
      null, null, null, Instant.now())
    every { payoutRepo.findBatch(batchId) } returns batch
    val sameAdmin = AuthPrincipal(creatorId.toString(), "a@t.com", creatorId, setOf("ADMIN"), "t")
    assertThrows(com.capturgo.payments.core.exception.ForbiddenException::class.java) {
      service().approveBatch(batchId, sameAdmin, null, null)
    }
  }
}
