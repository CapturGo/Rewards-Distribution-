package com.capturgo.payments.core.auth

import com.capturgo.payments.capturapi.CapturApiClient
import com.capturgo.payments.capturapi.CapturUserProfile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ContributorIdentityServiceTest {

  private val identityRepo = mockk<ContributorIdentityRepository>(relaxed = true)
  private val capturApi = mockk<CapturApiClient>()
  private val service = ContributorIdentityService(identityRepo, capturApi)

  private val privyId = "did:privy:abc123"
  private val userId = UUID.randomUUID()

  @Test
  fun `cached mapping resolves without calling captur-api`() {
    every { identityRepo.findCapturUserId(privyId) } returns userId

    val result = service.resolve("tok", privyId, "a@b.c", null)

    assertEquals(userId, result)
    verify(exactly = 0) { capturApi.getMe(any(), any()) }
  }

  @Test
  fun `first resolution verifies ownership via users-me then caches`() {
    every { identityRepo.findCapturUserId(privyId) } returns null
    every { capturApi.getMe("tok", userId) } returns
      CapturUserProfile(id = userId, email = "a@b.c")

    val result = service.resolve("tok", privyId, "a@b.c", userId)

    assertEquals(userId, result)
    verify { identityRepo.upsert(privyId, userId) }
  }

  @Test
  fun `no claimed userId and no cache returns null`() {
    every { identityRepo.findCapturUserId(privyId) } returns null

    assertNull(service.resolve("tok", privyId, "a@b.c", null))
    verify(exactly = 0) { capturApi.getMe(any(), any()) }
  }

  @Test
  fun `captur-api rejection means claimed id does not belong to caller`() {
    every { identityRepo.findCapturUserId(privyId) } returns null
    every { capturApi.getMe("tok", userId) } returns null // captor-api 403/404 → null

    assertNull(service.resolve("tok", privyId, "a@b.c", userId))
    verify(exactly = 0) { identityRepo.upsert(any(), any()) }
  }

  @Test
  fun `email mismatch between jwt and profile rejects mapping`() {
    every { identityRepo.findCapturUserId(privyId) } returns null
    every { capturApi.getMe("tok", userId) } returns
      CapturUserProfile(id = userId, email = "other@b.c")

    assertNull(service.resolve("tok", privyId, "a@b.c", userId))
    verify(exactly = 0) { identityRepo.upsert(any(), any()) }
  }

  @Test
  fun `profile without email still resolves when jwt has no email`() {
    every { identityRepo.findCapturUserId(privyId) } returns null
    every { capturApi.getMe("tok", userId) } returns CapturUserProfile(id = userId)

    assertEquals(userId, service.resolve("tok", privyId, null, userId))
  }
}
