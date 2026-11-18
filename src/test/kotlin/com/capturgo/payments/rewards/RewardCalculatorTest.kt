package com.capturgo.payments.rewards

import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RewardCalculatorTest {

  private val calc = RewardCalculator(
    BigDecimal("5.00"), BigDecimal("4.50"), BigDecimal("3.75"),
  )

  @Test
  fun `spec examples - full tier`() {
    assertEquals(5_000_000L, calc.calculate(DeviceTier.FULL, 3600).rewardUsdcUnits)
    assertEquals(2_500_000L, calc.calculate(DeviceTier.FULL, 1800).rewardUsdcUnits)
  }

  @Test
  fun `spec examples - iphone and basic tiers`() {
    assertEquals(4_500_000L, calc.calculate(DeviceTier.IPHONE, 3600).rewardUsdcUnits)
    assertEquals(2_250_000L, calc.calculate(DeviceTier.IPHONE, 1800).rewardUsdcUnits)
    assertEquals(3_750_000L, calc.calculate(DeviceTier.BASIC, 3600).rewardUsdcUnits)
    assertEquals(1_875_000L, calc.calculate(DeviceTier.BASIC, 1800).rewardUsdcUnits)
  }

  @Test
  fun `partial seconds are proportional`() {
    // 10 seconds at FULL $5/hr = 5/360 * ... = 0.013888... -> 13_889 units HALF_UP
    val c = calc.calculate(DeviceTier.FULL, 10)
    assertEquals(13_889L, c.rewardUsdcUnits)
    assertEquals(BigDecimal("0.013889"), c.rewardUsd)
  }

  @Test
  fun `zero duration yields zero reward`() {
    assertEquals(0L, calc.calculate(DeviceTier.FULL, 0).rewardUsdcUnits)
  }

  @Test
  fun `negative duration rejected`() {
    assertTrue(runCatching { calc.calculate(DeviceTier.FULL, -1) }.isFailure)
  }

  @Test
  fun `tier classification is conservative`() {
    assertEquals(DeviceTier.IPHONE, calc.classifyTier("iPhone 15"))
    assertEquals(DeviceTier.FULL, calc.classifyTier("Pixel 8", imuFrequencyHz = 200))
    assertEquals(DeviceTier.BASIC, calc.classifyTier("Random Android"))
    assertEquals(DeviceTier.BASIC, calc.classifyTier(null))
    assertEquals(DeviceTier.BASIC, calc.classifyTier("iPhone 15", hasGyroscope = false))
  }
}
