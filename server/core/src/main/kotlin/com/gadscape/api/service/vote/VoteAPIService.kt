@file:Suppress("unused")

package com.gadscape.api.service.vote

import com.gadscape.api.APIClient
import com.gadscape.api.model.User
import com.gadscape.api.model.Vote
import com.gadscape.api.model.VoteSite
import com.gadscape.api.model.VoteSiteStatus
import com.gadscape.api.service.APIService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.gadscape.api.resources.Vote as VoteResource

/**
 * Represents a service that communicates votes with the game-api.
 * The game-api listens for vote callbacks and updates the player's vote status.
 * Then forwards the vote to this service, which will handle the vote.
 *
 * @author Stan van der Bend
 */
object VoteAPIService : APIService() {

    fun requestStatuses(user: User, response: (Map<VoteSite, VoteSiteStatus>) -> Unit) {
        APIClient.get<List<VoteSiteStatus>, com.gadscape.api.resources.Vote.Status.All>(
            resource = com.gadscape.api.resources.Vote.Status.All(user.id),
            onSuccess = {
                response(associateBy { it.type })
            },
            onFailed = {
                response(emptyMap())
            }
        )
    }

    fun Routing.voteCallback() {
        post<VoteResource.Callback.Game> {
            val vote = call.receive<Vote>()
            VotePlayerHandler.onVoteReceived(vote)
            call.respond(HttpStatusCode.OK)
        }
    }
}
