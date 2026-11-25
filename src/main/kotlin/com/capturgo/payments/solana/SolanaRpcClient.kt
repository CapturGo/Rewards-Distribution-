package com.capturgo.payments.solana

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.p2p.solanaj.core.PublicKey
import org.p2p.solanaj.rpc.RpcClient
import org.p2p.solanaj.rpc.types.config.Commitment
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Thin wrapper over solanaj's RpcClient. Devnet-only usage is enforced by
 * [TreasurySigner]/[PayoutExecutionService] — this class is transport only.
 */
@Component
class SolanaRpcClient(
  @Value("\${payments.solana.rpc-url:}") private val rpcUrl: String,
) {
  private val client: RpcClient? = rpcUrl.takeIf { it.isNotBlank() }?.let { RpcClient(it) }
  private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build()
  private val mapper = ObjectMapper()

  val configured: Boolean get() = client != null

  fun getSolBalanceLamports(address: String): Long {
    val c = client ?: throw SolanaException("SOLANA_RPC_URL not configured")
    return c.api.getBalance(PublicKey(address), Commitment.CONFIRMED)
  }

  /**
   * Total SPL token balance (base units) for [owner] across every token
   * account holding [mint]. Returns null when Solana is not configured.
   */
  fun getSplTokenBalance(owner: String, mint: String): Long? {
    if (!configured) return null
    val body = mapper.writeValueAsString(
      mapOf(
        "jsonrpc" to "2.0",
        "id" to 1,
        "method" to "getTokenAccountsByOwner",
        "params" to listOf(
          owner,
          mapOf("mint" to mint),
          mapOf("encoding" to "jsonParsed"),
        ),
      ),
    )
    val req = HttpRequest.newBuilder(URI.create(rpcUrl))
      .timeout(Duration.ofSeconds(10))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    val res = http.send(req, HttpResponse.BodyHandlers.ofString())
    val root = mapper.readTree(res.body())
    var total = 0L
    root.path("result").path("value").forEach { acc ->
      val amt = acc
        .path("account").path("data").path("parsed")
        .path("info").path("tokenAmount").path("amount")
        .asText("0")
      total += amt.toLongOrNull() ?: 0L
    }
    return total
  }

  /** Returns the signature's confirmation status, or null when unknown/not found. */
  fun getSignatureStatus(signature: String): String? {
    val c = client ?: throw SolanaException("SOLANA_RPC_URL not configured")
    val statuses = c.api.getSignatureStatuses(listOf(signature))
    val v = statuses.`value` ?: return null
    if (v.isEmpty()) return null
    val s = v[0] ?: return null
    return s.confirmationStatus
  }

  /** On-chain result detail for a landed transaction. */
  data class TxDetail(
    val confirmationStatus: String?,
    /** Non-null when the transaction executed but failed on-chain. */
    val err: Any?,
    val feeLamports: Long?,
    val slot: Long?,
  )

  /**
   * Full reconciliation lookup: confirmation status plus the transaction's
   * on-chain error. A failed transaction still reports confirmed/finalized —
   * [TxDetail.err] is the only way to tell success from on-chain failure.
   * Returns null when the signature is unknown to the cluster.
   */
  fun getTransactionDetail(signature: String): TxDetail? {
    val status = runCatching { getSignatureStatus(signature) }.getOrNull()
      ?: return null
    if (status != "confirmed" && status != "finalized") {
      return TxDetail(status, err = null, feeLamports = null, slot = null)
    }
    val c = client ?: throw SolanaException("SOLANA_RPC_URL not configured")
    val tx = runCatching {
      c.api.getTransaction(signature, org.p2p.solanaj.rpc.types.config.Commitment.CONFIRMED)
    }.getOrNull() ?: return TxDetail(status, err = "unavailable", feeLamports = null, slot = null)
    val meta = tx.meta
    return TxDetail(
      confirmationStatus = status,
      err = meta?.err,
      feeLamports = meta?.fee,
      slot = runCatching { tx.slot }.getOrNull(),
    )
  }

  fun sendTransaction(transaction: org.p2p.solanaj.core.Transaction, signer: org.p2p.solanaj.core.Account): String {
    val c = client ?: throw SolanaException("SOLANA_RPC_URL not configured")
    return c.api.sendTransaction(transaction, signer)
  }

  fun latestBlockhash(): String {
    val c = client ?: throw SolanaException("SOLANA_RPC_URL not configured")
    return c.api.getLatestBlockhash(Commitment.CONFIRMED).`value`?.blockhash
      ?: throw SolanaException("Empty latest blockhash")
  }

  companion object {
    /** SPL token program and ATA program IDs are constant across clusters. */
    val TOKEN_PROGRAM_ID = PublicKey("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val ATA_PROGRAM_ID = PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
    val SYSTEM_PROGRAM_ID = PublicKey("11111111111111111111111111111111")
  }
}

class SolanaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
