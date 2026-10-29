package com.gadscape.api.service.vote

import com.gadscape.website.WebsiteService
import com.gadscape.api.APIClient
import com.gadscape.api.model.Vote
import com.gadscape.api.model.VoteSite
import com.gadscape.api.model.VoteSiteStatus
import com.gadscape.api.service.store.notify
import com.gadscape.api.service.user.updateVoteStatisticOnPlayerDetailsTab
import com.gadscape.tools.logging.GameLogMessage
import com.gadscape.tools.logging.GameLogger
import com.zenyte.cores.CoresManager
import com.zenyte.game.content.vote.VoteHandler
import com.zenyte.game.content.xamphur.XamphurHandler
import com.zenyte.game.item.ItemId
import com.gadscape.game.content.vote.VotePerks
import com.gadscape.game.model.ui.vote.VoteScreen
import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.util.Colour
import com.zenyte.game.world.World
import com.zenyte.game.world.broadcasts.BroadcastType
import com.zenyte.game.world.broadcasts.WorldBroadcasts
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.plugins.dialogue.WiseOldManD
import com.zenyte.utils.TimeUnit
import org.slf4j.LoggerFactory

object VotePlayerHandler {

    private val logger = LoggerFactory.getLogger(VotePlayerHandler::class.java)
    internal var peopleVoted = 0

    /** How long a site makes a player wait between two votes (the toplists' own rule). */
    val VOTE_COOLDOWN_MILLIS = TimeUnit.HOURS.toMillis(12)

    /**
     * The vote screen's Vote for [site]: the website's signed vote link when the website link is on, else the site's
     * page (VoteSiteConfig) for the API user or the character.
     */
    fun startVote(player: Player, site: VoteSite) {
        when {
            !VoteSiteConfig.isEnabled(site) -> player.sendMessage("${site.fancyName} isn't taking votes for GadScape yet.")
            World.isUpdating() -> player.notify("You cannot vote while the world is updating.", schedule = false)
            WebsiteService.isEnabled -> WebsiteService.commandVoteSite(player, site, VoteSiteConfig.url(site, player.name))
            APIClient.enabled -> {
                val user = player.user
                when {
                    !VoteAPIService.enabled -> player.notify("The voting service is currently unavailable.", schedule = false)
                    user == null -> player.notify("You must be logged in to vote.", schedule = false)
                    else -> {
                        player.notify("Awaiting vote confirmation...", loading = true)
                        player.packetDispatcher.sendURL(VoteSiteConfig.url(site, user.id.toString()))
                    }
                }
            }
            else -> {
                player.sendMessage("Opening ${site.fancyName}...")
                player.packetDispatcher.sendURL(VoteSiteConfig.url(site, player.name))
            }
        }
    }

    /** The old vote API's per-site status (wait times); nothing when that API is off. */
    fun requestStatuses(player: Player, onResponse: (Map<VoteSite, VoteSiteStatus>) -> Unit) {
        if (!APIClient.enabled || !VoteAPIService.enabled) return
        val user = player.user ?: return
        VoteAPIService.requestStatuses(user) {
            WorldTasksManager.schedule {
                onResponse(it)
            }
        }
    }

    /** Old vote API votes of [player] waiting to be claimed on [site] (all sites when null). */
    fun unclaimedApiVotes(player: Player, site: VoteSite? = null): Int =
        player.pendingVotesToApplyOnNextLogin.count { site == null || it.siteType == site }

    /** Votes of [player] on [site] the vote screen can claim: old API votes and the website's VOTE_RECEIVED ones. */
    fun votesWaiting(player: Player, site: VoteSite): Int = unclaimedApiVotes(player, site) + player.websiteVotesWaiting(site)

    /** The website told us [player] voted on [site] (game thread): the screen shows Claim on that row. */
    fun onWebsiteVote(player: Player, site: VoteSite?) {
        if (site != null) {
            player.setWebsiteVotesWaiting(site, player.websiteVotesWaiting(site) + 1)
            player.setLastVoteAt(site, System.currentTimeMillis())
        }
        VoteScreen.refreshIfOpen(player)
    }

    /**
     * The vote screen's Claim for [site] (game thread): an old API vote for the site when there is one, else the
     * site's confirmed votes from the website's queue (WebsiteService.claimSiteVotes, the same confirmed claim as ::claim). [done] runs once it's settled.
     */
    fun claim(player: Player, site: VoteSite, done: () -> Unit) {
        val pending = player.pendingVotesToApplyOnNextLogin
        val vote = pending.firstOrNull { it.siteType == site }
        if (vote != null) {
            pending.remove(vote)
            applyVoteReward(player, vote)
            done()
            return
        }
        if (!WebsiteService.isEnabled) {
            player.setWebsiteVotesWaiting(site, 0)
            player.sendMessage("You have no vote from ${site.fancyName} to claim.")
            done()
            return
        }
        WebsiteService.claimSiteVotes(player, site) { given ->
            if (given == 0) {
                player.setWebsiteVotesWaiting(site, 0)
                player.sendMessage("The website has no vote from ${site.fancyName} waiting for you. Votes can take a minute to arrive - try again shortly.")
            }
            done()
        }
    }

    /**
     * Applies a claimed old API vote to a [player] that is genuinely online right now. Must never be called with a
     * player loaded via [com.zenyte.game.world.entity.player.login.LoginManager]'s lightweight offline-load path - that
     * object has no packetDispatcher, BoonManager, etc wired up and nothing saves it afterwards, so granting
     * items/dialogues on it silently NPEs and loses the reward.
     */
    internal fun applyVoteReward(player: Player, vote: Vote) {
        val votePoints = vote.votePointReward
        val benefits = giveVoteBenefits(player, votePoints, vote.siteType)
        if (benefits.points > 0) {
            player.dialogue {
                plain(buildString {
                    append("Your vote on ${vote.siteType.fancyName} has been claimed!")
                    append("<br>You received ${Colour.DARK_BLUE.wrap("${benefits.points} vote point${if (benefits.points > 1) "s" else ""}")}")
                    if (benefits.bonus > 0)
                        append(" (+${Colour.DARK_BLUE.wrap("${benefits.bonus}")} bonus)")
                    append(",<br>${benefits.extras.joinToString(", ")}.")
                    append("<br>Thank you for voting <3")
                })
            }
        }
    }

    class VoteBenefits(val points: Int, val bonus: Int, val tokens: Int, val extras: List<String>)

    /**
     * What one confirmed vote worth [votePoints] on [site] gives in game (game thread): the vote points with the vote
     * bonuses (2x week, well perk, IVoted boon, Uber), the Xamphur world-boss counter, clue rolls, two tomes of
     * experience and the vote perks (VotePerks: blood tokens, the mystery box every 3rd vote, a lottery entry, the
     * skilling streak, the PvM drop rate boost, AFK skilling time). Used by the vote screen's Claim (old API and
     * website votes) and by the website's ::claim (WebsiteService).
     */
    fun giveVoteBenefits(player: Player, votePoints: Int, site: VoteSite? = null): VoteBenefits {
        val votePointsWithBonus = (votePoints * VoteHandler.getVotePointsModifier(player)).toInt()
        val bonusVotePoints = votePointsWithBonus - votePoints
        GameLogger.log {
            GameLogMessage.ClaimedVotes(username = player.username, votesClaimed = votePoints, votesBonus = bonusVotePoints)
        }
        if (votePointsWithBonus <= 0) return VoteBenefits(0, 0, 0, emptyList())
        if (site != null) {
            val waiting = player.websiteVotesWaiting(site)
            if (waiting > 0) player.setWebsiteVotesWaiting(site, waiting - 1)
        }
        XamphurHandler.get()?.addVotes(player, votePoints)
        WiseOldManD.rollClues(player, votePointsWithBonus)
        player.inventory.addOrDrop(ItemId.TOME_OF_EXPERIENCE_30215, 2)
        val extras = ArrayList(VotePerks.onVoteClaimed(player))
        extras += "2 tomes of experience"
        player.totalVoteCredits += votePointsWithBonus
        player.lastVoteClaimTime = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(12)
        player.updateVoteStatisticOnPlayerDetailsTab()
        player.sendMessage(Colour.RS_GREEN.wrap("Vote${site?.let { " on ${it.fancyName}" } ?: ""} claimed: $votePointsWithBonus vote points" +
                (if (bonusVotePoints > 0) " (+$bonusVotePoints bonus)" else "") + ", " + extras.joinToString(", ") + "."))
        peopleVoted++
        if (peopleVoted % 10 == 0) {
            WorldBroadcasts.broadcast(null, BroadcastType.HELPFUL_TIP, "Another 10 people have just voted, type ::vote and get rewards!")
        }
        VoteScreen.refreshIfOpen(player)
        return VoteBenefits(votePointsWithBonus, bonusVotePoints, VotePerks.BLOOD_TOKENS, extras)
    }

    /** A vote from the old vote API: it waits on the vote screen (Claim) until the player claims it. */
    internal fun onVoteReceived(vote: Vote) {
        val user = vote.user
        val onlinePlayer = World.getPlayer(user.name)
        if (onlinePlayer.isPresent) {
            WorldTasksManager.schedule {
                val player = onlinePlayer.get()
                player.pendingVotesToApplyOnNextLogin.add(vote)
                player.setLastVoteAt(vote.siteType, System.currentTimeMillis())
                player.sendMessage(Colour.RS_GREEN.wrap("Your vote on ${vote.siteType.fancyName} was received! Claim it on ::vote."))
                VoteScreen.refreshIfOpen(player)
            }
        } else {
            CoresManager.getLoginManager().load(true, vote.user.name) { offlinePlayer ->
                val player = offlinePlayer.orElse(null)
                if (player == null) {
                    logger.error("Failed to queue vote ${vote.id} for user: ${vote.user.name}, user not found.")
                    return@load
                }
                player.pendingVotesToApplyOnNextLogin.add(vote)
                player.setLastVoteAt(vote.siteType, System.currentTimeMillis())
                CoresManager.getLoginManager().save(player)
                logger.info("Queued vote {} for offline user {}; claimable on the vote screen at the next login.", vote.id, user.name)
            }
        }
    }
}
