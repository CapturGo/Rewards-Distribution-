package com.capturgo.payments.core.auth

import java.util.UUID

/** Authenticated caller resolved from a verified Privy JWT + captur-api role check. */
data class AuthPrincipal(
  val privyUserId: String,
  val email: String?,
  val capturUserId: UUID?,
  val roles: Set<String>,
  val rawToken: String,
  /** Roles permitted to perform admin/financial actions. Config-driven. */
  val adminRoles: Set<String> = DEFAULT_ADMIN_ROLES,
) {
  val isAdmin: Boolean
    get() = roles.any { it.uppercase() in adminRoles }

  companion object {
    /** Payout/financial admin default — MODERATOR intentionally excluded. */
    val DEFAULT_ADMIN_ROLES = setOf("ADMIN", "FINANCE")
  }
}

object AuthContext {
  const val PRINCIPAL_ATTRIBUTE = "authPrincipal"
}
