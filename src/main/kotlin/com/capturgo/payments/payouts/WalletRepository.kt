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

  /** Payout-wallet coverage across rewards that are payable or reserved. */
  data class Coverage(
    val payableRewards: Long,
    val coveredRewards: Long,
    val uncoveredRewards: Long,
    val uncoveredUsers: Long,
    val uncoveredUsdcUnits: Long,
  )

  fun coverage(): Coverage =
    jdbc.queryForObject(
      """
      SELECT count(*) AS payable_rewards,
             count(*) FILTER (WHERE w.verification = 'VERIFIED') AS covered_rewards,
             count(*) FILTER (WHERE w.verification IS DISTINCT FROM 'VERIFIED') AS uncovered_rewards,
             count(DISTINCT l.captur_user_id) FILTER (WHERE w.verification IS DISTINCT FROM 'VERIFIED') AS uncovered_users,
             COALESCE(sum(l.amount_usdc_units) FILTER (WHERE w.verification IS DISTINCT FROM 'VERIFIED'), 0) AS uncovered_usdc_units
      FROM usdc_reward_ledger l
      LEFT JOIN contributor_wallets w ON w.user_id = l.captur_user_id
      WHERE l.status IN ('AVAILABLE', 'RESERVED_FOR_PAYOUT')
      """.trimIndent(),
    ) { rs, _ ->
      Coverage(
        payableRewards = rs.getLong("payable_rewards"),
        coveredRewards = rs.getLong("covered_rewards"),
        uncoveredRewards = rs.getLong("uncovered_rewards"),
        uncoveredUsers = rs.getLong("uncovered_users"),
        uncoveredUsdcUnits = rs.getLong("uncovered_usdc_units"),
      )
    } ?: Coverage(0, 0, 0, 0, 0)

  /** Payable rewards whose user has no VERIFIED wallet — the payout blockers. */
  fun uncoveredPayableRewards(limit: Int): List<UncoveredReward> =
    jdbc.query(
      """
      SELECT l.captur_user_id, count(*) AS reward_count,
             sum(l.amount_usdc_units) AS usdc_units,
             COALESCE(w.verification, 'MISSING') AS wallet_state
      FROM usdc_reward_ledger l
      LEFT JOIN contributor_wallets w ON w.user_id = l.captur_user_id
      WHERE l.status IN ('AVAILABLE', 'RESERVED_FOR_PAYOUT')
        AND w.verification IS DISTINCT FROM 'VERIFIED'
      GROUP BY l.captur_user_id, w.verification
      ORDER BY usdc_units DESC
      LIMIT ?
      """.trimIndent(),
      { rs, _ ->
        UncoveredReward(
          capturUserId = rs.getObject("captur_user_id", UUID::class.java),
          rewardCount = rs.getLong("reward_count"),
          usdcUnits = rs.getLong("usdc_units"),
          walletState = rs.getString("wallet_state"),
        )
      },
      limit,
    )

  data class UncoveredReward(
    val capturUserId: UUID,
    val rewardCount: Long,
    val usdcUnits: Long,
    val walletState: String,
  )
}
