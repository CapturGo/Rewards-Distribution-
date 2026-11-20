package com.capturgo.payments.payouts

import com.capturgo.payments.core.auth.AuthPrincipal
import com.capturgo.payments.core.exception.ConflictException
import com.capturgo.payments.core.exception.FeatureDisabledException
import com.capturgo.payments.core.exception.NotFoundException
import com.capturgo.payments.rewards.RewardRepository
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PayoutService(
  private val payoutRepository: PayoutRepository,
  private val rewardRepository: RewardRepository,
  private val walletRepository: WalletRepository,
  private val objectMapper: ObjectMapper,
  private val payoutExecutionService: PayoutExecutionService,
  @Value("\${payments.solana.enabled:false}") private val solanaEnabled: Boolean,
  @Value("\${payments.solana.cluster:devnet}") private val solanaCluster: String,
) {
  private val logger = LoggerFactory.getLogger(PayoutService::class.java)

  // ---------- batch lifecycle ----------

  @Transactional
  fun createBatch(request: CreateBatchRequest, admin: AuthPrincipal, ip: String?, ua: String?): PayoutBatch {
    if (request.rewardIds.isEmpty()) {
      throw com.capturgo.payments.core.exception.UnprocessableException("rewardIds must not be empty")
    }
    if (request.rewardIds.size > MAX_BATCH_ITEMS) {
      throw com.capturgo.payments.core.exception.UnprocessableException("Batch exceeds max size $MAX_BATCH_ITEMS")
    }

    val idempotencyKey = request.idempotencyKey ?: UUID.randomUUID().toString()

    // Replay-safe: same key returns the existing batch
    payoutRepository.findBatchByIdempotencyKey(idempotencyKey)?.let { existing ->
      return existing
    }

    // Fetch rewards and validate
    val rewards = request.rewardIds.mapNotNull { rewardRepository.findById(it) }
    if (rewards.size != request.rewardIds.size) {
      throw NotFoundException("One or more rewards not found")
    }
    val unavailable = rewards.filter { it.status != "AVAILABLE" }
    if (unavailable.isNotEmpty()) {
      throw ConflictException(
        "Rewards not available for payout: ${unavailable.map { it.id }} " +
          "(statuses: ${unavailable.map { it.status }})",
      )
    }

    val totalUnits = rewards.sumOf { it.amountUsdcUnits }
    if (totalUnits > MAX_BATCH_USDC_UNITS) {
      throw com.capturgo.payments.core.exception.UnprocessableException(
        "Batch total exceeds cap of ${usdc(MAX_BATCH_USDC_UNITS)} USDC",
      )
    }

    val creatorId = admin.capturUserId
      ?: throw com.capturgo.payments.core.exception.ForbiddenException(
        "Admin identity unresolved — cannot create payout batch",
      )
    val batchId = payoutRepository.insertBatch(
      status = "PENDING_APPROVAL",
      totalUnits = totalUnits,
      itemCount = rewards.size,
      cluster = solanaCluster,
      idempotencyKey = idempotencyKey,
      createdBy = creatorId,
    ) ?: throw ConflictException("Batch with this idempotency key already exists")

    // Reserve rewards atomically — only AVAILABLE rows flip
    val reserved = payoutRepository.reserveRewards(request.rewardIds)
    if (reserved != request.rewardIds.size) {
      throw ConflictException("Only $reserved of ${request.rewardIds.size} rewards could be reserved")
    }

    // Create items with wallet snapshots — only VERIFIED wallets may be paid.
    val wallets = walletRepository.findAll()
    for (reward in rewards) {
      val wallet = wallets[reward.capturUserId]
      val address = wallet?.solanaAddress
      if (wallet == null || wallet.verification != "VERIFIED" || address == null ||
        !com.capturgo.payments.rewards.RewardSyncService.isSolanaAddress(address)
      ) {
        throw com.capturgo.payments.core.exception.UnprocessableException(
          "No verified Solana wallet for user ${reward.capturUserId} " +
            "(reward ${reward.id}, walletStatus=${wallet?.verification ?: "MISSING"})",
        )
      }
      val inserted = payoutRepository.insertItem(
        batchId = batchId,
        rewardId = reward.id,
        userId = reward.capturUserId,
        solanaAddress = address,
        amountUnits = reward.amountUsdcUnits,
      )
      if (inserted == null) {
        throw ConflictException("Reward ${reward.id} is already assigned to a payout")
      }
    }

    val batch = payoutRepository.findBatch(batchId)
      ?: throw IllegalStateException("Batch $batchId vanished after insert")

    payoutRepository.audit(
      actorId = admin.capturUserId,
      actorEmail = admin.email,
      action = "BATCH_CREATED",
      entityType = "payout_batch",
      entityId = batchId,
      before = null,
      after = objectMapper.writeValueAsString(batch),
      ip = ip,
      ua = ua,
    )
    logger.info("Batch {} created by {} with {} items, {} units", batchId, admin.email, rewards.size, totalUnits)
    return batch
  }

  @Transactional
  fun approveBatch(batchId: UUID, admin: AuthPrincipal, ip: String?, ua: String?): PayoutBatch {
    val batch = payoutRepository.findBatch(batchId) ?: throw NotFoundException("Batch not found")
    val approverId = admin.capturUserId
      ?: throw com.capturgo.payments.core.exception.ForbiddenException("Approver identity unresolved")
    if (approverId == batch.createdBy) {
      throw com.capturgo.payments.core.exception.ForbiddenException(
        "Approver must differ from batch creator",
      )
    }
    // Atomic transition — cannot overwrite PROCESSING or later states, so an
    // approval can't resurrect a batch mid-execution.
    if (!payoutRepository.approveBatchAtomically(batchId, approverId)) {
      throw ConflictException("Batch is ${batch.status}, cannot approve")
    }
    payoutRepository.audit(
      actorId = approverId, actorEmail = admin.email, action = "BATCH_APPROVED",
      entityType = "payout_batch", entityId = batchId,
      before = objectMapper.writeValueAsString(batch),
      after = """{"status":"APPROVED","approvedBy":"$approverId"}""",
      ip = ip, ua = ua,
    )
    return payoutRepository.findBatch(batchId)!!
  }

  @Transactional
  fun cancelBatch(batchId: UUID, admin: AuthPrincipal, ip: String?, ua: String?): PayoutBatch {
    val batch = payoutRepository.findBatch(batchId) ?: throw NotFoundException("Batch not found")
    // Atomic claim FIRST — only pre-execution states can be cancelled. If
    // execution already claimed the batch (PROCESSING+), this returns false
    // and no rewards are released while payments may be in flight.
    if (!payoutRepository.claimBatchForCancellation(batchId)) {
      throw ConflictException("Batch in status ${batch.status} cannot be cancelled")
    }
    val items = payoutRepository.listItems(batchId)
    payoutRepository.releaseRewards(items.map { it.rewardId })
    payoutRepository.audit(
      actorId = admin.capturUserId, actorEmail = admin.email, action = "BATCH_CANCELLED",
      entityType = "payout_batch", entityId = batchId,
      before = objectMapper.writeValueAsString(batch), after = """{"status":"CANCELLED"}""",
      ip = ip, ua = ua,
    )
    return payoutRepository.findBatch(batchId)!!
  }

  fun executeBatch(batchId: UUID, admin: AuthPrincipal): PayoutBatch {
    if (!solanaEnabled) {
      throw FeatureDisabledException(
        "Solana execution is disabled pending reward-amount, wallet-field, " +
          "eligibility-trigger and treasury confirmation (spec §12)",
      )
    }
    return payoutExecutionService.executeBatch(batchId, admin)
  }

  // ---------- queries ----------

  fun getBatch(id: UUID): BatchDetailDto {
    val batch = payoutRepository.findBatch(id) ?: throw NotFoundException("Batch not found")
    return BatchDetailDto(batch, payoutRepository.listItems(id))
  }

  fun listBatches(limit: Int, offset: Int, status: String?): Pair<List<PayoutBatch>, Long> =
    payoutRepository.listBatches(limit, offset, status) to payoutRepository.countBatches(status)

  fun paymentHistory(limit: Int, offset: Int, status: String?, userId: UUID?): Pair<List<PaymentHistoryRow>, Long> =
    payoutRepository.paymentHistory(limit, offset, status, userId) to
      payoutRepository.countPaymentHistory(status, userId)

  companion object {
    const val MAX_BATCH_ITEMS = 200
    const val MAX_BATCH_USDC_UNITS = 100_000_000L // 100 USDC cap until tuned
  }
}
