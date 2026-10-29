package com.gadscape.game.model.ui.vote

import com.gadscape.api.model.VoteSite
import com.gadscape.api.service.vote.VotePlayerHandler
import com.gadscape.api.service.vote.VoteSiteConfig
import com.gadscape.api.service.vote.lastVoteAt
import com.gadscape.api.service.vote.votesClaimed
import com.gadscape.game.content.vote.VoteLottery
import com.gadscape.game.content.vote.VotePerks
import com.gadscape.game.item.CustomItemId
import com.gadscape.website.WebsiteService
import com.zenyte.game.GameInterface
import com.zenyte.game.content.xamphur.XamphurHandler
import com.zenyte.game.model.ui.Interface
import com.zenyte.game.world.entity.player.Player
import mgi.types.component.ComponentDefinitions
import mgi.types.config.items.ItemDefinitions

/**
 * Interface and component ids of the vote screen, as the cache patch makes them (editor utils.VotePatch, object
 * VotePatch.Ids; `java -cp <editor jar> utils.VotePatch --server-ids` prints them). Both must say the same.
 */
object VoteUi {
    const val ID = 5260
    const val REFRESH = 2
    const val ROWS = 6
    const val ROW_FIRST = 3
    const val ROW_STRIDE = 36
    const val NAME = 10
    const val POINTS = 21
    const val STATUS = 22
    const val BUTTON_LABEL = 33
    const val BUTTON_VOTE = 34
    const val BUTTON_CLAIM = 35
    const val PERKS = 6
    const val PERK_FIRST = 221
    const val PERK_STRIDE = 8
    const val PERK_ITEM = 3
    const val PERK_STATUS = 7
    const val FOOTER = 269

    fun row(k: Int) = ROW_FIRST + k * ROW_STRIDE
    fun perk(k: Int) = PERK_FIRST + k * PERK_STRIDE
}

/** The vote screen (5260): see [VoteScreen]. */
@Suppress("unused")
class VoteInterface : Interface() {
    override fun attach() {}
    override fun build() {}
    override fun getDefaultHandler() = DefaultClickHandler { player, componentId, _, _, _ -> VoteScreen.click(player, componentId) }
    override fun getInterface(): GameInterface = GameInterface.VOTE_SCREEN

    override fun open(player: Player) {
        player.interfaceHandler.sendInterface(this)
        VoteScreen.requestStatuses(player)
        VoteScreen.render(player)
    }
}

/**
 * ::vote: one row per toplist (VoteSite order) with its points and state, and Vote (opens the site) / Claim (takes
 * the site's waiting vote and gives its rewards, VotePlayerHandler.claim) / Voted (12-hour wait); under them the
 * vote perks with the player's own numbers, and the vote total. Without the screen in the cache, ::vote opens the
 * website's vote page as before.
 */
object VoteScreen {

    private const val ITEM_ZOOM = 100
    private const val REFRESH_TICKS_KEY = "vote_screen_refreshed"
    private const val CLAIMING_KEY = "vote_screen_claiming"
    private const val API_WAITS_KEY = "vote_screen_api_waits"
    private const val API_POINTS_KEY = "vote_screen_api_points"
    private const val REFRESH_COOLDOWN_MILLIS = 3_000L

    /** Perk icons: mystery box, (the lottery prize), skilling, Xamphur, drop rate, AFK skilling. */
    private const val SKILLING_ICON = 11640
    private const val XAMPHUR_ICON = 22299
    private const val DROP_RATE_ICON = 2572
    private const val AFK_ICON = 1275
    private const val MYSTERY_BOX = 6199

    /** True when the cache has the vote screen (VotePatch). */
    val available: Boolean by lazy {
        try {
            ComponentDefinitions.containsInterface(VoteUi.ID)
        } catch (e: Exception) {
            false
        }
    }

    /** ::vote */
    @JvmStatic
    fun open(player: Player) {
        if (!available) {
            WebsiteService.commandVote(player)
            return
        }
        GameInterface.VOTE_SCREEN.open(player)
    }

    fun refreshIfOpen(player: Player) {
        if (available && player.interfaceHandler.isVisible(VoteUi.ID)) render(player)
    }

    /** Old vote API only: the sites' own wait times and points, then the screen again. */
    fun requestStatuses(player: Player) {
        VotePlayerHandler.requestStatuses(player) { statuses ->
            val now = System.currentTimeMillis()
            player.temporaryAttributes[API_WAITS_KEY] = statuses.mapValues { now + it.value.secondsTillCanVote * 1000L }
            player.temporaryAttributes[API_POINTS_KEY] = statuses.mapValues { it.value.voteReward }
            refreshIfOpen(player)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun apiWaits(player: Player) = player.temporaryAttributes[API_WAITS_KEY] as? Map<VoteSite, Long>

    @Suppress("UNCHECKED_CAST")
    private fun apiPoints(player: Player) = player.temporaryAttributes[API_POINTS_KEY] as? Map<VoteSite, Int>

    /** How long [player] still waits before voting on [site] again (0 = can vote). */
    private fun waitLeft(player: Player, site: VoteSite, now: Long): Long {
        val until = apiWaits(player)?.get(site) ?: (player.lastVoteAt(site) + VotePlayerHandler.VOTE_COOLDOWN_MILLIS)
        return (until - now).coerceAtLeast(0)
    }

    fun render(player: Player) {
        val d = player.packetDispatcher
        val now = System.currentTimeMillis()
        val id = VoteUi.ID
        for ((k, site) in VoteSite.entries.withIndex()) {
            if (k >= VoteUi.ROWS) break
            val base = VoteUi.row(k)
            val enabled = VoteSiteConfig.isEnabled(site)
            val waiting = VotePlayerHandler.votesWaiting(player, site)
            val wait = waitLeft(player, site, now)
            val points = apiPoints(player)?.get(site) ?: site.defaultPoints
            d.sendComponentText(id, base + VoteUi.NAME, (if (enabled) "<col=ffffff>" else "<col=808080>") + site.fancyName)
            d.sendComponentText(id, base + VoteUi.POINTS, (if (enabled) "<col=ff981f>" else "<col=7f3f3f>") + "$points Vote Points")
            val (status, label, button) = when {
                waiting > 0 -> Triple("<col=ffff00>" + if (waiting == 1) "Vote received - claim it!" else "$waiting votes received - claim them!",
                    "<col=ffff00>Claim", Button.CLAIM)
                !enabled -> Triple("<col=ff0000>this site is disabled", "<col=7f3f3f>Vote", Button.VOTE)
                wait > 0 -> Triple("<col=c8c8c8>Next vote in ${VotePerks.formatTime(wait)}", "<col=9f9f9f>Voted", Button.NONE)
                else -> Triple("<col=00ff00>You can vote now!", "<col=00ff00>Vote", Button.VOTE)
            }
            d.sendComponentText(id, base + VoteUi.STATUS, status)
            d.sendComponentText(id, base + VoteUi.BUTTON_LABEL, label)
            d.sendComponentVisibility(id, base + VoteUi.BUTTON_VOTE, button != Button.VOTE)
            d.sendComponentVisibility(id, base + VoteUi.BUTTON_CLAIM, button != Button.CLAIM)
        }

        val box = if (ItemDefinitions.get(CustomItemId.VOTE_MYSTERY_BOX) != null) CustomItemId.VOTE_MYSTERY_BOX else MYSTERY_BOX
        val untilBox = VotePerks.votesUntilBox(player)
        perk(player, 0, box, "Next box: <col=00ff00>$untilBox vote${if (untilBox == 1) "" else "s"}")
        perk(player, 1, VoteLottery.prize.id, "Your win chance: <col=00ff00>${"%.1f".format(VoteLottery.winChance(player.username))}%")
        val days = VotePerks.streakDays(player)
        perk(player, 2, SKILLING_ICON, "Your boost: <col=00ff00>${VotePerks.streakPercent(player)}%</col> ($days day${if (days == 1) "" else "s"})")
        val xamphur = XamphurHandler.get()
        perk(player, 3, XAMPHUR_ICON, if (xamphur == null) "Next spawn: <col=ff0000>off" else "Next spawn: <col=00ff00>${xamphur.amtTillSpawn()} votes")
        perk(player, 4, DROP_RATE_ICON, "Time left: " + timeLeft(VotePerks.dropBoostLeft(player)))
        perk(player, 5, AFK_ICON, "Time left: " + timeLeft(VotePerks.afkTimeLeft(player)))
        val total = player.votesClaimed
        d.sendComponentText(id, VoteUi.FOOTER, "You have voted a total of $total time${if (total == 1) "" else "s"}")
    }

    private enum class Button { VOTE, CLAIM, NONE }

    private fun timeLeft(millis: Long) = if (millis <= 0) "<col=ff0000>none" else "<col=00ff00>${VotePerks.formatTime(millis)}"

    private fun perk(player: Player, k: Int, itemId: Int, status: String) {
        val panel = VoteUi.perk(k)
        player.packetDispatcher.sendComponentItem(VoteUi.ID, panel + VoteUi.PERK_ITEM, itemId, ITEM_ZOOM)
        player.packetDispatcher.sendComponentText(VoteUi.ID, panel + VoteUi.PERK_STATUS, status)
    }

    /** A click on the vote screen. */
    fun click(player: Player, component: Int) {
        if (component == VoteUi.REFRESH) {
            val now = System.currentTimeMillis()
            val last = player.temporaryAttributes[REFRESH_TICKS_KEY] as? Long ?: 0L
            if (now - last < REFRESH_COOLDOWN_MILLIS) return
            player.temporaryAttributes[REFRESH_TICKS_KEY] = now
            requestStatuses(player)
            render(player)
            return
        }
        val offset = component - VoteUi.ROW_FIRST
        if (offset < 0 || offset >= VoteUi.ROWS * VoteUi.ROW_STRIDE) return
        val site = VoteSite.entries.getOrNull(offset / VoteUi.ROW_STRIDE) ?: return
        when (offset % VoteUi.ROW_STRIDE) {
            VoteUi.BUTTON_VOTE -> VotePlayerHandler.startVote(player, site)
            VoteUi.BUTTON_CLAIM -> {
                if (player.temporaryAttributes[CLAIMING_KEY] == true) return
                player.temporaryAttributes[CLAIMING_KEY] = true
                VotePlayerHandler.claim(player, site) {
                    player.temporaryAttributes.remove(CLAIMING_KEY)
                    refreshIfOpen(player)
                }
            }
        }
    }
}
