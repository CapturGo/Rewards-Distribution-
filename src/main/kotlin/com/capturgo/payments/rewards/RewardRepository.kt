package com.capturgo.payments.rewards

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class RewardRepository(private val jdbc: JdbcTemplate) {

  /** Insert a reward; returns false when submission_id already exists (idempotent). */
  fun insertIfAbsent(
    submissionId: UUID,
    capturUserId: UUID,
    activityId: UUID?,
    activityTitle: String?,
    amountUsdcUnits: Long,
    metadataJson: String?,
    deviceTier: String? = null,
    deviceModel: String? = null,
    hourlyRateUsdcUnits: Long? = null,
    eligibleDurationSeconds: Int? = null,
    calculationVersion: String? = null,
  ): Boolean {
    val inserted = jdbc.update(
      """
      INSERT INTO usdc_reward_ledger
        (submission_id, captur_user_id, activity_id, activity_title,
         amount_usdc_units, status, source, metadata,
         device_tier, device_model, hourly_rate_usdc_units,
         eligible_duration_seconds, calculation_version)
      VALUES (?, ?, ?, ?, ?, 'AVAILABLE', 'CAPTUR_API_POLL', ?::jsonb,
              ?, ?, ?, ?, ?)
      ON CONFLICT (submission_id) DO NOTHING
      """.trimIndent(),
      submissionId, capturUserId, activityId, activityTitle,
      amountUsdcUnits, metadataJson,
      deviceTier, deviceModel, hourlyRateUsdcUnits,
      eligibleDurationSeconds, calculationVersion,
    )
    return inserted > 0
  }

  fun findByIds(ids: List<UUID>): List<RewardLedgerRow> {
    if (ids.isEmpty()) return emptyList()
    val placeholders = ids.joinToString(",") { "?" }
    return jdbc.query(
      """
      SELECT id, submission_id, captur_user_id, activity_id, activity_title,
             amount_usdc_units, status, metadata, created_at
      FROM usdc_reward_ledger WHERE id IN ($placeholders)
      """.trimIndent(),
      { rs, _ ->
        RewardLedgerRow(
          id = rs.getObject("id", UUID::class.java),
          submissionId = rs.getObject("submission_id", UUID::class.java),
          capturUserId = rs.getObject("captur_user_id", UUID::class.java),
          activityId = rs.getObject("activity_id", UUID::class.java),
          activityTitle = rs.getString("activity_title"),
          amountUsdcUnits = rs.getLong("amount_usdc_units"),
          status = rs.getString("status"),
          metadata = null,
          createdAt = rs.getTimestamp("created_at")?.toInstant(),
        )
      },
      *ids.toTypedArray(),
    )
  }

  fun findById(id: UUID): RewardLedgerRow? = findByIds(listOf(id)).firstOrNull()

  fun existsBySubmissionId(submissionId: UUID): Boolean =
    jdbc.queryForObject(
      "SELECT COUNT(*) FROM usdc_reward_ledger WHERE submission_id = ?",
      Int::class.java, submissionId,
    )!! > 0

  fun findBySubmissionId(submissionId: UUID): RewardLedgerRow? =
    jdbc.query(
      """
      SELECT id, submission_id, captur_user_id, activity_id, activity_title,
             amount_usdc_units, status, metadata, created_at
      FROM usdc_reward_ledger WHERE submission_id = ?
      """.trimIndent(),
      { rs, _ ->
        RewardLedgerRow(
          id = rs.getObject("id", UUID::class.java),
          submissionId = rs.getObject("submission_id", UUID::class.java),
          capturUserId = rs.getObject("captur_user_id", UUID::class.java),
          activityId = rs.getObject("activity_id", UUID::class.java),
          activityTitle = rs.getString("activity_title"),
          amountUsdcUnits = rs.getLong("amount_usdc_units"),
          status = rs.getString("status"),
          metadata = null,
          createdAt = rs.getTimestamp("created_at")?.toInstant(),
        )
      },
      submissionId,
    ).firstOrNull()

  fun listReady(limit: Int, offset: Int): List<RewardLedgerRow> =
    jdbc.query(
      """
      SELECT id, submission_id, captur_user_id, activity_id, activity_title,
             amount_usdc_units, status, metadata, created_at
      FROM usdc_reward_ledger
      WHERE status = 'AVAILABLE'
      ORDER BY created_at ASC
      LIMIT ? OFFSET ?
      """.trimIndent(),
      { rs, _ ->
        RewardLedgerRow(
          id = rs.getObject("id", UUID::class.java),
          submissionId = rs.getObject("submission_id", UUID::class.java),
          capturUserId = rs.getObject("captur_user_id", UUID::class.java),
          activityId = rs.getObject("activity_id", UUID::class.java),
          activityTitle = rs.getString("activity_title"),
          amountUsdcUnits = rs.getLong("amount_usdc_units"),
          status = rs.getString("status"),
          metadata = null,
          createdAt = rs.getTimestamp("created_at")?.toInstant(),
        )
      },
      limit, offset,
    )

  fun countByStatus(status: String): Long =
    jdbc.queryForObject(
      "SELECT COUNT(*) FROM usdc_reward_ledger WHERE status = ?",
      Long::class.java, status,
    ) ?: 0

  fun sumUnitsByStatus(status: String): Long =
    jdbc.queryForObject(
      "SELECT COALESCE(SUM(amount_usdc_units),0) FROM usdc_reward_ledger WHERE status = ?",
      Long::class.java, status,
    ) ?: 0

  /** Per-user balance — never sums across contributors. */
  fun sumUnitsForUser(userId: UUID, status: String): Long =
    jdbc.queryForObject(
      """SELECT COALESCE(SUM(amount_usdc_units),0) FROM usdc_reward_ledger
         WHERE captur_user_id = ? AND status = ?""",
      Long::class.java, userId, status,
    ) ?: 0

  fun listByUser(capturUserId: UUID, limit: Int, offset: Int): List<RewardLedgerRow> =
    jdbc.query(
      """
      SELECT id, submission_id, captur_user_id, activity_id, activity_title,
             amount_usdc_units, status, metadata, created_at
      FROM usdc_reward_ledger
      WHERE captur_user_id = ?
      ORDER BY created_at DESC
      LIMIT ? OFFSET ?
      """.trimIndent(),
      { rs, _ ->
        RewardLedgerRow(
          id = rs.getObject("id", UUID::class.java),
          submissionId = rs.getObject("submission_id", UUID::class.java),
          capturUserId = rs.getObject("captur_user_id", UUID::class.java),
          activityId = rs.getObject("activity_id", UUID::class.java),
          activityTitle = rs.getString("activity_title"),
          amountUsdcUnits = rs.getLong("amount_usdc_units"),
          status = rs.getString("status"),
          metadata = null,
          createdAt = rs.getTimestamp("created_at")?.toInstant(),
        )
      },
      capturUserId, limit, offset,
    )

  fun countByUser(capturUserId: UUID): Long =
    jdbc.queryForObject(
      "SELECT COUNT(*) FROM usdc_reward_ledger WHERE captur_user_id = ?",
      Long::class.java, capturUserId,
    ) ?: 0

  /** Top contributors by earned USDC (all non-reversed ledger entries). */
  fun topEarners(limit: Int): List<TopEarnerRow> =
    jdbc.query(
      """
      SELECT captur_user_id, COUNT(*) AS clips, SUM(amount_usdc_units) AS total_units
      FROM usdc_reward_ledger
      WHERE status <> 'REVERSED'
      GROUP BY captur_user_id
      ORDER BY total_units DESC
      LIMIT ?
      """.trimIndent(),
      { rs, _ ->
        TopEarnerRow(
          capturUserId = rs.getObject("captur_user_id", UUID::class.java),
          clips = rs.getLong("clips"),
          totalUsdcUnits = rs.getLong("total_units"),
        )
      },
      limit,
    )
}

data class TopEarnerRow(
  val capturUserId: UUID,
  val clips: Long,
  val totalUsdcUnits: Long,
)
