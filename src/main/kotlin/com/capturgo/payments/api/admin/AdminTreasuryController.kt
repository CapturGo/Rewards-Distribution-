package com.capturgo.payments.api.admin

import com.capturgo.payments.payouts.TreasuryBalanceDto
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
) {

  @GetMapping("/balance")
  fun balance(): TreasuryBalanceDto = TreasuryBalanceDto(
    cluster = cluster,
    treasuryAddress = treasuryAddress.ifBlank { null },
    usdcUnits = null,
    solanaUnits = null,
    enabled = solanaEnabled,
    fetchedAt = Instant.now(),
    note = if (solanaEnabled) null
      else "Solana RPC not configured — balances unavailable until SOLANA_ENABLED=true with devnet config",
  )
}
