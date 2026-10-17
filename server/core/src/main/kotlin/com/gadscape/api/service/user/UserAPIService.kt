package com.gadscape.api.service.user

import com.gadscape.api.APIClient
import com.gadscape.api.ApiConstants
import com.gadscape.api.model.ApiGameMode
import com.gadscape.api.model.Bond
import com.gadscape.api.model.Skill
import com.gadscape.api.requests.UserLoginRequest
import com.gadscape.api.requests.UserUpdateHiScoresRequest
import com.gadscape.api.resources.User
import com.gadscape.api.responses.*
import com.gadscape.api.service.APIService
import com.gadscape.api.util.AES
import kotlinx.coroutines.runBlocking

object UserAPIService : APIService() {

    fun login(username: String, password: String, ip: String, uuid: ByteArray, onResponse: (UserLoginResponse?) -> Unit) {
        if (!APIClient.post<UserLoginRequest, UserLoginResponse, User.Login>(
                resource = User.Login(),
                request = UserLoginRequest(username, runBlocking { AES.encrypt(password, ApiConstants.AES_SECRET)!! }, ip, uuid),
                async = false,
                onSuccess = { onResponse(this) },
                onFailed = {
                    logger.error("Failed to login user: $username (api_status_received=$this)", it)
                    onResponse(null)
                }
            )
        ) onResponse(null)
    }

    fun validate2FA(user: com.gadscape.api.model.User, code: Int, onResponse: (Boolean) -> Unit) {
        if(!APIClient.get<Boolean, User.Id.Check2FA>(
                resource = User.Id.Check2FA(user.id, code),
                async = false,
                onSuccess = { onResponse(this) },
                onFailed = { onResponse(false) }
            )
        ) onResponse(false)
    }

    fun updateGameMode(user: com.gadscape.api.model.User, mode: ApiGameMode, onResponse: (UserUpdateGameModeResponse?) -> Unit) {
        if (!APIClient.post<UserUpdateGameModeResponse, User.Id.GameMode>(
                resource = User.Id.GameMode(user.id, mode),
                onSuccess = { onResponse(this) },
                onFailed = { onResponse(null) }
            )
        ) onResponse(null)
    }

    fun updateHiscores(user: com.gadscape.api.model.User, skills: List<Skill>) {
        APIClient.post<UserUpdateHiScoresRequest, UserUpdateHiScoresResponse, User.Id.Hiscores>(
            resource = User.Id.Hiscores(user.id),
            request = UserUpdateHiScoresRequest(skills)
        )
    }

    fun claimBond(user: com.gadscape.api.model.User, bond: Bond, onResponse: (UserClaimBondResponse?) -> Unit) {
        if (!APIClient.post<UserClaimBondResponse, User.Id.Bond>(
                resource = User.Id.Bond(user.id, bond),
                onSuccess = { onResponse(this) },
                onFailed = { onResponse(null) }
            )
        ) onResponse(null)
    }

    fun subtractCredits(user: com.gadscape.api.model.User, amount: Int, onResponse: (UserSubtractCreditsResponse?) -> Unit) {
        if (!APIClient.post<UserSubtractCreditsResponse, User.Id.Credits>(
                resource = User.Id.Credits(user.id, amount),
                onSuccess = { onResponse(this) },
                onFailed = { onResponse(null) }
            )
        ) onResponse(null)
    }
}
