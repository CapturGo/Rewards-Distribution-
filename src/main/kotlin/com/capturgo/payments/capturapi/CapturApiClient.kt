package com.capturgo.payments.capturapi

import com.capturgo.payments.core.exception.UpstreamException
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

/**
 * Read-only client for captur-api. Every call forwards the caller's Bearer
 * token — the service never stores or mints captur-api credentials.
 */
@Component
class CapturApiClient(
  @Value("\${payments.captur-api.base-url}") private val baseUrl: String,
  @Value("\${payments.captur-api.timeout-ms:15000}") private val timeoutMs: Long,
  @Value("\${payments.captur-api.submissions-page-size:100}") val pageSize: Int,
  @Value("\${payments.captur-api.submissions-max-pages:20}") val maxPages: Int,
) {
  private val logger = LoggerFactory.getLogger(CapturApiClient::class.java)

  private val requestFactory = requestFactoryWithTimeout(timeoutMs)

  private val restClient: RestClient = RestClient.builder()
    .baseUrl(baseUrl)
    .requestFactory { uri, method -> requestFactory.createRequest(uri, method) }
    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
    .build()

  private fun requestFactoryWithTimeout(ms: Long) =
    org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
      setConnectTimeout(ms.toInt())
      setReadTimeout(ms.toInt())
    }

  fun authHeaders(token: String) = HttpHeaders().apply {
    set(HttpHeaders.AUTHORIZATION, "Bearer $token")
    set("X-Device-ID", "payments-service")
    set("X-Device-Type", "server")
  }

  /** Verify admin identity/roles via captur-api using the caller's token. */
  fun verifyAdmin(token: String): AdminVerifyResponse {
    return try {
      restClient.get()
        .uri("/api/v1/admin/auth/verify")
        .headers { it.addAll(authHeaders(token)) }
        .retrieve()
        .body(object : ParameterizedTypeReference<Map<String, Any?>>() {})
        ?.let { mapAdminVerify(it) }
        ?: throw UpstreamException("Empty admin verify response")
    } catch (e: UpstreamException) {
      throw e
    } catch (e: Exception) {
      throw UpstreamException("captur-api admin verify failed: ${e.message}", e)
    }
  }

  private fun mapAdminVerify(raw: Map<String, Any?>): AdminVerifyResponse {
    fun str(key: String) = raw[key] as? String
    fun uuid(key: String) = (raw[key] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    val roles = when (val r = raw["roles"]) {
      is List<*> -> r.filterIsInstance<String>()
      is String -> listOf(r)
      else -> emptyList()
    }
    return AdminVerifyResponse(
      userId = uuid("userId") ?: uuid("id"),
      id = uuid("id"),
      email = str("email"),
      username = str("username"),
      roles = roles,
      role = str("role"),
    )
  }

  /** Fetch one page of submissions. status filter optional. */
  fun fetchSubmissions(
    token: String,
    status: String?,
    offset: Int,
    limit: Int,
  ): PagedSubmissions {
    val uri = UriComponentsBuilder
      .fromPath("/api/v1/admin/activities/submissions")
      .queryParam("limit", limit)
      .queryParam("offset", offset)
      .queryParam("sortBy", "updated_at")
      .queryParam("sortDir", "asc")
      .apply { status?.let { queryParam("status", it) } }
      .build()
      .toUriString()

    return try {
      restClient.get()
        .uri(uri)
        .headers { it.addAll(authHeaders(token)) }
        .retrieve()
        .body(PagedSubmissions::class.java)
        ?: PagedSubmissions()
    } catch (e: Exception) {
      logger.warn("captur-api submissions fetch failed at offset {}: {}", offset, e.message)
      throw UpstreamException("captur-api submissions fetch failed: ${e.message}", e)
    }
  }

  /**
   * Resolve the caller's captur-api profile. captur-api requires the `userId`
   * query param and treats it as the "current user" selector — a 200 response
   * proves the supplied id belongs to the Bearer token's owner.
   */
  fun getMe(token: String, userId: UUID): CapturUserProfile? {
    return try {
      restClient.get()
        .uri { builder ->
          builder.path("/api/v1/users/me")
            .queryParam("userId", userId)
            .build()
        }
        .headers { it.addAll(authHeaders(token)) }
        .retrieve()
        .body(CapturUserProfile::class.java)
    } catch (e: Exception) {
      logger.debug("users/me failed: {}", e.message)
      null
    }
  }

  fun getUser(token: String, userId: UUID): CapturUserProfile? {
    return try {
      restClient.get()
        .uri("/api/v1/users/{id}", userId)
        .headers { it.addAll(authHeaders(token)) }
        .retrieve()
        .body(CapturUserProfile::class.java)
    } catch (e: Exception) {
      logger.debug("user lookup failed for {}: {}", userId, e.message)
      null
    }
  }
}
