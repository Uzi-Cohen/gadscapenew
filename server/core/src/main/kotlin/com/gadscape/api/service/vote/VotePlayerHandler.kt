package com.gadscape.api.service.vote

import com.gadscape.website.WebsiteService
import com.gadscape.api.APIClient
import com.gadscape.api.model.User
import com.gadscape.api.model.Vote
import com.gadscape.api.model.VoteSite
import com.gadscape.api.model.VoteSiteStatus
import com.gadscape.api.service.store.notify
import com.gadscape.api.service.user.updateVoteStatisticOnPlayerDetailsTab
import com.gadscape.tools.logging.GameLogMessage
import com.gadscape.tools.logging.GameLogger
import com.zenyte.cores.CoresManager
import com.zenyte.game.content.skills.afk.AfkSkilling
import com.zenyte.game.content.vote.VoteHandler
import com.zenyte.game.content.xamphur.XamphurHandler
import com.zenyte.game.item.Item
import com.zenyte.game.item.ItemId
import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.util.Colour
import com.zenyte.game.world.World
import com.zenyte.game.world.broadcasts.BroadcastType
import com.zenyte.game.world.broadcasts.WorldBroadcasts
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.plugins.dialogue.WiseOldManD
import com.zenyte.utils.TimeUnit
import io.ktor.http.*
import io.ktor.server.util.*
import mgi.utilities.StringFormatUtil
import org.slf4j.LoggerFactory

object VotePlayerHandler {

    private val logger = LoggerFactory.getLogger(VotePlayerHandler::class.java)
    internal var peopleVoted = 0

    fun startVote(player: Player, site: VoteSite) {
        ifEnabled(
            player,
            action = { user ->
                player.notify("Awaiting vote confirmation...", loading = true)
                val voteUrl = when(site) {
                    VoteSite.RUNELOCUS -> url {
                        protocol = URLProtocol.HTTPS
                        host = "www.runelocus.com"
                        path("top-rsps-list/GadScape-is-back/vote")
                        parameters.append("callback", user.id.toString())
                    }
                    VoteSite.RSPS_LIST -> url {
                        protocol = URLProtocol.HTTPS
                        host = "www.rsps-list.com"
                        path("index.php")
                        parameters.append("a", "in")
                        parameters.append("u", "nrpk")
                        parameters.append("id", user.id.toString())
                    }
                }
                player.packetDispatcher.sendURL(voteUrl)
            }
        )
    }

    fun requestStatuses(player: Player, onResponse: (Map<VoteSite, VoteSiteStatus>) -> Unit) {
        ifEnabled(player) { user ->
            VoteAPIService.requestStatuses(user) {
                WorldTasksManager.schedule {
                    onResponse(it)
                }
            }
        }
    }

    /**
     * Applies a vote reward to a [player] that is genuinely online right now - either
     * because they were already online when the vote came in, or because this is
     * being replayed from [pendingVotesToApplyOnNextLogin] right after they just logged
     * in. Must never be called with a player loaded via [com.zenyte.game.world.entity.player.login.LoginManager]'s
     * lightweight offline-load path - that object has no packetDispatcher, BoonManager,
     * etc wired up and nothing saves it afterwards, so granting items/dialogues on it
     * silently NPEs and loses the reward.
     */
    internal fun applyVoteReward(player: Player, vote: Vote) {
        WorldTasksManager.schedule {
            val votePoints = vote.votePointReward
            val votePointsWithBonus = (votePoints * VoteHandler.getVotePointsModifier(player)).toInt()
            val bonusVotePoints = votePointsWithBonus - votePoints
            GameLogger.log {
                GameLogMessage.ClaimedVotes(username = player.username, votesClaimed = votePoints, votesBonus = bonusVotePoints)
            }
            if (votePointsWithBonus > 0) {
                XamphurHandler.get().addVotes(player, votePoints)
                AfkSkilling.addAfkTime(player, TimeUnit.HOURS.toMillis(12))
                WiseOldManD.rollClues(player, votePointsWithBonus)
                val coinRewardAmount = votePointsWithBonus * (if (player.authenticator.isEnabled) 150_000 else 75_000)
                val coinRewardItem = Item(ItemId.COINS_995, coinRewardAmount)
                player.inventory.addOrDrop(ItemId.TOME_OF_EXPERIENCE_30215, 2)
                player.inventory.addOrDrop(coinRewardItem)
                player.totalVoteCredits += votePointsWithBonus
                player.lastVoteClaimTime = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(12)
                player.updateVoteStatisticOnPlayerDetailsTab()
                player.dialogue {
                    plain(buildString {
                        append("Your vote on ${vote.siteType.fancyName} has been confirmed!")
                        append("<br>As a reward you received ${Colour.DARK_BLUE.wrap("$votePoints vote points${if (votePointsWithBonus > 1) "s" else ""}")}")
                        if (bonusVotePoints > 0)
                            append(" (+${Colour.DARK_BLUE.wrap("$bonusVotePoints")} bonus)")
                        append("<br>and ${Colour.DARK_BLUE.wrap(StringFormatUtil.format(coinRewardAmount))} gold pieces.")
                        append("<br>Thank you for voting <3")
                    })
                }
                peopleVoted++
                if (peopleVoted % 10 == 0) {
                    WorldBroadcasts.broadcast(null, BroadcastType.HELPFUL_TIP, "Another 10 people have just voted, type ::vote and get rewards!")
                }
            }
        }
    }

    internal fun onVoteReceived(vote: Vote) {
        val user = vote.user
        val onlinePlayer = World.getPlayer(user.name)
        if (onlinePlayer.isPresent) {
            applyVoteReward(onlinePlayer.get(), vote)
        } else {
            CoresManager.getLoginManager().load(true, vote.user.name) { offlinePlayer ->
                val player = offlinePlayer.orElse(null)
                if (player == null) {
                    logger.error("Failed to claim vote ${vote.id} for user: ${vote.user.name}, user not found.")
                    return@load
                }
                player.pendingVotesToApplyOnNextLogin.add(vote)
                CoresManager.getLoginManager().save(player)
                logger.info("Queued vote {} for offline user {}; will be applied on next login.", vote.id, user.name)
            }
        }
    }

    private fun ifEnabled(player: Player, action: (User) -> Unit) {
        when {
            !APIClient.enabled -> WebsiteService.commandVote(player)
            !VoteAPIService.enabled -> player.notify( "The voting service is currently unavailable.", schedule = false)
            World.isUpdating() -> player.notify("You cannot vote while the world is updating.", schedule = false)
            else -> {
                val user = player.user
                if (user != null)
                    action(user)
                else
                    player.notify("You must be logged in to vote.", schedule = false)
            }
        }
    }
}
