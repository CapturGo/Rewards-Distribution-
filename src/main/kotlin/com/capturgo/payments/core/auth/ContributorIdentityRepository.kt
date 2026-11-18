package com.capturgo.payments.core.auth

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Persistent Privy DID → captur-api user_id mapping. Rows are written only
 * after captur-api confirms the token owns the claimed userId.
 */
@Repository
class ContributorIdentityRepository(private val jdbc: JdbcTemplate) {

  fun findCapturUserId(privyUserId: String): UUID? =
    jdbc.query(
      "SELECT captur_user_id FROM contributor_identities WHERE privy_user_id = ?",
      { rs, _ -> rs.getObject("captur_user_id", UUID::class.java) },
      privyUserId,
    ).firstOrNull()

  fun upsert(privyUserId: String, capturUserId: UUID) {
    jdbc.update(
      """
      INSERT INTO contributor_identities (privy_user_id, captur_user_id, verified_at)
      VALUES (?, ?, now())
      ON CONFLICT (privy_user_id) DO UPDATE SET
        captur_user_id = EXCLUDED.captur_user_id,
        verified_at = now(),
        updated_at = now()
      """.trimIndent(),
      privyUserId, capturUserId,
    )
  }
}
