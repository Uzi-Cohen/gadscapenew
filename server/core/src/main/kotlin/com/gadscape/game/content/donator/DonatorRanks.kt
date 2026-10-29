package com.gadscape.game.content.donator

import com.gadscape.api.service.user.storeTotalSpent
import com.gadscape.website.claimedPinCents
import com.gadscape.website.donatorPoints
import com.google.common.eventbus.Subscribe
import com.google.gson.JsonObject
import com.zenyte.game.GameInterface
import com.zenyte.game.util.Colour
import com.zenyte.game.world.entity.persistentAttribute
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.privilege.MemberRank
import com.zenyte.plugins.events.LoginEvent
import org.slf4j.LoggerFactory
import java.util.Locale

/**
 * Money the game has counted from ::claim: rewards that carry the purchase's USD amount (`donatedCents`), in cents.
 * Pins aren't in here: a pin counts when it's used ([claimedPinCents]).
 */
var Player.claimedDonationCents: Int by persistentAttribute("donator_claimed_cents", 0)

/**
 * Lifetime total donated, in cents: pins redeemed (as the website reports them) plus purchases claimed in game, or
 * the old store API's total when that is bigger.
 */
val Player.totalDonatedCents: Long
    get() = maxOf(claimedPinCents.toLong() + claimedDonationCents, storeTotalSpent.toLong() * 100)

private var Player.sawNewRanks: Boolean by persistentAttribute("donator_ranks_v2_seen", false)

/**
 * The donator ranks (Roat Pkz's, see [MemberRank]): a rank is bought (DONATOR_RANK in ::claim) or reached by the
 * lifetime total donated, and it never goes down.
 */
object DonatorRanks {

    private val logger = LoggerFactory.getLogger(DonatorRanks::class.java)

    const val REWARD_CODE = "DONATOR_RANK"

    /** What one ::claim reward does to the donator rank. [rank] null = not a rank reward. */
    data class Claim(val rank: MemberRank?, val donatedCents: Int)

    /** [current] after [candidate] is offered: only ever up, and Amascut stays Amascut. */
    @JvmStatic
    fun upgraded(current: MemberRank, candidate: MemberRank): MemberRank =
        if (current == MemberRank.AMASCUT || current.id >= candidate.id) current else candidate

    /**
     * Reads a ::claim reward. A DONATOR_RANK reward names its rank (1..6) in `rank`, `value` or `amount`; any reward
     * but a pin may say how much of the purchase it stands for in `donatedCents` (`amountCents` is read too).
     * Null = a DONATOR_RANK reward without a valid rank, which stays queued.
     */
    @JvmStatic
    fun parseClaim(reward: JsonObject): Claim? {
        val code = reward.text("code")
        val cents = if (code == "PIN") 0 else (reward.number("donatedCents") ?: reward.number("amountCents") ?: 0).coerceAtLeast(0)
        if (code != REWARD_CODE) return Claim(null, cents)
        val number = reward.number("rank") ?: reward.number("value") ?: reward.number("amount")
        val rank = number?.let { MemberRank.forNumber(it) } ?: return null
        return Claim(rank, cents)
    }

    /**
     * A DONATOR_RANK reward: the rank (never lower than the player's), the donator points that come with it and the
     * purchase's donated amount. False = it names no rank 1-6 (keep it queued).
     */
    @JvmStatic
    fun claimRank(player: Player, reward: JsonObject): Boolean {
        val rank = parseClaim(reward)?.rank
        if (rank == null) {
            logger.warn("{} reward for {} has no rank 1-6: {}", REWARD_CODE, player.name, reward)
            return false
        }
        player.donatorPoints += rank.freeDonatorPoints
        if (!upgrade(player, rank, announce = true))
            player.sendMessage(Colour.RS_GREEN.wrap("You claimed $rank; you keep your ${player.memberRank} rank."))
        countDonation(player, reward)
        return true
    }

    /** Adds what a claimed reward says was donated for it (`donatedCents`) to the total, and the rank it reaches. */
    @JvmStatic
    fun countDonation(player: Player, reward: JsonObject) {
        val cents = parseClaim(reward)?.donatedCents ?: 0
        if (cents <= 0) return
        player.claimedDonationCents += cents
        refresh(player, announce = true)
    }

    /** Raises [player] to [candidate] if that's higher. True if the rank changed. */
    @JvmStatic
    fun upgrade(player: Player, candidate: MemberRank, announce: Boolean): Boolean {
        // the saved rank, not a YouTuber's Divine perks (Player.getMemberRank)
        val current = player.storedMemberRank
        val next = upgraded(current, candidate)
        if (next == current) return false
        player.memberRank = next
        logger.info("Donator rank of {}: {} -> {} (total donated {} cents)", player.name, current, next, player.totalDonatedCents)
        if (announce) player.sendMessage(Colour.RS_GREEN.wrap("Congratulations, you are now a ${next.crown().crownTag}$next! Type ::donator to see your perks."))
        updateNoticeboard(player)
        return true
    }

    /** Gives [player] the rank their total donated reaches, if that's higher than theirs. */
    @JvmStatic
    fun refresh(player: Player, announce: Boolean): Boolean {
        val changed = upgrade(player, MemberRank.forTotalCents(player.totalDonatedCents), announce)
        if (!changed) updateNoticeboard(player)
        return changed
    }

    @JvmStatic
    fun formatUsd(cents: Long): String = "$" + String.format(Locale.ROOT, "%,.2f", cents / 100.0)

    /** The rank and total donated lines of the Game Noticeboard (journal tab). */
    @JvmStatic
    fun updateNoticeboard(player: Player) {
        if (!player.isLoggedIn) return
        GameInterface.GAME_NOTICEBOARD.plugin.ifPresent { plugin ->
            player.packetDispatcher.sendComponentText(GameInterface.GAME_NOTICEBOARD, plugin.getComponent("Member Rank"),
                "Member: " + Colour.WHITE.wrap(player.memberCrown.crownTag + player.memberName))
            player.packetDispatcher.sendComponentText(GameInterface.GAME_NOTICEBOARD, plugin.getComponent("Total donated"),
                "Total donated: " + Colour.WHITE.wrap(formatUsd(player.totalDonatedCents)))
        }
    }

    @Subscribe
    @JvmStatic
    fun onLogin(event: LoginEvent) {
        val player = event.player
        refresh(player, announce = true)
        if (player.memberRank.isDonator && !player.sawNewRanks) {
            player.sawNewRanks = true
            player.sendMessage(Colour.RS_GREEN.wrap("Donator ranks are now Gad Donator, Super, Extreme, Legendary, Royal and Divine. " +
                    "You are a ${player.memberRank}: type ::donator to see your perks."))
        }
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.number(key: String): Int? =
        get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() }
}
