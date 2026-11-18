package com.capturgo.payments.migration

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.DriverManager
import java.util.UUID
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

/**
 * Runs the real Flyway migration against an embedded PostgreSQL and
 * verifies schema constraints that guarantee payment idempotency.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlywayMigrationTest {

  private lateinit var pg: EmbeddedPostgres
  private lateinit var jdbc: JdbcTemplate

  @BeforeAll
  fun start() {
    pg = EmbeddedPostgres.start()
    val ds = DriverManagerDataSource(pg.getJdbcUrl("postgres", "postgres"), "postgres", "postgres")
    Flyway.configure()
      .dataSource(ds)
      .locations("classpath:db/migration")
      .load()
      .migrate()
    jdbc = JdbcTemplate(ds)
  }

  @AfterAll
  fun stop() {
    pg.close()
  }

  @Test
  fun `migration creates all service tables`() {
    val tables = jdbc.queryForList(
      "SELECT table_name FROM information_schema.tables WHERE table_schema='public' ORDER BY table_name",
      String::class.java,
    )
    listOf(
      "contributor_wallets", "usdc_reward_ledger", "payout_batches",
      "payout_batch_items", "payout_transactions", "payout_audit_logs",
      "poller_checkpoints", "device_capabilities", "contributor_identities",
      "submission_device_evidence",
      "flyway_schema_history",
    ).forEach { assertTrue(tables.contains(it), "missing table $it") }
  }

  @Test
  fun `duplicate submission_id is rejected - prevents double rewards`() {
    val subId = UUID.randomUUID()
    val userId = UUID.randomUUID()
    insertReward(subId, userId)
    val ex = assertThrows(DataAccessException::class.java) { insertReward(subId, userId) }
    assertTrue(ex.message!!.contains("submission_id"))
  }

  @Test
  fun `reward cannot be negative or zero`() {
    assertThrows(DataAccessException::class.java) {
      jdbc.update(
        """INSERT INTO usdc_reward_ledger
           (submission_id, captur_user_id, amount_usdc_units)
           VALUES (?, ?, 0)""",
        UUID.randomUUID(), UUID.randomUUID(),
      )
    }
  }

  @Test
  fun `same reward cannot appear in two payout batches`() {
    val batchId = insertBatch("key-a")
    val rewardId = insertRewardAndGetId()
    jdbc.update(
      """INSERT INTO payout_batch_items (batch_id, reward_id, captur_user_id, solana_address, amount_usdc_units)
         VALUES (?, ?, ?, 'So11111111111111111111111111111111111111112', 5000000)""",
      batchId, rewardId, UUID.randomUUID(),
    )
    val batch2 = insertBatch("key-b")
    assertThrows(DataAccessException::class.java) {
      jdbc.update(
        """INSERT INTO payout_batch_items (batch_id, reward_id, captur_user_id, solana_address, amount_usdc_units)
           VALUES (?, ?, ?, 'So11111111111111111111111111111111111111112', 5000000)""",
        batch2, rewardId, UUID.randomUUID(),
      )
    }
  }

  @Test
  fun `idempotency key is unique per batch`() {
    insertBatch("same-key")
    assertThrows(DataAccessException::class.java) { insertBatch("same-key") }
  }

  @Test
  fun `invalid status values are rejected`() {
    assertThrows(DataAccessException::class.java) {
      jdbc.update(
        "INSERT INTO payout_batches (status,total_usdc_units,item_count,cluster,idempotency_key,created_by) VALUES ('BOGUS',1,1,'devnet','k1',?)",
        UUID.randomUUID(),
      )
    }
  }

  @Test
  fun `batch execution claim is atomic - second claim fails`() {
    val batchId = insertBatch("claim-key")
    val repo = com.capturgo.payments.payouts.PayoutRepository(jdbc)
    // move to APPROVED first
    jdbc.update("UPDATE payout_batches SET status='APPROVED' WHERE id=?", batchId)
    assertTrue(repo.claimBatchForExecution(batchId))
    // second concurrent claim must lose
    org.junit.jupiter.api.Assertions.assertFalse(repo.claimBatchForExecution(batchId))
    // and a non-approved batch can never be claimed
    val b2 = insertBatch("claim-key-2")
    org.junit.jupiter.api.Assertions.assertFalse(repo.claimBatchForExecution(b2))
  }

  @Test
  fun `reward sums are per-user - balances never leak across contributors`() {
    val userA = UUID.randomUUID()
    val userB = UUID.randomUUID()
    insertReward(UUID.randomUUID(), userA)
    insertReward(UUID.randomUUID(), userB)
    val repo = com.capturgo.payments.rewards.RewardRepository(jdbc)
    assertEquals(5_000_000L, repo.sumUnitsForUser(userA, "AVAILABLE"))
    assertEquals(5_000_000L, repo.sumUnitsForUser(userB, "AVAILABLE"))
    // user C has nothing
    assertEquals(0L, repo.sumUnitsForUser(UUID.randomUUID(), "AVAILABLE"))
  }

  @Test
  fun `contributor identity mapping is unique per privy user`() {
    jdbc.update(
      "INSERT INTO contributor_identities (privy_user_id, captur_user_id) VALUES ('did:privy:t1', ?)",
      UUID.randomUUID(),
    )
    assertThrows(DataAccessException::class.java) {
      jdbc.update(
        "INSERT INTO contributor_identities (privy_user_id, captur_user_id) VALUES ('did:privy:t1', ?)",
        UUID.randomUUID(),
      )
    }
  }

  // ── Phase B: submission_device_evidence ──

  @Test
  fun `evidence table enforces unique submission_id`() {
    val subId = UUID.randomUUID()
    val userId = UUID.randomUUID()
    insertReward(subId, userId)
    insertEvidence(subId, userId)
    assertThrows(DataAccessException::class.java) {
      insertEvidence(subId, userId)
    }
  }

  @Test
  fun `evidence cannot exist without matching reward row`() {
    val orphanSubId = UUID.randomUUID()
    assertThrows(DataAccessException::class.java) {
      insertEvidence(orphanSubId, UUID.randomUUID())
    }
  }

  @Test
  fun `evidence invalid status is rejected`() {
    val subId = UUID.randomUUID()
    val userId = UUID.randomUUID()
    insertReward(subId, userId)
    assertThrows(DataAccessException::class.java) {
      jdbc.update(
        """INSERT INTO submission_device_evidence
           (submission_id, captur_user_id, evidence_status)
           VALUES (?, ?, 'BOGUS')""",
        subId, userId,
      )
    }
  }

  @Test
  fun `evidence stores sensor fields correctly`() {
    val subId = UUID.randomUUID()
    val userId = UUID.randomUUID()
    insertReward(subId, userId)
    jdbc.update(
      """INSERT INTO submission_device_evidence
         (submission_id, captur_user_id, device_model, sync_tier,
          imu_frequency_hz, gyro_sample_count, accel_sample_count,
          gyro_stream_url, accel_stream_url, duration_seconds)
         VALUES (?, ?, 'Pixel 8', 'GOLD', 200, 12000, 12000,
                 'https://gyro.example', 'https://accel.example', 60)""",
      subId, userId,
    )
    val row = jdbc.queryForMap(
      "SELECT * FROM submission_device_evidence WHERE submission_id = ?", subId,
    )
    assertEquals("GOLD", row["sync_tier"])
    assertEquals(200.0, (row["imu_frequency_hz"] as Number).toDouble(), 0.001)
    assertEquals(12000, row["gyro_sample_count"])
    assertEquals(12000, row["accel_sample_count"])
    assertEquals("CAPTURED", row["evidence_status"])
  }

  @Test
  fun `device evidence persists per submission and is idempotent`() {
    val subId = UUID.randomUUID()
    val userId = UUID.randomUUID()
    // evidence FK requires the reward ledger row first
    jdbc.update(
      """INSERT INTO usdc_reward_ledger (submission_id, captur_user_id, amount_usdc_units)
         VALUES (?, ?, 31250)""",
      subId, userId,
    )
    val repo = com.capturgo.payments.rewards.DeviceEvidenceRepository(
      jdbc, com.fasterxml.jackson.databind.ObjectMapper(),
    )
    val sub = com.capturgo.payments.capturapi.CapturSubmission(
      id = subId, activityId = UUID.randomUUID(), userId = userId,
      status = "APPROVED", durationSeconds = 30, deviceModel = "Pixel 8",
      syncTier = "GOLD", imuFrequencyHz = 200.0,
      gyroSampleCount = 6000, accelSampleCount = 6000,
      gyroStreamUrl = "https://x/gyro", accelStreamUrl = null,
      metadataUrls = mapOf("m" to "https://x/m"),
      metadata = mapOf("v" to "2.1"),
    )
    assertTrue(repo.insertIfAbsent(subId, userId, sub))
    // second insert for same submission is a no-op
    org.junit.jupiter.api.Assertions.assertFalse(repo.insertIfAbsent(subId, userId, sub))

    val ev = repo.findBySubmissionId(subId)!!
    assertEquals("Pixel 8", ev.deviceModel)
    assertEquals("GOLD", ev.syncTier)
    assertEquals(200.0, ev.imuFrequencyHz!!, 0.001)
    assertEquals(6000, ev.gyroSampleCount)
    assertEquals(6000, ev.accelSampleCount)
    assertEquals("https://x/gyro", ev.gyroStreamUrl)
    assertEquals(null, ev.accelStreamUrl)
    assertEquals("CAPTURED", ev.evidenceStatus)
    assertTrue(ev.metadataUrls!!.contains("https://x/m"))
    assertTrue(ev.rawMetadata!!.contains("2.1"))
  }

  @Test
  fun `evidence cannot exist without a reward row - FK enforced`() {
    val repo = com.capturgo.payments.rewards.DeviceEvidenceRepository(
      jdbc, com.fasterxml.jackson.databind.ObjectMapper(),
    )
    val orphan = com.capturgo.payments.capturapi.CapturSubmission(
      id = UUID.randomUUID(), activityId = UUID.randomUUID(),
      userId = UUID.randomUUID(), status = "APPROVED",
    )
    assertThrows(DataAccessException::class.java) {
      repo.insertIfAbsent(orphan.id, orphan.userId, orphan)
    }
  }

  private fun insertBatch(key: String): UUID {
    jdbc.update(
      "INSERT INTO payout_batches (status,total_usdc_units,item_count,cluster,idempotency_key,created_by) VALUES ('PENDING_APPROVAL',5000000,1,'devnet',?,?)",
      key, UUID.randomUUID(),
    )
    return jdbc.queryForObject("SELECT id FROM payout_batches WHERE idempotency_key=?", UUID::class.java, key)!!
  }

  private fun insertReward(submissionId: UUID, userId: UUID) {
    jdbc.update(
      """INSERT INTO usdc_reward_ledger (submission_id, captur_user_id, amount_usdc_units)
         VALUES (?, ?, 5000000)""",
      submissionId, userId,
    )
  }

  private fun insertRewardAndGetId(): UUID {
    val subId = UUID.randomUUID()
    insertReward(subId, UUID.randomUUID())
    return jdbc.queryForObject(
      "SELECT id FROM usdc_reward_ledger WHERE submission_id=?", UUID::class.java, subId,
    )!!
  }

  private fun insertEvidence(submissionId: UUID, userId: UUID) {
    jdbc.update(
      """INSERT INTO submission_device_evidence
         (submission_id, captur_user_id, device_model)
         VALUES (?, ?, 'Test Device')""",
      submissionId, userId,
    )
  }
}
