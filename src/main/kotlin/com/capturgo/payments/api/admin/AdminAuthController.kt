package com.capturgo.payments.api.admin

import com.capturgo.payments.core.auth.AuthContext
import com.capturgo.payments.core.auth.AuthPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/auth")
class AdminAuthController {

  @GetMapping("/verify")
  fun verify(req: HttpServletRequest): Map<String, Any?> {
    val principal = req.getAttribute(AuthContext.PRINCIPAL_ATTRIBUTE) as? AuthPrincipal
    return mapOf(
      "userId" to (principal?.capturUserId?.toString() ?: principal?.privyUserId),
      "email" to principal?.email,
      "roles" to (principal?.roles?.toList() ?: emptyList<String>()),
      "authenticated" to (principal != null),
    )
  }
}
