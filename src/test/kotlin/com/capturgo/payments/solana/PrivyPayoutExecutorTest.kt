package com.capturgo.payments.solana

import com.capturgo.payments.solana.PrivyWalletClient.Action
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrivyPayoutExecutorTest {

  private val client = mockk<PrivyWalletClient>()

  private fun executor(
    enabled: Boolean = true,
    walletId: String = "wal_123",
    chain: String = "solana_devnet",
    cluster: String = "devnet",
    timeoutMs: Long = 50,
    pollMs: Long = 5,
  ) = PrivyPayoutExecutor(
    client = client,
    enabled = enabled,
    treasuryWalletId = walletId,
    chain = chain,
    cluster = cluster,
    decimals = 6,
    actionTimeoutMs = timeoutMs,
    actionPollMs = pollMs,
  )

  @Test
  fun `disabled executor refuses to send`() {
    every { client.configured } returns true
    assertFalse(executor(enabled = false).configured)
    assertThrows(SolanaException::class.java) {
      executor(enabled = false).execute("destAddr", 1_000_000L)
    }
  }

  @Test
  fun `mainnet chain refuses to send`() {
    every { client.configured } returns true
    assertFalse(executor(chain = "solana").configured)
    assertThrows(SolanaException::class.java) {
      executor(chain = "solana").execute("destAddr", 1_000_000L)
    }
  }

  @Test
  fun `succeeded action confirms with action id as reference`() {
    every { client.configured } returns true
    every {
      client.transfer("wal_123", "solana_devnet", "usdc", "1.5", "destAddr", "usdc", any())
    } returns Action("act_1", "pending")
    every { client.getAction("wal_123", "act_1") } returns Action("act_1", "succeeded")

    val result = executor().execute("destAddr", 1_500_000L)

    assertTrue(result.confirmed)
    assertEquals("act_1", result.signature)
  }

  @Test
  fun `failed action surfaces chain error - never confirmed`() {
    every { client.configured } returns true
    every {
      client.transfer(any(), any(), any(), any(), any(), any(), any())
    } returns Action("act_2", "pending")
    every { client.getAction("wal_123", "act_2") } returns Action("act_2", "failed")

    val result = executor().execute("destAddr", 1_000_000L)

    assertFalse(result.confirmed)
    assertEquals("failed", result.confirmationStatus)
    assertEquals("privy action failed", result.chainError)
  }

  @Test
  fun `timeout past pending returns unconfirmed for reconciliation`() {
    every { client.configured } returns true
    every {
      client.transfer(any(), any(), any(), any(), any(), any(), any())
    } returns Action("act_3", "pending")
    every { client.getAction("wal_123", "act_3") } returns Action("act_3", "pending")

    val result = executor(timeoutMs = 30, pollMs = 5).execute("destAddr", 1_000_000L)

    assertFalse(result.confirmed)
    assertEquals("act_3", result.signature)
  }

  @Test
  fun `amount is decimal string in standard units`() {
    every { client.configured } returns true
    val amountSlot = slot<String>()
    every {
      client.transfer(any(), any(), any(), capture(amountSlot), any(), any(), any())
    } returns Action("act_4", "pending")
    every { client.getAction("wal_123", "act_4") } returns Action("act_4", "succeeded")

    executor().execute("destAddr", 2_500_000L)

    // 2_500_000 base units at 6 decimals = "2.5" — never a float
    assertEquals("2.5", amountSlot.captured)
  }

  @Test
  fun `lookup maps succeeded to confirmed tx detail`() {
    every { client.configured } returns true
    every { client.getAction("wal_123", "act_9") } returns Action("act_9", "succeeded")

    val detail = executor().lookup("act_9")

    assertEquals("finalized", detail?.confirmationStatus)
    assertNull(detail?.err)
  }

  @Test
  fun `lookup maps failed to landed-with-error detail`() {
    every { client.configured } returns true
    every { client.getAction("wal_123", "act_8") } returns Action("act_8", "rejected")

    val detail = executor().lookup("act_8")

    assertEquals("finalized", detail?.confirmationStatus)
    assertEquals("privy action rejected", detail?.err)
  }

  @Test
  fun `lookup maps pending to processed detail`() {
    every { client.configured } returns true
    every { client.getAction("wal_123", "act_7") } returns Action("act_7", "pending")

    val detail = executor().lookup("act_7")

    assertEquals("processed", detail?.confirmationStatus)
  }

  @Test
  fun `idempotency key is deterministic per destination and amount`() {
    every { client.configured } returns true
    val keys = mutableListOf<String>()
    every {
      client.transfer(any(), any(), any(), any(), "destAddr", any(), capture(keys))
    } returns Action("act_x", "succeeded")
    every { client.getAction(any(), "act_x") } returns Action("act_x", "succeeded")

    executor().execute("destAddr", 1_000_000L)
    executor().execute("destAddr", 1_000_000L)

    assertEquals(2, keys.size)
    assertEquals(keys[0], keys[1]) // same payment -> same key -> Privy dedupes
  }
}
