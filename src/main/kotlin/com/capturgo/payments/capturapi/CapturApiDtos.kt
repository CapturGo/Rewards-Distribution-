package com.capturgo.payments.capturapi

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class CapturSubmission(
  val id: UUID,
  val activityId: UUID,
  val activityTitle: String? = null,
  val userId: UUID,
  val username: String? = null,
  val userEmail: String? = null,
  val userTokenBalance: Long? = null,
  val status: String,
  val durationSeconds: Int? = null,
  val rewardAmount: BigDecimal? = null,
  val rewardCurrency: String? = null,
  val rejectionReasons: List<String>? = null,
  val deviceModel: String? = null,
  val videoUrl: String? = null,
  // Capture-evidence fields exposed by the deployed captur-api submission DTO.
  // Persisted for audit only — they do NOT determine tier (registry does).
  val imuFrequencyHz: Double? = null,
  val gyroSampleCount: Int? = null,
  val accelSampleCount: Int? = null,
  val syncTier: String? = null,
  val gyroStreamUrl: String? = null,
  val accelStreamUrl: String? = null,
  val metadataUrls: Map<String, Any?>? = null,
  val verificationCount: Int? = null,
  val rejectionCount: Int? = null,
  // True when QA verified the clip but placed a payout hold — the clip is
  // eligible for delivery but must NOT generate a USDC reward.
  val payoutHold: Boolean? = null,
  val metadata: Map<String, Any?>? = null,
  val createdAt: Instant? = null,
  val reviewedAt: Instant? = null,
  val updatedAt: Instant? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PagedSubmissions(
  val items: List<CapturSubmission> = emptyList(),
  val totalItems: Long = 0,
  val limit: Int = 0,
  val offset: Int = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CapturUserProfile(
  val id: UUID? = null,
  val userId: UUID? = null,
  val username: String? = null,
  val email: String? = null,
  val walletAddress: String? = null,
  val seekerWallet: String? = null,
  val displayName: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AdminVerifyResponse(
  val userId: UUID? = null,
  val id: UUID? = null,
  val email: String? = null,
  val username: String? = null,
  val roles: List<String> = emptyList(),
  val role: String? = null,
)
