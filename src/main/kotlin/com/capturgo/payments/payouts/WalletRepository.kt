package com.capturgo.payments.payouts

import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

data class ContributorWallet(
  val userId: UUID,
  val solanaAddress: String,
  val privyWalletId: String?,
  val verification: String,
  val verifiedAt: Instant?,
)

@Repository
class WalletRepository(private val jdbc: JdbcTemplate) {

  fun upsert(userId: UUID, address: String, privyWalletId: String?, verification: String) {
    jdbc.update(
      """
      INSERT INTO contributor_wallets (user_id, solana_address, privy_wallet_id, verification, verified_at)
      VALUES (?, ?, ?, ?, CASE WHEN ? = 'VERIFIED' THEN now() ELSE NULL END)
      ON CONFLICT (user_id) DO UPDATE SET
        solana_address = EXCLUDED.solana_address,
        privy_wallet_id = COALESCE(EXCLUDED.privy_wallet_id, contributor_wallets.privy_wallet_id),
        verification = EXCLUDED.verification,
        verified_at = CASE WHEN EXCLUDED.verification = 'VERIFIED' THEN now() ELSE contributor_wallets.verified_at END,
        updated_at = now()
      """.trimIndent(),
      userId, address, privyWalletId, verification, verification,
    )
  }

  fun findByUserId(userId: UUID): ContributorWallet? =
    jdbc.query(
      "SELECT user_id, solana_address, privy_wallet_id, verification, verified_at FROM contributor_wallets WHERE user_id = ?",
      { rs, _ ->
        ContributorWallet(
          userId = rs.getObject("user_id", UUID::class.java),
          solanaAddress = rs.getString("solana_address"),
          privyWalletId = rs.getString("privy_wallet_id"),
          verification = rs.getString("verification"),
          verifiedAt = rs.getTimestamp("verified_at")?.toInstant(),
        )
      },
      userId,
    ).firstOrNull()

  fun findAll(): Map<UUID, ContributorWallet> =
    jdbc.query(
      "SELECT user_id, solana_address, privy_wallet_id, verification, verified_at FROM contributor_wallets",
      { rs, _ ->
        ContributorWallet(
          userId = rs.getObject("user_id", UUID::class.java),
          solanaAddress = rs.getString("solana_address"),
          privyWalletId = rs.getString("privy_wallet_id"),
          verification = rs.getString("verification"),
          verifiedAt = rs.getTimestamp("verified_at")?.toInstant(),
        )
      },
    ).associateBy { it.userId }
}
