package com.capturgo.payments.core.auth

import com.capturgo.payments.capturapi.CapturApiClient
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Resolves the captur-api user_id for an authenticated Privy caller WITHOUT
 * trusting a client-supplied userId.
 *
 * Resolution order:
 *  1. Persistent mapping (contributor_identities) — already verified once.
 *  2. Caller supplies a candidate userId → captur-api `/users/me?userId=` is
 *     called with the caller's own token. captur-api treats `userId` as the
 *     "current user" selector and rejects mismatches, so a 200 proves the
 *     claimed id belongs to this token. The mapping is then cached.
 *  3. No candidate → null; the client must supply userId once.
 *
 * Secondary check: when both the JWT email and the profile email are present
 * they must agree, guarding against captur-api ever relaxing the userId check.
 */
@Service
class ContributorIdentityService(
  private val identityRepository: ContributorIdentityRepository,
  private val capturApiClient: CapturApiClient,
) {
  private val logger = LoggerFactory.getLogger(ContributorIdentityService::class.java)

  fun resolve(
    token: String,
    privyUserId: String,
    verifiedEmail: String?,
    claimedUserId: UUID?,
  ): UUID? {
    identityRepository.findCapturUserId(privyUserId)?.let { return it }

    val candidate = claimedUserId ?: return null
    val profile = capturApiClient.getMe(token, candidate) ?: return null
    val resolved = profile.id ?: profile.userId ?: return null

    val profileEmail = profile.email
    if (verifiedEmail != null && profileEmail != null &&
      !verifiedEmail.equals(profileEmail, ignoreCase = true)
    ) {
      logger.warn("identity resolution rejected: email mismatch for privy user {}", privyUserId)
      return null
    }

    identityRepository.upsert(privyUserId, resolved)
    return resolved
  }
}
