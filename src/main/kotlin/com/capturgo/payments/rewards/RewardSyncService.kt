package com.capturgo.payments.rewards

import com.capturgo.payments.capturapi.CapturApiClient
import com.capturgo.payments.capturapi.CapturSubmission
import com.capturgo.payments.payouts.WalletRepository
import com.capturgo.payments.solana.PrivyWalletClient
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Polls captur-api for APPROVED submissions and creates idempotent
 * USDC reward-ledger rows. Safe to re-run: submission_id UNIQUE +
 * INSERT ... ON CONFLICT DO NOTHING makes processing exactly-once.
 */
@Service
class RewardSyncService(
  private val capturApiClient: CapturApiClient,
  private val rewardRepository: RewardRepository,
  private val walletRepository: WalletRepository,
  private val objectMapper: ObjectMapper,
  private val rewardCalculator: RewardCalculator,
  private val deviceCapabilityRepository: DeviceCapabilityRepository,
  private val deviceEvidenceRepository: DeviceEvidenceRepository,
  private val privyWalletClient: PrivyWalletClient,
  @Value("\${payments.wallet.privy-autofill-enabled:true}")
  private val privyAutofillEnabled: Boolean,
) {
  private val logger = LoggerFactory.getLogger(RewardSyncService::class.java)

  @Volatile
  private var running = false

  /** Per-sync cache so repeated submissions from one user resolve once. */
  private val walletLookupCache = java.util.concurrent.ConcurrentHashMap<java.util.UUID, String?>()

  @Transactional
  fun sync(token: String): RewardSyncResult {
    if (running) {
      return RewardSyncResult(0, 0, 0, 0, 0, 0, listOf("sync already in progress"), Instant.now())
    }
    running = true
    walletLookupCache.clear()
    var scanned = 0
    var created = 0
    var skippedExisting = 0
    var skippedIneligible = 0
    var walletsResolved = 0
    var walletsMissing = 0
    val errors = mutableListOf<String>()

    try {
      var offset = 0
      var pages = 0
      while (pages < capturApiClient.maxPages) {
        val page = capturApiClient.fetchSubmissions(token, "APPROVED", offset, capturApiClient.pageSize)
        pages++
        if (page.items.isEmpty()) break

        for (sub in page.items) {
          scanned++
          try {
            val outcome = processSubmission(token, sub)
            when (outcome.outcome) {
              Outcome.CREATED -> { created++; if (outcome.walletResolved) walletsResolved++ else walletsMissing++ }
              Outcome.EXISTING -> skippedExisting++
              Outcome.INELIGIBLE -> skippedIneligible++
            }
          } catch (e: Exception) {
            errors.add("${sub.id}: ${e.message}")
            logger.warn("reward sync failed for submission {}: {}", sub.id, e.message)
          }
        }

        offset += page.items.size
        if (page.items.size < capturApiClient.pageSize) break
        if (offset >= page.totalItems) break
      }
    } finally {
      running = false
    }

    val result = RewardSyncResult(
      scanned = scanned, created = created, skippedExisting = skippedExisting,
      skippedIneligible = skippedIneligible, walletsResolved = walletsResolved,
      walletsMissing = walletsMissing, errors = errors, finishedAt = Instant.now(),
    )
    logger.info("reward sync done: {}", result)
    return result
  }

  private enum class Outcome { CREATED, EXISTING, INELIGIBLE }
  private data class OutcomeResult(val outcome: Outcome, val walletResolved: Boolean = false)

  private fun processSubmission(token: String, sub: CapturSubmission): OutcomeResult {
    if (rewardRepository.existsBySubmissionId(sub.id)) {
      // Backfill payout wallet for already-synced rewards whose user has no
      // VERIFIED wallet yet (e.g. synced before Privy autofill existed).
      // resolveWallet short-circuits when a VERIFIED wallet already exists.
      resolveWallet(token, sub.userId)
      return OutcomeResult(Outcome.EXISTING)
    }
    // captur-api returns the display name ("approved"), not the enum name —
    // compare case-insensitively or every submission lands INELIGIBLE.
    if (!sub.status.equals("APPROVED", ignoreCase = true)) {
      return OutcomeResult(Outcome.INELIGIBLE)
    }

    // QA "verify only" clips carry a payout hold — verified for quality but
    // explicitly excluded from USDC rewards.
    if (sub.payoutHold == true) {
      return OutcomeResult(Outcome.INELIGIBLE)
    }

    // Device tier comes from the capability registry — unknown or unverified
    // devices are INELIGIBLE (never auto-BASIC) per product decision.
    val deviceKey = sub.deviceModel?.trim()?.lowercase()
    val capability = deviceKey?.let { deviceCapabilityRepository.findByDeviceKey(it) }
    if (capability == null || capability.verification != "VERIFIED") {
      return OutcomeResult(Outcome.INELIGIBLE)
    }
    val tier = runCatching { DeviceTier.valueOf(capability.tier) }.getOrNull()
      ?: return OutcomeResult(Outcome.INELIGIBLE)

    // Hourly-tier calculation (spec §2): reward = duration/3600 × tier rate.
    // durationSeconds is the eligible-duration source until product confirms
    // the authoritative metric (spec §9.1).
    val durationSeconds = sub.durationSeconds
    if (durationSeconds == null || durationSeconds <= 0) {
      return OutcomeResult(Outcome.INELIGIBLE)
    }
    val calc = rewardCalculator.calculate(tier, durationSeconds)
    if (calc.rewardUsdcUnits <= 0) {
      return OutcomeResult(Outcome.INELIGIBLE)
    }

    val wallet = resolveWallet(token, sub.userId)

    val metadata = objectMapper.writeValueAsString(
      mapOf(
        "walletMissing" to (wallet == null),
        "rewardAmount" to (sub.rewardAmount?.toPlainString() ?: ""),
        "rewardCurrency" to (sub.rewardCurrency ?: ""),
        "deviceTier" to tier.name,
        "hourlyRateUsd" to calc.hourlyRateUsd.toPlainString(),
        "calculationVersion" to RewardCalculator.CALCULATION_VERSION,
        "captureEvidence" to captureEvidence(sub),
        "syncedAt" to Instant.now().toString(),
      ),
    )

    val inserted = rewardRepository.insertIfAbsent(
      submissionId = sub.id,
      capturUserId = sub.userId,
      activityId = sub.activityId,
      activityTitle = sub.activityTitle,
      amountUsdcUnits = calc.rewardUsdcUnits,
      metadataJson = metadata,
      deviceTier = tier.name,
      deviceModel = sub.deviceModel,
      hourlyRateUsdcUnits = calc.hourlyRateUsd.movePointRight(6).longValueExact(),
      eligibleDurationSeconds = durationSeconds,
      calculationVersion = RewardCalculator.CALCULATION_VERSION,
    )

    // Phase B: persist device evidence snapshot (idempotent, non-fatal)
    if (inserted) {
      try {
        deviceEvidenceRepository.insertIfAbsent(sub.id, sub.userId, sub)
      } catch (e: Exception) {
        logger.warn("device evidence insert failed for submission {}: {}", sub.id, e.message)
        // Non-fatal — the reward row is already created.
        // Evidence can be backfilled later if this fails.
      }
    }

    return if (inserted) {
      OutcomeResult(Outcome.CREATED, wallet != null)
    } else {
      OutcomeResult(Outcome.EXISTING)
    }
  }

  /**
   * Capture evidence reported by captur-api for this submission, embedded in
   * the ledger metadata JSONB. Also persisted structurally in
   * submission_device_evidence. Audit only — tier eligibility comes from the
   * VERIFIED device registry, never from these client-supplied values.
   */
  private fun captureEvidence(sub: CapturSubmission): Map<String, Any?> = buildMap {
    sub.imuFrequencyHz?.let { put("imuFrequencyHz", it) }
    sub.gyroSampleCount?.let { put("gyroSampleCount", it) }
    sub.accelSampleCount?.let { put("accelSampleCount", it) }
    sub.syncTier?.let { put("syncTier", it) }
    sub.gyroStreamUrl?.let { put("gyroStreamUrl", it) }
    sub.accelStreamUrl?.let { put("accelStreamUrl", it) }
    sub.metadataUrls?.let { put("metadataUrls", it) }
    sub.verificationCount?.let { put("verificationCount", it) }
    sub.rejectionCount?.let { put("rejectionCount", it) }
  }

  /** Resolve the contributor's Solana wallet from captur-api user profile. */
  private fun resolveWallet(token: String, userId: java.util.UUID): String? {
    val existing = walletRepository.findByUserId(userId)
    if (existing != null && existing.verification == "VERIFIED") return existing.solanaAddress
    if (walletLookupCache.containsKey(userId)) return walletLookupCache[userId]

    val resolved = resolveWalletUncached(token, userId)
    walletLookupCache[userId] = resolved
    return resolved
  }

  private fun resolveWalletUncached(token: String, userId: java.util.UUID): String? {
    val profile = capturApiClient.getUser(token, userId) ?: return null
    var candidate = profile.seekerWallet ?: profile.walletAddress

    // Fallback: users who never linked a seeker wallet still have a Privy
    // embedded Solana wallet (created at login). Resolve it via the Privy
    // DID on the profile so they remain payable.
    if (candidate == null && privyAutofillEnabled && privyWalletClient.configured) {
      val privyDid = profile.externalId
      if (!privyDid.isNullOrBlank()) {
        candidate = try {
          privyWalletClient.getUserSolanaAddress(privyDid)
        } catch (e: Exception) {
          logger.warn("privy wallet lookup failed for user {}: {}", userId, e.message)
          null
        }
      }
    }
    if (candidate == null) return null
    val valid = isSolanaAddress(candidate)
    walletRepository.upsert(
      userId = userId,
      address = candidate,
      privyWalletId = null,
      verification = if (valid) "VERIFIED" else "INVALID",
    )
    return if (valid) candidate else null
  }

  companion object {
    /** Basic Solana address format check: base58, 32–44 chars. */
    fun isSolanaAddress(address: String): Boolean {
      if (address.length !in 32..44) return false
      return address.all { it.isLetterOrDigit() && it != '0' && it != 'O' && it != 'I' && it != 'l' }
    }
  }
}
