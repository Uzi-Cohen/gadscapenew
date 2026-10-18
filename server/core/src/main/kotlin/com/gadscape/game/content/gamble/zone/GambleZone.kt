package com.gadscape.game.content.gamble.zone

import com.gadscape.game.content.gamble.blackjack.BlackjackTable
import com.gadscape.game.content.gamble.dice.DiceTable
import com.gadscape.game.content.gamble.mines.MinesTable
import com.zenyte.game.GameInterface
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.`object`.ObjectAction
import com.zenyte.game.world.`object`.ObjectHandler
import com.zenyte.game.world.`object`.WorldObject

/**
 * Roat Pkz's gambling zone (region 14137, see [GambleZoneCache]): ::gamble lands at [ARRIVAL], next to the
 * centre statue; flower poker lanes are to the west, the blackjack tables and dice/mines machines to the east.
 */
object GambleZone {

    val ARRIVAL = Location(3552, 3683, 0)

    private var registered = false

    /** Object clicks are registered here rather than through the plugin list, so a deploy picks them up. */
    @JvmStatic
    fun register() {
        if (registered) return
        registered = true
        ObjectHandler.add(GambleObjects::class.java)
    }

    class GambleObjects : ObjectAction {

        override fun getObjects(): Array<Any> = arrayOf(
            GambleZoneCache.DICE_MACHINE, GambleZoneCache.MINES_MACHINE, GambleZoneCache.BLACKJACK_TABLE,
            GambleZoneCache.ATM, GambleZoneCache.EXCHANGE_BOOTH_1, GambleZoneCache.EXCHANGE_BOOTH_2,
            GambleZoneCache.GOLD_CHEST, GambleZoneCache.VIP_REWARDS,
        )

        override fun handleObjectAction(player: Player, `object`: WorldObject, name: String, optionId: Int, option: String) {
            when (`object`.id) {
                GambleZoneCache.DICE_MACHINE -> play(player, option) { DiceTable.open(it) }
                GambleZoneCache.MINES_MACHINE -> play(player, option) { MinesTable.open(it) }
                GambleZoneCache.BLACKJACK_TABLE -> play(player, option) { BlackjackTable.open(it) }
                GambleZoneCache.VIP_REWARDS -> player.sendMessage("Nothing interesting happens.")
                else -> when (option) {
                    "Bank" -> GameInterface.BANK.open(player)
                    "Trading-post", "Trading-Post" -> player.grandExchange.openOffersInterface()
                    else -> player.sendMessage("Nothing interesting happens.")
                }
            }
        }

        private fun play(player: Player, option: String, open: (Player) -> Unit) {
            if (option == "Spectate") {
                player.sendMessage("Spectating isn't available yet.")
                return
            }
            open(player)
        }
    }
}
