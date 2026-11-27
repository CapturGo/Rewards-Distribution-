package com.capturgo.payments.solana

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Minimal REST client for the Privy server API (Basic auth: app id + secret,
 * plus the `privy-app-id` header). Transport only — no business logic.
 *
 * Endpoints used (https://docs.privy.io/api-reference):
 *  - POST /v1/wallets                          create a server wallet
 *  - POST /v1/wallets/{wallet_id}/transfer     execute a wallet action
 *  - GET  /v1/wallets/{wallet_id}/actions/{id} poll action status
 */
@Component
class PrivyWalletClient(
  @Value("\${payments.privy.app-id:}") private val appId: String,
  @Value("\${payments.privy.app-secret:}") private val appSecret: String,
  @Value("\${payments.privy.api-base-url:https://api.privy.io}") private val apiBaseUrl: String,
  @Value("\${payments.privy.timeout-ms:15000}") private val timeoutMs: Long,
) {
  val configured: Boolean get() = appId.isNotBlank() && appSecret.isNotBlank()

  private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofMillis(timeoutMs))
    .build()
  private val mapper = ObjectMapper()

  class PrivyApiException(val status: Int, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

  data class Wallet(val id: String, val address: String)
  data class Action(val id: String, val status: String)

  /** POST /v1/wallets — create a wallet. Idempotent via `privy-idempotency-key`. */
  fun createWallet(
    chainType: String,
    displayName: String? = null,
    externalId: String? = null,
    idempotencyKey: String? = null,
  ): Wallet {
    val body = mapper.createObjectNode().apply {
      put("chain_type", chainType)
      displayName?.let { put("display_name", it) }
      externalId?.let { put("external_id", it) }
    }
    val res = request("POST", "/v1/wallets", body, idempotencyKey)
    return Wallet(
      id = res.path("id").asText(""),
      address = res.path("address").asText(""),
    )
  }

  /**
   * POST /v1/wallets/{wallet_id}/transfer. Returns the pending wallet action.
   * `amount` is a decimal string in standard units (e.g. "1.50" USDC).
   */
  fun transfer(
    walletId: String,
    sourceChain: String,
    sourceAsset: String,
    amount: String,
    destinationAddress: String,
    destinationAsset: String? = null,
    idempotencyKey: String? = null,
  ): Action {
    val body = mapper.createObjectNode().apply {
      putObject("source").apply {
        put("asset", sourceAsset)
        put("amount", amount)
        put("chain", sourceChain)
      }
      putObject("destination").apply {
        put("address", destinationAddress)
        destinationAsset?.let { put("asset", it) }
      }
    }
    val res = request("POST", "/v1/wallets/$walletId/transfer", body, idempotencyKey)
    return Action(id = res.path("id").asText(""), status = res.path("status").asText("pending"))
  }

  /**
   * GET /v1/users/{user_id} — resolve the user's embedded Solana wallet
   * address from their linked accounts. Returns null when the user has no
   * Solana wallet or the lookup fails (callers treat null as "unresolvable").
   * Prefers wallets created by Privy itself (embedded) over external links.
   */
  fun getUserSolanaAddress(privyUserId: String): String? {
    val user = try {
      request("GET", "/v1/users/$privyUserId", null, null)
    } catch (e: PrivyApiException) {
      if (e.status == 404) return null
      throw e
    }
    val accounts = user.path("linked_accounts")
    if (!accounts.isArray) return null

    var firstSolana: String? = null
    for (account in accounts) {
      val chainType = account.path("chain_type").asText("")
      val address = account.path("address").asText("")
      if (chainType.equals("solana", ignoreCase = true) && address.isNotBlank()) {
        val client = account.path("wallet_client").asText("")
        if (client.equals("privy", ignoreCase = true)) return address
        if (firstSolana == null) firstSolana = address
      }
    }
    return firstSolana
  }

  /** GET /v1/wallets/{wallet_id}/actions/{action_id}. Null on 404. */
  fun getAction(walletId: String, actionId: String): Action? {
    val res = try {
      request("GET", "/v1/wallets/$walletId/actions/$actionId", null, null)
    } catch (e: PrivyApiException) {
      if (e.status == 404) return null
      throw e
    }
    return Action(id = res.path("id").asText(actionId), status = res.path("status").asText("pending"))
  }

  private fun request(
    method: String,
    path: String,
    body: JsonNode?,
    idempotencyKey: String?,
  ): JsonNode {
    if (!configured) throw PrivyApiException(0, "Privy credentials not configured")
    val json = body?.let { mapper.writeValueAsString(it) }
    val builder = HttpRequest.newBuilder()
      .uri(URI.create(apiBaseUrl.trimEnd('/') + path))
      .timeout(Duration.ofMillis(timeoutMs))
      .header("Authorization", "Basic " + Base64.getEncoder()
        .encodeToString("$appId:$appSecret".toByteArray(StandardCharsets.UTF_8)))
      .header("privy-app-id", appId)
      .header("Content-Type", "application/json")
    idempotencyKey?.let { builder.header("privy-idempotency-key", it) }
    when (method) {
      "GET" -> builder.GET()
      "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(json ?: "{}"))
      else -> throw IllegalArgumentException("unsupported method $method")
    }
    val res = try {
      http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    } catch (e: Exception) {
      throw PrivyApiException(0, "Privy request failed: ${e.message}", e)
    }
    val status = res.statusCode()
    if (status !in 200..299) {
      // Do not echo response bodies verbatim into logs — Privy error bodies
      // are safe but may embed request context; truncate defensively.
      val snippet = res.body().take(300)
      throw PrivyApiException(status, "Privy $method $path -> HTTP $status: $snippet")
    }
    return mapper.readTree(res.body())
  }
}
