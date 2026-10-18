package com.gadscape.game.content.kits

import com.gadscape.api.service.user.storeTotalSpent
import com.gadscape.api.service.vote.totalVoteCredits
import com.gadscape.game.content.accounttype.accountType
import com.gadscape.game.world.entity.player.pkPoints
import com.gadscape.game.world.entity.player.pvpDeaths
import com.gadscape.game.world.entity.player.pvpKillStreak
import com.gadscape.game.world.entity.player.pvpKills
import com.zenyte.game.GameInterface
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.player.Player
import java.text.NumberFormat
import java.util.Locale

/** The Main page of the side panel: server, PK, points and account lines, like Roat Pkz's main tab. */
object SidePanel {

    private const val PAGE_KEY = "side_panel_page"
    const val MAIN = 0
    const val KITS = 1

    private fun green(value: Any) = "<col=3ed32d>$value</col>"
    private fun n(value: Int) = NumberFormat.getIntegerInstance(Locale.US).format(value)

    @JvmStatic
    fun page(player: Player): Int = player.temporaryAttributes[PAGE_KEY] as? Int ?: MAIN

    @JvmStatic
    fun show(player: Player, page: Int) {
        player.temporaryAttributes[PAGE_KEY] = page
        val d = player.packetDispatcher
        val tab = GameInterface.MUSIC_TAB
        d.sendComponentVisibility(tab, KitsTabCache.MAIN_LAYER, page != MAIN)
        d.sendComponentVisibility(tab, KitsTabCache.KITS_LAYER, page != KITS)
        d.sendComponentVisibility(tab, KitsTabCache.MAIN_ON, page != MAIN)
        d.sendComponentVisibility(tab, KitsTabCache.KITS_ON, page != KITS)
        if (page == MAIN) refreshMain(player)
    }

    @JvmStatic
    fun refreshMain(player: Player) {
        val kills = player.pvpKills
        val deaths = player.pvpDeaths
        val kdr = if (deaths == 0) kills.toDouble() else kills.toDouble() / deaths
        val lines = listOf(
            "<col=fff71f>Server Data</col>",
            "  - Players Online: ${green(World.getPlayers().size)}",
            "<col=fff71f>Pk Data</col>",
            "  - Kills: ${green(n(kills))}",
            "  - Deaths: ${green(n(deaths))}",
            "  - K/D Ratio: ${green("%.2f".format(kdr))}",
            "  - Current Killstreak: ${green(player.pvpKillStreak)}",
            "<col=fff71f>Your Points</col>",
            "  - Pk Points: ${green(n(player.pkPoints))}",
            "  - Vote Points: ${green(player.totalVoteCredits)}",
            "<col=fff71f>Misc Data</col>",
            "  - Donator Rank: ${green(player.memberRank.toString())}",
            "  - Total Donated: ${green("$" + player.storeTotalSpent)}",
            "  - Account: ${green(player.accountType?.title ?: player.gameMode.toString())}",
            "",
        )
        val d = player.packetDispatcher
        for (i in 0 until KitsTabCache.MAIN_LINE_COUNT) {
            d.sendComponentText(GameInterface.MUSIC_TAB, KitsTabCache.MAIN_LINES + i, lines.getOrElse(i) { "" })
        }
    }
}
