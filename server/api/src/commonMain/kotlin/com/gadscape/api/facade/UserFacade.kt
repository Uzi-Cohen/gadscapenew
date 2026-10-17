package com.gadscape.api.facade

import com.gadscape.api.model.ApiGameMode
import com.gadscape.api.model.Bond
import com.gadscape.api.responses.UserClaimBondResponse
import com.gadscape.api.responses.UserSubtractCreditsResponse
import com.gadscape.api.responses.UserUpdateGameModeResponse

interface UserFacade {

    suspend fun findUserIdByUsername(username: String): Long?

    suspend fun hasTwoFactorAuthEnabled(identityId: Long): Boolean
    suspend fun getTwoFactorAuthSecret(identityId: Long): String

    suspend fun setGameMode(identityId: Long, gameMode: ApiGameMode): UserUpdateGameModeResponse

    suspend fun claimBond(identityId: Long, bond: Bond): UserClaimBondResponse

    suspend fun subtractCredits(identityId: Long, amount: Int): UserSubtractCreditsResponse
}
