package com.capturgo.payments.rewards

import com.capturgo.payments.capturapi.CapturSubmission
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Immutable snapshot of device/sensor evidence captured at reward-sync time.
 * Read-only after creation — never updated by application code.
 */
data class DeviceEvidence(
  val id: UUID,
  val submissionId: UUID,
  val capturUserId: UUID,
  val deviceModel: String?,
  val syncTier: String?,
  val imuFrequencyHz: Double?,
  val gyroSampleCount: Int?,
  val accelSampleCount: Int?,
  val gyroStreamUrl: String?,
  val accelStreamUrl: String?,
  val videoUrl: String?,
  val durationSeconds: Int?,
  val metadataUrls: String?,       // JSONB stored as string
  val rawMetadata: String?,         // JSONB stored as string
  val evidenceStatus: String,
  val createdAt: Instant?,
)

/**
 * Persistence for submission_device_evidence. Insert is idempotent
 * (ON CONFLICT DO NOTHING on submission_id). No updates — evidence
 * is an immutable audit snapshot.
 */
@Repository
class DeviceEvidenceRepository(
  private val jdbc: JdbcTemplate,
  private val objectMapper: ObjectMapper,
) {

  /**
   * Persist device evidence extracted from a [CapturSubmission].
   * Returns true if a new row was created, false if one already existed.
   */
  /**
   * NESTED propagation: runs in a savepoint inside the caller's sync
   * transaction. A failed evidence insert rolls back only to the savepoint —
   * it must NOT abort the surrounding reward-sync transaction (Postgres
   * aborts the whole tx on any error otherwise).
   */
  @Transactional(propagation = Propagation.NESTED)
  fun insertIfAbsent(
    submissionId: UUID,
    capturUserId: UUID,
    sub: CapturSubmission,
  ): Boolean {
    val metadataUrlsJson = sub.metadataUrls?.let { objectMapper.writeValueAsString(it) }
    val rawMetadataJson = sub.metadata?.let { objectMapper.writeValueAsString(it) }

    val inserted = jdbc.update(
      """
      INSERT INTO submission_device_evidence
        (submission_id, captur_user_id, device_model, sync_tier,
         imu_frequency_hz, gyro_sample_count, accel_sample_count,
         gyro_stream_url, accel_stream_url,
         video_url, duration_seconds,
         metadata_urls, raw_metadata)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
      ON CONFLICT (submission_id) DO NOTHING
      """.trimIndent(),
      submissionId, capturUserId,
      sub.deviceModel, sub.syncTier,
      sub.imuFrequencyHz, sub.gyroSampleCount, sub.accelSampleCount,
      sub.gyroStreamUrl, sub.accelStreamUrl,
      sub.videoUrl, sub.durationSeconds,
      metadataUrlsJson, rawMetadataJson,
    )
    return inserted > 0
  }

  fun findBySubmissionId(submissionId: UUID): DeviceEvidence? =
    jdbc.query(
      """
      SELECT id, submission_id, captur_user_id, device_model, sync_tier,
             imu_frequency_hz, gyro_sample_count, accel_sample_count,
             gyro_stream_url, accel_stream_url,
             video_url, duration_seconds,
             metadata_urls::text, raw_metadata::text,
             evidence_status, created_at
      FROM submission_device_evidence WHERE submission_id = ?
      """.trimIndent(),
      { rs, _ ->
        DeviceEvidence(
          id = rs.getObject("id", UUID::class.java),
          submissionId = rs.getObject("submission_id", UUID::class.java),
          capturUserId = rs.getObject("captur_user_id", UUID::class.java),
          deviceModel = rs.getString("device_model"),
          syncTier = rs.getString("sync_tier"),
          // pgjdbc getObject doesn't support Kotlin boxed types — use getX+wasNull
          imuFrequencyHz = rs.getDouble("imu_frequency_hz").let { if (rs.wasNull()) null else it },
          gyroSampleCount = rs.getInt("gyro_sample_count").let { if (rs.wasNull()) null else it },
          accelSampleCount = rs.getInt("accel_sample_count").let { if (rs.wasNull()) null else it },
          gyroStreamUrl = rs.getString("gyro_stream_url"),
          accelStreamUrl = rs.getString("accel_stream_url"),
          videoUrl = rs.getString("video_url"),
          durationSeconds = rs.getInt("duration_seconds").let { if (rs.wasNull()) null else it },
          metadataUrls = rs.getString("metadata_urls"),
          rawMetadata = rs.getString("raw_metadata"),
          evidenceStatus = rs.getString("evidence_status"),
          createdAt = rs.getTimestamp("created_at")?.toInstant(),
        )
      },
      submissionId,
    ).firstOrNull()

  fun countByStatus(status: String): Long =
    jdbc.queryForObject(
      "SELECT COUNT(*) FROM submission_device_evidence WHERE evidence_status = ?",
      Long::class.java, status,
    ) ?: 0
}
