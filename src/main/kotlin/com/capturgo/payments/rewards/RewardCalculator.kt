package com.capturgo.payments.rewards

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Device reward tiers per the "How Rewards Work" spec. */
enum class DeviceTier { FULL, IPHONE, BASIC }

/**
 * Reward = eligibleDurationSeconds / 3600 × tier hourly rate.
 * All math in BigDecimal; final USDC stored as integer base units (6 dp),
 * rounded HALF_UP. calculation_version bumps whenever rules change so old
 * rewards remain auditable. Rates are configurable (spec §10.1).
 */
@Component
class RewardCalculator(
  @Value("\${payments.rewards.tier-full-usd-per-hour:5.00}") private val fullRate: BigDecimal,
  @Value("\${payments.rewards.tier-iphone-usd-per-hour:4.50}") private val iphoneRate: BigDecimal,
  @Value("\${payments.rewards.tier-basic-usd-per-hour:3.75}") private val basicRate: BigDecimal,
) {

  data class Calculation(
    val tier: DeviceTier,
    val hourlyRateUsd: BigDecimal,
    val eligibleDurationSeconds: Int,
    val rewardUsd: BigDecimal,
    val rewardUsdcUnits: Long,
  )

  fun rateFor(tier: DeviceTier): BigDecimal = when (tier) {
    DeviceTier.FULL -> fullRate
    DeviceTier.IPHONE -> iphoneRate
    DeviceTier.BASIC -> basicRate
  }

  /**
   * Classify tier from device capabilities. Conservative default: anything
   * unrecognized falls back to BASIC — the backend is authoritative, the app
   * display is not (spec §4). Exact tier rules pending mobile/product
   * confirmation (spec §9.4).
   */
  fun classifyTier(
    deviceModel: String?,
    isIphone: Boolean = deviceModel?.contains("iphone", ignoreCase = true) == true,
    hasGyroscope: Boolean? = null,
    hasAccelerometer: Boolean? = null,
    imuFrequencyHz: Int? = null,
  ): DeviceTier {
    if (hasGyroscope == false || hasAccelerometer == false) return DeviceTier.BASIC
    return when {
      isIphone -> DeviceTier.IPHONE
      imuFrequencyHz != null && imuFrequencyHz >= 200 -> DeviceTier.FULL
      else -> DeviceTier.BASIC
    }
  }

  fun calculate(tier: DeviceTier, eligibleDurationSeconds: Int): Calculation {
    require(eligibleDurationSeconds >= 0) { "duration must be >= 0" }
    val hours = BigDecimal(eligibleDurationSeconds).divide(SECONDS_PER_HOUR, MC)
    val rewardUsd = hours.multiply(rateFor(tier), MC)
      .setScale(6, RoundingMode.HALF_UP)
    return Calculation(
      tier = tier,
      hourlyRateUsd = rateFor(tier),
      eligibleDurationSeconds = eligibleDurationSeconds,
      rewardUsd = rewardUsd,
      rewardUsdcUnits = rewardUsd.movePointRight(6).longValueExact(),
    )
  }

  companion object {
    const val CALCULATION_VERSION = "v1-hourly-tier"
    private val SECONDS_PER_HOUR = BigDecimal(3600)
    private val MC = MathContext.DECIMAL64
  }
}
