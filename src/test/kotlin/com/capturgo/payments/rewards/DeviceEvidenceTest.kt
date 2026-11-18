package com.capturgo.payments.rewards

import com.capturgo.payments.capturapi.CapturApiClient
import com.capturgo.payments.capturapi.CapturSubmission
import com.capturgo.payments.capturapi.PagedSubmissions
import com.capturgo.payments.payouts.WalletRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.math.BigDecimal
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase B tests: device evidence extraction and persistence.
 * Verifies that sensor telemetry from captur-api is captured alongside
 * each reward row without changing reward calculation or eligibility.
 */
class DeviceEvidenceTest {

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

  private fun verifiedFull() = DeviceCapability(
    id = UUID.randomUUID(), deviceKey = "pixel 8 pro", deviceModel = "Pixel 8 Pro",
    tier = "FULL", verification = "VERIFIED", source = "MANUAL",
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

  private fun stubRewardInsert() {
    every {
      rewardRepo.insertIfAbsent(any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any())
    } returns true
  }

  private fun stubWalletMissing(userId: UUID) {
    every { walletRepo.findByUserId(userId) } returns null
    every { capturApi.getUser(any(), userId) } returns null
  }

  // ── Evidence is persisted with all sensor fields ──

  @Test
  fun `evidence row captures all sensor fields from captur-api`() {
    val userId = UUID.randomUUID()
    val sub = CapturSubmission(
      id = UUID.randomUUID(),
      activityId = UUID.randomUUID(),
      activityTitle = "Walking Clip",
      userId = userId,
      status = "APPROVED",
      durationSeconds = 60,
      deviceModel = "Pixel 8",
      rewardAmount = BigDecimal.TEN,
      rewardCurrency = "PTS",
      // Phase B fields
      syncTier = "GOLD",
      imuFrequencyHz = 200.0,
      gyroSampleCount = 12000,
      accelSampleCount = 12000,
      gyroStreamUrl = "https://storage.example.com/gyro/abc.bin",
      accelStreamUrl = "https://storage.example.com/accel/abc.bin",
      metadataUrls = mapOf("gyro" to "https://meta/g", "accel" to "https://meta/a"),
      metadata = mapOf("captureVersion" to "2.1", "sdkVersion" to "1.0"),
    )

    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns false
    every { deviceRepo.findByDeviceKey("pixel 8") } returns verifiedBasic()
    stubWalletMissing(userId)
    stubRewardInsert()

    val capturedSub = slot<CapturSubmission>()
    every { evidenceRepo.insertIfAbsent(any(), any(), capture(capturedSub)) } returns true

    service().sync("token")

    verify(exactly = 1) { evidenceRepo.insertIfAbsent(sub.id, userId, any()) }
    val captured = capturedSub.captured
    assertEquals("GOLD", captured.syncTier)
    assertEquals(200.0, captured.imuFrequencyHz)
    assertEquals(12000, captured.gyroSampleCount)
    assertEquals(12000, captured.accelSampleCount)
    assertEquals("https://storage.example.com/gyro/abc.bin", captured.gyroStreamUrl)
    assertEquals("https://storage.example.com/accel/abc.bin", captured.accelStreamUrl)
    assertNotNull(captured.metadataUrls)
  }

  // ── Evidence includes sensor data in reward metadata JSON ──

  @Test
  fun `reward metadata JSON includes device evidence fields`() {
    val userId = UUID.randomUUID()
    val sub = CapturSubmission(
      id = UUID.randomUUID(),
      activityId = UUID.randomUUID(),
      userId = userId,
      status = "APPROVED",
      durationSeconds = 30,
      deviceModel = "Pixel 8",
      syncTier = "SILVER",
      imuFrequencyHz = 100.0,
      gyroSampleCount = 3000,
      accelSampleCount = 3000,
      gyroStreamUrl = "https://gyro.example",
      accelStreamUrl = null,
    )

    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns false
    every { deviceRepo.findByDeviceKey("pixel 8") } returns verifiedBasic()
    stubWalletMissing(userId)
    every { evidenceRepo.insertIfAbsent(any(), any(), any()) } returns true

    val metadataSlot = slot<String>()
    every {
      rewardRepo.insertIfAbsent(any(), any(), any(), any(), any(),
        capture(metadataSlot), any(), any(), any(), any(), any())
    } returns true

    service().sync("token")

    val parsed = objectMapper.readTree(metadataSlot.captured)
    val ev = parsed.get("captureEvidence")
    assertEquals("SILVER", ev.get("syncTier").asText())
    assertEquals(100.0, ev.get("imuFrequencyHz").asDouble(), 0.001)
    assertEquals(3000, ev.get("gyroSampleCount").asInt())
    assertEquals(3000, ev.get("accelSampleCount").asInt())
    assertEquals("https://gyro.example", ev.get("gyroStreamUrl").asText())
    // accelStreamUrl was null on the submission — omitted, not zeroed
    org.junit.jupiter.api.Assertions.assertFalse(ev.has("accelStreamUrl"))
  }

  // ── Evidence is NOT persisted when reward already exists ──

  @Test
  fun `duplicate submission skips both reward and evidence`() {
    val sub = CapturSubmission(
      id = UUID.randomUUID(),
      activityId = UUID.randomUUID(),
      userId = UUID.randomUUID(),
      status = "APPROVED",
      durationSeconds = 30,
      deviceModel = "Pixel 8",
      syncTier = "GOLD",
      imuFrequencyHz = 200.0,
    )
    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns true

    service().sync("token")

    verify(exactly = 0) { evidenceRepo.insertIfAbsent(any(), any(), any()) }
  }

  // ── Evidence is NOT persisted for ineligible submissions ──

  @Test
  fun `ineligible device skips evidence persistence`() {
    val sub = CapturSubmission(
      id = UUID.randomUUID(),
      activityId = UUID.randomUUID(),
      userId = UUID.randomUUID(),
      status = "APPROVED",
      durationSeconds = 30,
      deviceModel = "Unknown Phone",
      syncTier = "BRONZE",
      imuFrequencyHz = 50.0,
    )
    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns false
    every { deviceRepo.findByDeviceKey("unknown phone") } returns null

    service().sync("token")

    verify(exactly = 0) { evidenceRepo.insertIfAbsent(any(), any(), any()) }
  }

  // ── Evidence failure does not block reward creation ──

  @Test
  fun `evidence insert failure does not roll back reward`() {
    val userId = UUID.randomUUID()
    val sub = CapturSubmission(
      id = UUID.randomUUID(),
      activityId = UUID.randomUUID(),
      userId = userId,
      status = "APPROVED",
      durationSeconds = 30,
      deviceModel = "Pixel 8",
      syncTier = "GOLD",
    )

    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns false
    every { deviceRepo.findByDeviceKey("pixel 8") } returns verifiedBasic()
    stubWalletMissing(userId)
    stubRewardInsert()
    every { evidenceRepo.insertIfAbsent(any(), any(), any()) } throws
      RuntimeException("DB connection lost")

    val result = service().sync("token")

    // Reward was still created despite evidence failure
    assertEquals(1, result.created)
    verify(exactly = 1) {
      rewardRepo.insertIfAbsent(any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any())
    }
  }

  // ── Null sensor fields are handled gracefully ──

  @Test
  fun `null sensor fields produce evidence with defaults`() {
    val userId = UUID.randomUUID()
    val sub = CapturSubmission(
      id = UUID.randomUUID(),
      activityId = UUID.randomUUID(),
      userId = userId,
      status = "APPROVED",
      durationSeconds = 45,
      deviceModel = "Pixel 8",
      // All Phase B fields are null — captur-api didn't return them
    )

    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns false
    every { deviceRepo.findByDeviceKey("pixel 8") } returns verifiedBasic()
    stubWalletMissing(userId)
    stubRewardInsert()

    val capturedSub = slot<CapturSubmission>()
    every { evidenceRepo.insertIfAbsent(any(), any(), capture(capturedSub)) } returns true

    service().sync("token")

    val captured = capturedSub.captured
    assertEquals(null, captured.syncTier)
    assertEquals(null, captured.imuFrequencyHz)
    assertEquals(null, captured.gyroSampleCount)
    assertEquals(null, captured.accelSampleCount)
    assertEquals(null, captured.gyroStreamUrl)
    assertEquals(null, captured.accelStreamUrl)
    assertEquals(null, captured.metadataUrls)
  }

  // ── Reward calculation is unchanged by Phase B ──

  @Test
  fun `Phase B does not alter reward amount calculation`() {
    val userId = UUID.randomUUID()
    // High-evidence submission
    val sub = CapturSubmission(
      id = UUID.randomUUID(),
      activityId = UUID.randomUUID(),
      userId = userId,
      status = "APPROVED",
      durationSeconds = 3600,  // exactly 1 hour
      deviceModel = "Pixel 8",
      syncTier = "DIAMOND",
      imuFrequencyHz = 500.0,
      gyroSampleCount = 1800000,
      accelSampleCount = 1800000,
    )

    pageOf(sub)
    every { rewardRepo.existsBySubmissionId(sub.id) } returns false
    every { deviceRepo.findByDeviceKey("pixel 8") } returns verifiedBasic()
    stubWalletMissing(userId)
    every { evidenceRepo.insertIfAbsent(any(), any(), any()) } returns true

    val amountSlot = slot<Long>()
    every {
      rewardRepo.insertIfAbsent(any(), any(), any(), any(), capture(amountSlot), any(),
        any(), any(), any(), any(), any())
    } returns true

    service().sync("token")

    // 1 hour at BASIC tier = $3.75 = 3_750_000 USDC units
    // syncTier "DIAMOND" does NOT change the calculation — only the
    // device_capabilities registry tier matters.
    assertEquals(3_750_000L, amountSlot.captured)
  }
}
