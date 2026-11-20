package com.capturgo.payments.rewards

import com.capturgo.payments.capturapi.CapturApiClient
import com.capturgo.payments.capturapi.CapturSubmission
import com.capturgo.payments.capturapi.PagedSubmissions
import com.capturgo.payments.payouts.WalletRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RewardSyncServiceTest {

  private val capturApi = mockk<CapturApiClient>()
  private val rewardRepo = mockk<RewardRepository>()
  private val walletRepo = mockk<WalletRepository>()
  private val deviceRepo = mockk<DeviceCapabilityRepository>()
  private val evidenceRepo = mockk<DeviceEvidenceRepository>(relaxed = true)
  private val objectMapper = ObjectMapper()

  private fun service() = RewardSyncService(
    capturApiClient = capturApi,
    rewardRepository = rewardRepo,
    walletRepository = walletRepo,
    objectMapper = objectMapper,
    rewardCalculator = RewardCalculator(
      BigDecimal("5.00"), BigDecimal("4.50"), BigDecimal("3.75"),
    ),
    deviceCapabilityRepository = deviceRepo,
    deviceEvidenceRepository = evidenceRepo,
  )

  private fun submission(
    id: UUID,
    status: String = "APPROVED",
    userId: UUID = UUID.randomUUID(),
    deviceModel: String? = "Pixel 8",
    durationSeconds: Int? = 30,
  ) = CapturSubmission(
    id = id, activityId = UUID.randomUUID(), userId = userId,
    status = status, rewardAmount = BigDecimal.TEN, rewardCurrency = "PTS",
    durationSeconds = durationSeconds, activityTitle = "Test Clip",
    deviceModel = deviceModel, createdAt = null, reviewedAt = null,
  )

  private fun verifiedBasic() = DeviceCapability(
    id = UUID.randomUUID(), deviceKey = "pixel 8", deviceModel = "Pixel 8",
    tier = "BASIC", verification = "VERIFIED", source = "MANUAL",
  )

  private fun pageOf(vararg subs: CapturSubmission) {
    every { capturApi.pageSize } returns 50
    every { capturApi.maxPages } returns 10
    every { capturApi.fetchSubmissions(any(), "APPROVED", 0, 50) } returns
      PagedSubmissions(subs.toList(), subs.size.toLong(), 0, 50)
  }

  @Test
  fun `duplicate submission does not create second reward`() {
    val sub = submission(UUID.randomUUID())
    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns true

    val result = service().sync("token")
    assertEquals(1, result.scanned)
    assertEquals(0, result.created)
    assertEquals(1, result.skippedExisting)
    verify(exactly = 0) {
      rewardRepo.insertIfAbsent(any(), any(), any(), any(), any(), any())
    }
  }

  @Test
  fun `approved submission with verified device creates ledger row`() {
    val sub = submission(UUID.randomUUID())
    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns false
    every { deviceRepo.findByDeviceKey("pixel 8") } returns verifiedBasic()
    every { walletRepo.findByUserId(sub.userId) } returns null
    every { capturApi.getUser(any(), sub.userId) } returns null
    every { walletRepo.upsert(any(), any(), any(), any()) } returns Unit
    every {
      rewardRepo.insertIfAbsent(any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any())
    } returns true
    every { evidenceRepo.insertIfAbsent(any(), any(), any()) } returns true

    val result = service().sync("token")
    assertEquals(1, result.scanned)
    assertEquals(1, result.created)
    assertEquals(1, result.walletsMissing)
    // 30s at BASIC $3.75/hr = 0.03125 USDC = 31_250 base units
    verify(exactly = 1) {
      rewardRepo.insertIfAbsent(sub.id, sub.userId, sub.activityId, "Test Clip", 31_250L, any(),
        "BASIC", "Pixel 8", 3_750_000L, 30, RewardCalculator.CALCULATION_VERSION)
    }
    // Phase B: evidence row also persisted
    verify(exactly = 1) {
      evidenceRepo.insertIfAbsent(sub.id, sub.userId, sub)
    }
  }

  @Test
  fun `lowercase approved status from captur-api still creates reward`() {
    // captur-api serializes ActivityStatus via toDisplayName() → "approved".
    // The eligibility check must be case-insensitive or every real
    // submission is skipped as INELIGIBLE.
    val sub = submission(UUID.randomUUID(), status = "approved")
    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns false
    every { deviceRepo.findByDeviceKey("pixel 8") } returns verifiedBasic()
    every { walletRepo.findByUserId(sub.userId) } returns null
    every { capturApi.getUser(any(), sub.userId) } returns null
    every { walletRepo.upsert(any(), any(), any(), any()) } returns Unit
    every {
      rewardRepo.insertIfAbsent(any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any())
    } returns true
    every { evidenceRepo.insertIfAbsent(any(), any(), any()) } returns true

    val result = service().sync("token")
    assertEquals(1, result.created)
    assertEquals(0, result.skippedIneligible)
  }

  @Test
  fun `unknown device is ineligible - never auto-basic`() {
    val sub = submission(UUID.randomUUID(), deviceModel = "Unknown Phone X")
    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns false
    every { deviceRepo.findByDeviceKey("unknown phone x") } returns null

    val result = service().sync("token")
    assertEquals(0, result.created)
    assertEquals(1, result.skippedIneligible)
    verify(exactly = 0) {
      rewardRepo.insertIfAbsent(any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any())
    }
  }

  @Test
  fun `unverified device is ineligible`() {
    val sub = submission(UUID.randomUUID())
    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns false
    every { deviceRepo.findByDeviceKey("pixel 8") } returns
      verifiedBasic().copy(verification = "UNVERIFIED")

    val result = service().sync("token")
    assertEquals(0, result.created)
    assertEquals(1, result.skippedIneligible)
  }

  @Test
  fun `missing device model is ineligible`() {
    val sub = submission(UUID.randomUUID(), deviceModel = null)
    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns false

    val result = service().sync("token")
    assertEquals(0, result.created)
    assertEquals(1, result.skippedIneligible)
  }

  @Test
  fun `capture evidence fields are persisted into ledger metadata`() {
    val sub = submission(UUID.randomUUID()).copy(
      imuFrequencyHz = 200.0,
      gyroSampleCount = 6000,
      accelSampleCount = 6000,
      syncTier = "FULL",
      gyroStreamUrl = "https://cdn.example/gyro.bin",
      accelStreamUrl = "https://cdn.example/accel.bin",
      metadataUrls = mapOf("manifest" to "https://cdn.example/manifest.json"),
      verificationCount = 2,
      rejectionCount = 1,
    )
    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns false
    every { deviceRepo.findByDeviceKey("pixel 8") } returns verifiedBasic()
    every { walletRepo.findByUserId(sub.userId) } returns null
    every { capturApi.getUser(any(), sub.userId) } returns null
    every { walletRepo.upsert(any(), any(), any(), any()) } returns Unit
    every {
      rewardRepo.insertIfAbsent(any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any())
    } returns true
    every { evidenceRepo.insertIfAbsent(any(), any(), any()) } returns true

    service().sync("token")

    // Evidence snapshot is persisted with the full submission payload —
    // the repository maps every capture field into submission_device_evidence.
    verify(exactly = 1) { evidenceRepo.insertIfAbsent(sub.id, sub.userId, sub) }
    // Tier still comes from the registry, not the client-supplied syncTier
    verify {
      rewardRepo.insertIfAbsent(sub.id, sub.userId, sub.activityId, "Test Clip", 31_250L, any(),
        "BASIC", "Pixel 8", 3_750_000L, 30, RewardCalculator.CALCULATION_VERSION)
    }
  }

  @Test
  fun `evidence insert failure does not block reward creation`() {
    val sub = submission(UUID.randomUUID())
    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns false
    every { deviceRepo.findByDeviceKey("pixel 8") } returns verifiedBasic()
    every { walletRepo.findByUserId(sub.userId) } returns null
    every { capturApi.getUser(any(), sub.userId) } returns null
    every { walletRepo.upsert(any(), any(), any(), any()) } returns Unit
    every {
      rewardRepo.insertIfAbsent(any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any())
    } returns true
    every { evidenceRepo.insertIfAbsent(any(), any(), any()) } throws RuntimeException("db down")

    val result = service().sync("token")
    assertEquals(1, result.created)
    assertEquals(0, result.errors.size)
  }

  @Test
  fun `existing reward skips evidence insert`() {
    val sub = submission(UUID.randomUUID())
    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns true

    service().sync("token")
    verify(exactly = 0) { evidenceRepo.insertIfAbsent(any(), any(), any()) }
  }

  @Test
  fun `non-approved status is skipped`() {
    val sub = submission(UUID.randomUUID(), status = "REJECTED")
    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns false

    val result = service().sync("token")
    assertEquals(0, result.created)
    assertEquals(1, result.skippedIneligible)
  }

  @Test
  fun `solana address validation`() {
    assertTrue(RewardSyncService.isSolanaAddress("So11111111111111111111111111111111111111112"))
    assertTrue(!RewardSyncService.isSolanaAddress("not-an-address"))
    assertTrue(!RewardSyncService.isSolanaAddress(""))
  }
}
