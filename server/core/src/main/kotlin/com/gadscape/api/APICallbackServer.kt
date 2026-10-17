package com.gadscape.api

import com.gadscape.api.service.sanction.SanctionAPIService
import com.gadscape.api.service.store.StoreAPIService
import com.gadscape.api.service.vote.VoteAPIService
import com.zenyte.game.GameConstants
import com.zenyte.game.world.World
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.security.MessageDigest

@Serializable
data class OnlinePlayersResponse(val count: Int, val players: List<String>)

object APICallbackServer {

    private val logger = LoggerFactory.getLogger(this::class.java.simpleName)

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    fun start(callbackServerPort: Int = GameConstants.WORLD_PROFILE.api?.callbackPort ?: 6969): Boolean {
        try {
            embeddedServer(Netty, port = callbackServerPort, host = "127.0.0.1") {
                install(ContentNegotiation) {
                    json()
                }
                install(CallLogging) {
                    level = Level.TRACE
                }
                install(Resources)
                intercept(ApplicationCallPipeline.Plugins) {
                    val configuredToken = GameConstants.WORLD_PROFILE.api?.token
                    val presentedToken = call.request.headers[HttpHeaders.Authorization]
                        ?.removePrefix("Bearer ")
                        ?.trim()
                    if (configuredToken.isNullOrBlank() ||
                        presentedToken.isNullOrBlank() ||
                        !constantTimeEquals(presentedToken, configuredToken)
                    ) {
                        call.respond(HttpStatusCode.Unauthorized)
                        finish()
                        return@intercept
                    }
                }
                routing {
                    with(StoreAPIService) {
                        orderCallback()
                    }
                    with(VoteAPIService) {
                        voteCallback()
                    }
                    with(SanctionAPIService) {
                        sanctionCallback()
                    }
                    // Pending for the next game deploy, not live yet (see commit message /
                    // RENAME-NOTES.md) - cheapest way to expose a player count/online list:
                    // reuses the same token-gated callback server rather than a new listener.
                    get("/players/online") {
                        val names = World.getPlayers()
                            .filterNotNull()
                            .filterNot { it.isHidden }
                            .map { it.name }
                            .sorted()
                        call.respond(OnlinePlayersResponse(names.size, names))
                    }
                }
            }.start(wait = false)
            logger.info("Store API callback server started on 127.0.0.1:{}", callbackServerPort)
            return true
        } catch (e: Exception) {
            logger.error("Failed to start store API callback server", e)
            return false
        }
    }
}
