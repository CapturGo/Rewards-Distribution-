package com.capturgo.payments.rewards

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

data class DeviceCapability(
  val id: UUID,
  val deviceKey: String,
  val deviceModel: String?,
  val tier: String,
  val verification: String,
  val source: String,
)

@Repository
class DeviceCapabilityRepository(private val jdbc: JdbcTemplate) {

  /**
   * Look up a device by normalized model string. Returns null when the device
   * is unknown — callers must treat null as INELIGIBLE, never auto-BASIC
   * (product decision: unverified devices get no automatic payout).
   */
  fun findByDeviceKey(deviceKey: String): DeviceCapability? =
    jdbc.query(
      """
      SELECT id, device_key, device_model, tier, verification, source
      FROM device_capabilities WHERE device_key = ?
      """.trimIndent(),
      { rs, _ ->
        DeviceCapability(
          id = rs.getObject("id", UUID::class.java),
          deviceKey = rs.getString("device_key"),
          deviceModel = rs.getString("device_model"),
          tier = rs.getString("tier"),
          verification = rs.getString("verification"),
          source = rs.getString("source"),
        )
      },
      deviceKey,
    ).firstOrNull()

  fun upsert(
    deviceKey: String,
    deviceModel: String?,
    tier: String,
    hasGyro: Boolean?,
    hasAccel: Boolean?,
    imuHz: Int?,
    fov: Int?,
    ar169: Boolean?,
    fps: Int?,
    verification: String,
    source: String,
    notes: String?,
  ) {
    jdbc.update(
      """
      INSERT INTO device_capabilities
        (device_key, device_model, tier, has_gyroscope, has_accelerometer,
         imu_frequency_hz, camera_fov_degrees, aspect_ratio_169, fps,
         verification, source, notes)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (device_key) DO UPDATE SET
        device_model = EXCLUDED.device_model,
        tier = EXCLUDED.tier,
        has_gyroscope = EXCLUDED.has_gyroscope,
        has_accelerometer = EXCLUDED.has_accelerometer,
        imu_frequency_hz = EXCLUDED.imu_frequency_hz,
        camera_fov_degrees = EXCLUDED.camera_fov_degrees,
        aspect_ratio_169 = EXCLUDED.aspect_ratio_169,
        fps = EXCLUDED.fps,
        verification = EXCLUDED.verification,
        source = EXCLUDED.source,
        notes = EXCLUDED.notes,
        updated_at = now()
      """.trimIndent(),
      deviceKey, deviceModel, tier, hasGyro, hasAccel, imuHz, fov, ar169, fps,
      verification, source, notes,
    )
  }

  fun listAll(): List<DeviceCapability> =
    jdbc.query(
      "SELECT id, device_key, device_model, tier, verification, source FROM device_capabilities ORDER BY device_key",
      { rs, _ ->
        DeviceCapability(
          id = rs.getObject("id", UUID::class.java),
          deviceKey = rs.getString("device_key"),
          deviceModel = rs.getString("device_model"),
          tier = rs.getString("tier"),
          verification = rs.getString("verification"),
          source = rs.getString("source"),
        )
      },
    )
}
