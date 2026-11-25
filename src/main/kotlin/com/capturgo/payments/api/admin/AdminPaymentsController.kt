package com.capturgo.payments.api.admin

import com.capturgo.payments.capturapi.CapturApiClient
import com.capturgo.payments.core.auth.AuthContext
import com.capturgo.payments.core.auth.AuthPrincipal
import com.capturgo.payments.core.exception.UnauthorizedException
import com.capturgo.payments.payouts.BatchDetailDto
import com.capturgo.payments.payouts.CreateBatchRequest
import com.capturgo.payments.payouts.PayoutBatch
import com.capturgo.payments.payouts.PayoutService
import com.capturgo.payments.payouts.PaymentHistoryRow
import com.capturgo.payments.payouts.usdc
import com.capturgo.payments.rewards.ReadyRewardDto
import com.capturgo.payments.rewards.RewardRepository
import com.capturgo.payments.rewards.RewardSyncResult
import com.capturgo.payments.rewards.RewardSyncService
import com.capturgo.payments.rewards.usdcUnits
import jakarta.servlet.http.HttpServletRequest
import java.math.BigDecimal
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/payments")
class AdminPaymentsController(
  private val payoutService: PayoutService,
  private val rewardSyncService: RewardSyncService,
  private val rewardRepository: RewardRepository,
  private val walletLookup: com.capturgo.payments.payouts.WalletRepository,
  private val capturApiClient: CapturApiClient,
) {

  private fun principal(req: HttpServletRequest): AuthPrincipal =
    req.getAttribute(AuthContext.PRINCIPAL_ATTRIBUTE) as? AuthPrincipal
      ?: throw UnauthorizedException()

  private fun token(req: HttpServletRequest): String = principal(req).rawToken

  // ---------- rewards ----------

  @GetMapping("/ready")
  fun ready(
    @RequestParam(defaultValue = "50") limit: Int,
    @RequestParam(defaultValue = "0") offset: Int,
  ): Map<String, Any> {
    val rows = rewardRepository.listReady(limit.coerceIn(1, 200), offset.coerceAtLeast(0))
    val wallets = walletLookup.findAll()
    val items = rows.map { r ->
      val w = wallets[r.capturUserId]
      ReadyRewardDto(
        rewardId = r.id,
        submissionId = r.submissionId,
        userId = r.capturUserId,
        username = null,
        email = null,
        solanaAddress = w?.solanaAddress,
        walletStatus = w?.verification ?: "MISSING",
        amountUsdc = usdcUnits(r.amountUsdcUnits),
        amountUsdcUnits = r.amountUsdcUnits,
        activityTitle = r.activityTitle,
        createdAt = r.createdAt,
      )
    }
    return mapOf(
      "items" to items,
      "totalItems" to rewardRepository.countByStatus("AVAILABLE"),
      "limit" to limit,
      "offset" to offset,
    )
  }

  @PostMapping("/sync")
  fun sync(req: HttpServletRequest): RewardSyncResult =
    rewardSyncService.sync(token(req))

  // ---------- batches ----------

  @PostMapping("/batches")
  fun createBatch(
    @RequestBody body: CreateBatchRequest,
    req: HttpServletRequest,
  ): ResponseEntity<PayoutBatch> {
    val batch = payoutService.createBatch(
      body, principal(req), req.remoteAddr, req.getHeader("User-Agent"),
    )
    return ResponseEntity.status(HttpStatus.CREATED).body(batch)
  }

  @GetMapping("/batches")
  fun listBatches(
    @RequestParam(defaultValue = "50") limit: Int,
    @RequestParam(defaultValue = "0") offset: Int,
    @RequestParam(required = false) status: String?,
  ): Map<String, Any> {
    val (items, total) = payoutService.listBatches(limit.coerceIn(1, 200), offset.coerceAtLeast(0), status)
    return mapOf("items" to items, "totalItems" to total, "limit" to limit, "offset" to offset)
  }

  @GetMapping("/batches/{id}")
  fun getBatch(@PathVariable id: UUID): BatchDetailDto = payoutService.getBatch(id)

  @PostMapping("/batches/{id}/approve")
  fun approveBatch(@PathVariable id: UUID, req: HttpServletRequest): PayoutBatch =
    payoutService.approveBatch(id, principal(req), req.remoteAddr, req.getHeader("User-Agent"))

  @PostMapping("/batches/{id}/cancel")
  fun cancelBatch(@PathVariable id: UUID, req: HttpServletRequest): PayoutBatch =
    payoutService.cancelBatch(id, principal(req), req.remoteAddr, req.getHeader("User-Agent"))

  @PostMapping("/batches/{id}/execute")
  fun executeBatch(@PathVariable id: UUID, req: HttpServletRequest): PayoutBatch =
    payoutService.executeBatch(id, principal(req))

  // ---------- history ----------

  @GetMapping
  fun payments(
    @RequestParam(defaultValue = "50") limit: Int,
    @RequestParam(defaultValue = "0") offset: Int,
    @RequestParam(required = false) status: String?,
    @RequestParam(required = false) userId: UUID?,
  ): Map<String, Any> {
    val (items, total) = payoutService.paymentHistory(
      limit.coerceIn(1, 200), offset.coerceAtLeast(0), status, userId,
    )
    return mapOf("items" to items, "totalItems" to total, "limit" to limit, "offset" to offset)
  }

  /** Top contributors by earned USDC — live from usdc_reward_ledger. */
  @GetMapping("/top-earners")
  fun topEarners(
    req: HttpServletRequest,
    @RequestParam(defaultValue = "10") limit: Int,
  ): Map<String, Any> {
    val token = token(req)
    val rows = rewardRepository.topEarners(limit.coerceIn(1, 50))
    val items = rows.mapIndexed { i, row ->
      val profile = capturApiClient.getUser(token, row.capturUserId)
      val totalUsdc = BigDecimal(row.totalUsdcUnits).movePointLeft(6)
      val avgUsdc = if (row.clips > 0) totalUsdc.divide(BigDecimal(row.clips), 6, java.math.RoundingMode.HALF_UP) else BigDecimal.ZERO
      mapOf(
        "rank" to i + 1,
        "userId" to row.capturUserId.toString(),
        "username" to (profile?.username ?: profile?.displayName ?: ""),
        "email" to (profile?.email ?: ""),
        "totalUsdc" to totalUsdc.toPlainString(),
        "totalUsdcUnits" to row.totalUsdcUnits,
        "clips" to row.clips,
        "avgUsdc" to avgUsdc.toPlainString(),
      )
    }
    return mapOf("items" to items)
  }

  @GetMapping("/{id}")
  fun paymentDetail(@PathVariable id: UUID): Map<String, Any> =
    mapOf("message" to "payment detail not yet implemented", "id" to id.toString())
}
