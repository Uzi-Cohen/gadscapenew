package com.gadscape.api.facade

import com.gadscape.api.model.VoteSite
import com.gadscape.api.model.VoteSiteStatus
import com.gadscape.api.responses.CreateVoteResponse

interface VoteFacade {

    /**
     * Gets a list of [VoteSiteStatus] instances specific to the [GameAccount] associated with the [accountId].
     */
    suspend fun voteSiteStatuses(accountId: Long): List<VoteSiteStatus>

    /**
     * Creates a [Vote] of [voteSite] for the [GameAccount] associated with the [userId].
     */
    suspend fun createVote(voteSite: VoteSite, userId: Long, userIp: String) : CreateVoteResponse

    suspend fun claimVote(voteId: Int): Boolean
}
