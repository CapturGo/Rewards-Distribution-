package com.capturgo.payments.core.auth

import com.capturgo.payments.core.exception.ForbiddenException
import com.capturgo.payments.core.exception.UnauthorizedException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthFilterTest {

  private fun req(path: String, authHeader: String? = null): HttpServletRequest {
    val r = mockk<HttpServletRequest>(relaxed = true)
    every { r.getRequestURI() } returns path
    every { r.getHeader("Authorization") } returns authHeader
    every { r.getAttribute(any()) } returns null
    return r
  }

  private fun res(): HttpServletResponse = mockk(relaxed = true)
  private val chain: FilterChain = mockk(relaxed = true)

  private fun filter(jwtVerifier: PrivyJwtVerifier = mockk(relaxed = true)) =
    AuthFilter(jwtVerifier, AdminAuthService(mockk(), mockk(), "ADMIN,FINANCE"))

  @Test
  fun `public paths skip auth entirely`() {
    val f = filter()
    f.doFilter(req("/health"), res(), chain)
    f.doFilter(req("/ready"), res(), chain)
    verify(atLeast = 2) { chain.doFilter(any(), any()) }
  }

  @Test
  fun `admin verify endpoint skips auth`() {
    filter().doFilter(req("/api/v1/admin/auth/verify"), res(), chain)
    verify { chain.doFilter(any(), any()) }
  }

  @Test
  fun `missing token on protected path returns 401`() {
    val response = res()
    filter().doFilter(req("/api/v1/admin/payments/ready"), response, chain)
    verify { response.status = 401 }
    verify(exactly = 0) { chain.doFilter(any(), any()) }
  }

  @Test
  fun `non-bearer garbage token returns 401`() {
    val verifier = mockk<PrivyJwtVerifier>()
    every { verifier.verify("Basic abc") } returns null
    val response = res()
    filter(verifier).doFilter(req("/api/v1/x", "Bearer Basic abc"), response, chain)
    verify { response.status = 401 }
  }

  @Test
  fun `valid token reaches the chain`() {
    val verifier = mockk<PrivyJwtVerifier>()
    every { verifier.verify("good") } returns
      PrivyJwtVerifier.VerifiedToken("sub-1", "a@b.c", null, "privy.io")
    val response = res()
    val request = req("/api/v1/payments/history", "Bearer good")
    filter(verifier).doFilter(request, response, chain)
    verify { chain.doFilter(request, response) }
    verify { request.setAttribute("authPrincipal", any()) }
  }

  @Test
  fun `admin role detection`() {
    assertTrue(AuthPrincipal("u1", null, null, setOf("ADMIN"), "t").isAdmin)
    assertTrue(AuthPrincipal("u1", null, null, setOf("FINANCE"), "t").isAdmin)
    assertFalse(AuthPrincipal("u1", null, null, setOf("USER"), "t").isAdmin)
    // MODERATOR is a content-review role — never a payout admin by default
    assertFalse(AuthPrincipal("u1", null, null, setOf("MODERATOR"), "t").isAdmin)
    // configured admin roles are honored
    assertTrue(
      AuthPrincipal("u1", null, null, setOf("MODERATOR"), "t", adminRoles = setOf("MODERATOR")).isAdmin,
    )
    assertTrue(runCatching { throw UnauthorizedException() }.isFailure)
    assertTrue(runCatching { throw ForbiddenException() }.isFailure)
  }

  @Test
  fun `admin auth service applies configured roles`() {
    val svc = AdminAuthService(mockk(), mockk(), "ADMIN,FINANCE")
    val field = AdminAuthService::class.java.getDeclaredField("allowedAdminRoles")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val roles = field.get(svc) as Set<String>
    assertEquals(setOf("ADMIN", "FINANCE"), roles)
  }
}
