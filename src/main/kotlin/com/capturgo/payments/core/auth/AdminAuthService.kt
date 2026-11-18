package com.capturgo.payments.core.auth

import com.capturgo.payments.capturapi.CapturApiClient
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Resolves admin identity + roles by calling captur-api's /admin/auth/verify
 * with the caller's own Bearer token. captur-api remains the authority on roles.
 * Results are cached briefly per token.
 *
 * `payments.admin-roles` (env PAYMENTS_ADMIN_ROLES) decides which captur-api
 * roles may use admin endpoints — default ADMIN,FINANCE. MODERATOR is a
 * content-review role and must not approve or execute payouts.
 */
@Service
class AdminAuthService(
  private val capturApiClient: CapturApiClient,
  private val objectMapper: ObjectMapper,
  @Value("\${payments.admin-roles:ADMIN,FINANCE}") adminRoles: String,
) {
  private val logger = LoggerFactory.getLogger(AdminAuthService::class.java)
  private val cache = ConcurrentHashMap<String, Pair<Instant, AuthPrincipal?>>()

  private val allowedAdminRoles: Set<String> =
    adminRoles.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
      .ifEmpty { AuthPrincipal.DEFAULT_ADMIN_ROLES }

  fun resolveAdmin(token: String, verified: PrivyJwtVerifier.VerifiedToken): AuthPrincipal? {
    cache[token]?.let { (at, principal) ->
      if (Duration.between(at, Instant.now()) < CACHE_TTL) return principal
    }
    val principal = try {
      val profile = capturApiClient.verifyAdmin(token)
      AuthPrincipal(
        privyUserId = verified.subject,
        email = profile.email ?: verified.email,
        capturUserId = profile.userId,
        roles = profile.roles.toSet(),
        rawToken = token,
        adminRoles = allowedAdminRoles,
      )
    } catch (e: Exception) {
      logger.debug("Admin verify via captur-api failed: {}", e.message)
      null
    }
    cache[token] = Instant.now() to principal
    return principal
  }

  companion object {
    private val CACHE_TTL: Duration = Duration.ofSeconds(60)
  }
}
