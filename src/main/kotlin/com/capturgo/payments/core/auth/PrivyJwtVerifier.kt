package com.capturgo.payments.core.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSKeySelector
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.net.URI
import java.time.Instant
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Verifies Privy access tokens against the app's JWKS endpoint.
 * Authentication only — role/identity resolution happens via captur-api.
 */
@Component
class PrivyJwtVerifier(
  @Value("\${payments.privy.jwks-url:}") private val jwksUrl: String,
  @Value("\${payments.privy.app-id:}") private val appId: String,
) {
  private val logger = LoggerFactory.getLogger(PrivyJwtVerifier::class.java)

  private val processors = ConcurrentHashMap<String, ConfigurableJWTProcessor<SecurityContext>>()

  data class VerifiedToken(
    val subject: String,
    val email: String?,
    val expiresAt: Instant?,
    val issuer: String?,
  )

  fun verify(token: String): VerifiedToken? {
    if (jwksUrl.isBlank() || appId.isBlank()) {
      logger.warn("Privy JWKS not configured (app-id/jwks-url empty) — rejecting token")
      return null
    }
    return try {
      val processor = processorFor(jwksUrl)
      val claims: JWTClaimsSet = processor.process(token, null)
      val sub = claims.subject ?: return null
      val exp = claims.expirationTime?.toInstant()
      if (exp != null && exp.isBefore(Instant.now())) return null
      VerifiedToken(
        subject = sub,
        email = claims.getStringClaim("email") ?: emailFromCustomClaims(claims),
        expiresAt = exp,
        issuer = claims.issuer,
      )
    } catch (e: Exception) {
      logger.debug("JWT verification failed: {}", e.message)
      null
    }
  }

  private fun emailFromCustomClaims(claims: JWTClaimsSet): String? {
    val custom = claims.getClaim("custom_claims") as? Map<*, *> ?: return null
    return (custom["email"] as? String)?.ifBlank { null }
  }

  private fun processorFor(url: String): ConfigurableJWTProcessor<SecurityContext> {
    return processors.computeIfAbsent(url) {
      val source: JWKSource<SecurityContext> =
        JWKSourceBuilder
          .create<SecurityContext>(URI.create(url).toURL())
          .retrying(true)
          .build()
      DefaultJWTProcessor<SecurityContext>().apply {
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.ES256, source)
      }
    }
  }
}
