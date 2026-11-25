package com.capturgo.payments.api.admin

import com.capturgo.payments.payouts.TreasuryBalanceDto
import com.capturgo.payments.solana.SolanaRpcClient
import java.time.Instant
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/treasury")
class AdminTreasuryController(
  @Value("\${payments.solana.enabled:false}") private val solanaEnabled: Boolean,
  @Value("\${payments.solana.cluster:devnet}") private val cluster: String,
  @Value("\${payments.solana.treasury-address:}") private val treasuryAddress: String,
  @Value("\${payments.solana.usdc-mint:}") private val usdcMint: String,
  private val solanaRpc: SolanaRpcClient,
) {

  @GetMapping("/balance")
  fun balance(): TreasuryBalanceDto {
    var usdcUnits: Long? = null
    var solanaUnits: Long? = null
    var note: String? = null

    when {
      !solanaEnabled ->
        note = "Solana RPC not configured — balances unavailable until SOLANA_ENABLED=true"
      treasuryAddress.isBlank() ->
        note = "SOLANA_TREASURY_ADDRESS not configured"
      !solanaRpc.configured ->
        note = "SOLANA_RPC_URL not configured — on-chain balances unavailable"
      else -> {
        runCatching { solanaRpc.getSolBalanceLamports(treasuryAddress) }
          .onSuccess { solanaUnits = it }
          .onFailure { note = "SOL balance lookup failed: ${it.message}" }
        if (usdcMint.isBlank()) {
          note = (note ?: "") + " SOLANA_USDC_MINT not configured".trim()
        } else {
          runCatching { solanaRpc.getSplTokenBalance(treasuryAddress, usdcMint) }
            .onSuccess { usdcUnits = it }
            .onFailure {
              note = listOfNotNull(note, "USDC balance lookup failed: ${it.message}")
                .joinToString("; ")
            }
        }
      }
    }

    return TreasuryBalanceDto(
      cluster = cluster,
      treasuryAddress = treasuryAddress.ifBlank { null },
      usdcUnits = usdcUnits,
      solanaUnits = solanaUnits,
      enabled = solanaEnabled,
      fetchedAt = Instant.now(),
      note = note?.ifBlank { null },
    )
  }
}
