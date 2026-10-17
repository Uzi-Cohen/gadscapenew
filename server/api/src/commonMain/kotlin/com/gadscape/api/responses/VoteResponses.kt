@file:Suppress("CanSealedSubClassBeObject")

package com.gadscape.api.responses

import com.gadscape.api.model.Vote
import com.gadscape.api.model.VoteSiteStatus
import kotlinx.serialization.Serializable
import kotlin.time.Duration


@Serializable
data class VoteSiteStatusResponse(
    val userId: Long,
    val statuses: List<VoteSiteStatus>
)

@Serializable
sealed class CreateVoteResponse {
    @Serializable class AccountNotFound : CreateVoteResponse()
    @Serializable class SiteNotFound : CreateVoteResponse()
    @Serializable data class Created(val vote: Vote) : CreateVoteResponse()
    @Serializable data class TooSoon(val timeRemaining: Duration) : CreateVoteResponse()
}
