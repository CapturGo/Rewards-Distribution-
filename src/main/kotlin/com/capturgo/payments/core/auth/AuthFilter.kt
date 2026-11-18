package com.capturgo.payments.core.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates every request under the /api/v1 prefix:
 *  1. Bearer token → PrivyJwtVerifier (signature + expiry)
 *  2. admin paths additionally → captur-api role check (cached)
 * Public paths (health probes handled by actuator) are not under /api/v1.
 */
@Component
@Order(10)
class AuthFilter(
  private val jwtVerifier: PrivyJwtVerifier,
  private val adminAuthService: AdminAuthService,
) : OncePerRequestFilter() {

  override fun shouldNotFilter(request: HttpServletRequest): Boolean {
    val path = request.requestURI
    return !path.startsWith("/api/v1/") ||
      path.startsWith("/api/v1/admin/auth/verify")
  }

  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    chain: FilterChain,
  ) {
    val header = request.getHeader("Authorization")
    val token = header?.removePrefix("Bearer ")?.trim()
    if (token.isNullOrBlank()) {
      return reject(response, "Missing Authorization header")
    }

    val verified = jwtVerifier.verify(token)
      ?: return reject(response, "Invalid or expired token")

    val isAdminPath = request.requestURI.startsWith("/api/v1/admin/")
    if (isAdminPath) {
      val admin = adminAuthService.resolveAdmin(token, verified)
        ?: return reject(response, "Admin verification failed")
      if (!admin.isAdmin) {
        return reject(response, "Admin role required", HttpServletResponse.SC_FORBIDDEN)
      }
      request.setAttribute(AuthContext.PRINCIPAL_ATTRIBUTE, admin)
    } else {
      // Contributor path: authenticated user, identity resolved lazily by captorUserId lookup.
      val principal = AuthPrincipal(
        privyUserId = verified.subject,
        email = verified.email,
        capturUserId = null,
        roles = emptySet(),
        rawToken = token,
      )
      request.setAttribute(AuthContext.PRINCIPAL_ATTRIBUTE, principal)
    }

    request.setAttribute("requestId", UUID.randomUUID().toString())
    chain.doFilter(request, response)
  }

  /** Filters run before DispatcherServlet — @RestControllerAdvice can't see these. */
  private fun reject(response: HttpServletResponse, message: String, status: Int = HttpServletResponse.SC_UNAUTHORIZED) {
    response.status = status
    response.contentType = "application/json"
    response.writer.write(
      """{"errorCode":"${if (status == 401) "UNAUTHORIZED" else "FORBIDDEN"}",""" +
        """"numericCode":$status,"error":"${if (status == 401) "Unauthorized" else "Forbidden"}",""" +
        """"message":"$message","timestamp":"${java.time.Instant.now()}","path":"","requestId":"${UUID.randomUUID()}"}""",
    )
  }
}
