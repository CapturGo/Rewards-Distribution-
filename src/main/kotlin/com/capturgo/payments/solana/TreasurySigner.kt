package com.capturgo.payments.solana

import org.p2p.solanaj.core.Account
import org.p2p.solanaj.core.PublicKey
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Loads the treasury keypair from configuration (Secret Manager in deployed
 * envs). Operates on devnet or mainnet-beta — mainnet payouts move real USDC,
 * so the cluster must be set deliberately via SOLANA_CLUSTER.
 */
@Component
class TreasurySigner(
  @Value("\${payments.solana.enabled:false}") private val enabled: Boolean,
  @Value("\${payments.solana.cluster:devnet}") private val cluster: String,
  @Value("\${payments.solana.treasury-secret-key:}") private val secretKeyBase58: String,
  @Value("\${payments.solana.treasury-address:}") private val treasuryAddress: String,
) {
  val active: Boolean get() = enabled && (cluster == "devnet" || cluster == "mainnet-beta") && secretKeyBase58.isNotBlank()

  private val account: Account? by lazy {
    if (!active) return@lazy null
    try {
      Account.fromBase58PrivateKey(secretKeyBase58)
    } catch (e: Exception) {
      throw SolanaException("Treasury keypair could not be loaded", e)
    }
  }

  val address: String get() = account?.publicKeyBase58 ?: treasuryAddress

  fun requireAccount(): Account =
    account ?: throw SolanaException(
      "Treasury signer not active (enabled=$enabled, cluster=$cluster, keyConfigured=${secretKeyBase58.isNotBlank()})",
    )

  /** Derive the treasury's USDC associated token account for a mint. */
  fun usdcTokenAccount(mint: PublicKey): PublicKey {
    val seeds = listOf(
      requireAccount().publicKey.toByteArray(),
      SolanaRpcClient.TOKEN_PROGRAM_ID.toByteArray(),
      mint.toByteArray(),
    )
    return PublicKey.findProgramAddress(seeds, SolanaRpcClient.ATA_PROGRAM_ID).address
  }

  /** Derive a recipient wallet's USDC ATA for a mint. */
  fun recipientTokenAccount(owner: PublicKey, mint: PublicKey): PublicKey {
    val seeds = listOf(
      owner.toByteArray(),
      SolanaRpcClient.TOKEN_PROGRAM_ID.toByteArray(),
      mint.toByteArray(),
    )
    return PublicKey.findProgramAddress(seeds, SolanaRpcClient.ATA_PROGRAM_ID).address
  }
}
