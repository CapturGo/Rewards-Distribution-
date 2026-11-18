package com.capturgo.payments.rewards

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Optional scheduled reward sync. Disabled by default — enable only after a
 * service credential for captur-api is provisioned (CAPTUR_API_SERVICE_TOKEN).
 * captur-api has no machine auth today, so the poller reuses a long-lived
 * admin Bearer token stored in Secret Manager.
 */
@Component
class RewardSyncScheduler(
  private val syncService: RewardSyncService,
  @Value("\${payments.rewards.auto-sync-enabled:false}") private val enabled: Boolean,
  @Value("\${payments.rewards.auto-sync-interval-ms:300000}") private val intervalMs: Long,
  @Value("\${CAPTUR_API_SERVICE_TOKEN:}") private val serviceToken: String,
) {
  private val logger = LoggerFactory.getLogger(RewardSyncScheduler::class.java)

  @Scheduled(fixedDelayString = "\${payments.rewards.auto-sync-interval-ms:300000}",
    initialDelayString = "\${payments.rewards.auto-sync-initial-delay-ms:60000}")
  fun run() {
    if (!enabled) return
    if (serviceToken.isBlank()) {
      logger.warn("auto-sync enabled but CAPTUR_API_SERVICE_TOKEN is empty — skipping")
      return
    }
    try {
      val result = syncService.sync(serviceToken)
      logger.info("scheduled reward sync: {}", result)
    } catch (e: Exception) {
      logger.error("scheduled reward sync failed: {}", e.message)
    }
  }
}
