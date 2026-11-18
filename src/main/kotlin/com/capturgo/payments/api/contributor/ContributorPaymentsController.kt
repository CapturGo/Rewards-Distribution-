package com.capturgo.payments.api.contributor

import com.capturgo.payments.core.auth.AuthContext
import com.capturgo.payments.core.auth.AuthPrincipal
import com.capturgo.payments.core.auth.ContributorIdentityService
import com.capturgo.payments.core.exception.UnprocessableException
import com.capturgo.payments.core.exception.UnauthorizedException
import com.capturgo.payments.payouts.PayoutRepository
import com.capturgo.payments.payouts.WalletRepository
import com.capturgo.payments.rewards.RewardRepository
import com.capturgo.payments.rewards.usdcUnits
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class ContributorPaymentsController(
  private val payoutRepository: PayoutRepository,
  private val rewardRepository: RewardRepository,
  private val walletRepository: WalletRepository,
  private val identityService: ContributorIdentityService,
) {

  private fun principal(req: HttpServletRequest): AuthPrincipal =
    req.getAttribute(AuthContext.PRINCIPAL_ATTRIBUTE) as? AuthPrincipal
      ?: throw UnauthorizedException()

  /**
   * Resolve the caller's captur user id via the trusted Privy→captur mapping.
   * `userId` is only a first-time claim: captur-api verifies the token owns it
   * before the mapping is stored. Once mapped, the param is ignored.
   */
  private fun capturUserId(req: HttpServletRequest, claimedUserId: UUID?): UUID {
    val principal = principal(req)
    return identityService.resolve(
      token = principal.rawToken,
      privyUserId = principal.privyUserId,
      verifiedEmail = principal.email,
      claimedUserId = claimedUserId,
    ) ?: throw UnprocessableException(
      "userId is required on first request and must belong to the authenticated account",
    )
  }

  @GetMapping("/payments/history")
  fun paymentHistory(
    req: HttpServletRequest,
    @RequestParam(required = false) userId: UUID?,
  ): Map<String, Any> {
    val uid = capturUserId(req, userId)
    val (items, total) = payoutRepository.paymentHistory(100, 0, null, uid)
    return mapOf("items" to items, "totalItems" to total)
  }

  @GetMapping("/rewards/balance")
  fun rewardsBalance(
    req: HttpServletRequest,
    @RequestParam(required = false) userId: UUID?,
  ): Map<String, Any?> {
    val uid = capturUserId(req, userId)
    val available = rewardRepository.sumUnitsForUser(uid, "AVAILABLE")
    val reserved = rewardRepository.sumUnitsForUser(uid, "RESERVED_FOR_PAYOUT")
    val paid = rewardRepository.sumUnitsForUser(uid, "PAID")
    return mapOf(
      "userId" to uid,
      "availableUsdc" to usdcUnits(available),
      "reservedUsdc" to usdcUnits(reserved),
      "paidUsdc" to usdcUnits(paid),
      "totalEarnedUsdc" to usdcUnits(available + reserved + paid),
    )
  }

  @GetMapping("/wallet")
  fun wallet(
    req: HttpServletRequest,
    @RequestParam(required = false) userId: UUID?,
  ): Map<String, Any?> {
    val uid = capturUserId(req, userId)
    val w = walletRepository.findByUserId(uid)
    return mapOf(
      "userId" to uid,
      "solanaAddress" to w?.solanaAddress,
      "verification" to (w?.verification ?: "MISSING"),
      "verifiedAt" to w?.verifiedAt,
    )
  }
}
