package com.capturgo.payments.solana

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Selects the payout transport. `payments.payout.provider`:
 *  - `keypair` (default) — [UsdcPayoutExecutor], raw Solana keypair.
 *  - `privy` — [PrivyPayoutExecutor], Privy Wallet Actions API.
 *
 * Both executors remain Spring components; only the injected
 * [PayoutExecutor] bean changes. Reward ledger, batch approval,
 * eligibility and reconciliation logic are identical either way.
 */
@Configuration
class PayoutExecutorConfiguration(
  private val keypairExecutor: UsdcPayoutExecutor,
  private val privyExecutor: PrivyPayoutExecutor,
  @Value("\${payments.payout.provider:keypair}") private val provider: String,
) {
  // @Primary: both executor components are themselves PayoutExecutor beans,
  // so the selector must win for interface injection points.
  @Bean
  @Primary
  fun payoutExecutor(): PayoutExecutor = when (provider.lowercase()) {
    "privy" -> privyExecutor
    else -> keypairExecutor
  }
}
