package com.gadscape.game.content.gamble

import com.gadscape.game.content.gamble.ui.RoatUi
import com.gadscape.game.content.ui.ClientScriptsCache
import com.gadscape.game.world.entity.player.pkPoints
import com.zenyte.game.content.flowerpoker.GambleBan
import com.zenyte.game.item.Item
import com.zenyte.game.packet.out.IfSetPosition
import com.zenyte.game.world.entity.persistentAttribute
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.region.area.wilderness.WildernessArea
import org.slf4j.LoggerFactory
import java.text.DecimalFormat
import java.text.NumberFormat

/**
 * What the house games (dice, mines, blackjack) are played with. Roat Pkz bets Donator Points or Game Credits;
 * GadScape has PK points and coins, switched with Roat's "(change currency)" button.
 */
enum class GambleCurrency(val long: String, val short: String) {
    PK_POINTS("PK Points", "PKP") {
        override fun balance(player: Player) = player.pkPoints.toLong()
        override fun take(player: Player, amount: Long): Boolean {
            if (amount <= 0 || player.pkPoints < amount) return false
            player.pkPoints -= amount.toInt()
            return true
        }
        override fun give(player: Player, amount: Long) {
            player.pkPoints = (player.pkPoints + amount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    },
    COINS("Coins", "GP") {
        override fun balance(player: Player) = player.inventory.getAmountOf(COINS_ID).toLong()
        override fun take(player: Player, amount: Long): Boolean {
            if (amount <= 0 || balance(player) < amount) return false
            player.inventory.deleteItem(Item(COINS_ID, amount.toInt()))
            return true
        }
        override fun give(player: Player, amount: Long) {
            var left = amount
            val room = Int.MAX_VALUE.toLong() - balance(player)
            val coins = minOf(left, room)
            if (coins > 0) player.inventory.addOrDrop(COINS_ID, coins.toInt())
            left -= coins
            if (left > 0) {
                // a full coin stack: the rest as platinum tokens (1,000 coins each)
                val tokens = left / 1000
                if (tokens > 0) player.inventory.addOrDrop(PLATINUM_TOKEN, tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                player.sendMessage("Your coin stack is full, so ${Gamble.amount(tokens)} platinum tokens were added instead.")
            }
        }
    };

    abstract fun balance(player: Player): Long

    /** Takes [amount] if the player has it all; changes nothing and returns false otherwise. */
    abstract fun take(player: Player, amount: Long): Boolean

    abstract fun give(player: Player, amount: Long)

    companion object {
        const val COINS_ID = 995
        const val PLATINUM_TOKEN = 13204
    }
}

/** Saved per player: provably-fair seeds and the game counter, so a nonce never repeats for a seed pair. */
var Player.gambleClientSeed: String by persistentAttribute("gamble-client-seed", "")
var Player.gambleServerSeed: String by persistentAttribute("gamble-server-seed", "")
var Player.gambleNonce: Int by persistentAttribute("gamble-nonce", 0)
var Player.gambleCurrency: Int by persistentAttribute("gamble-currency", 0)

/** Shared plumbing for the house games. */
object Gamble {

    private val log = LoggerFactory.getLogger("gambling")

    const val MIN_BET = 1L

    /** Biggest bet per currency (the max win is capped by the games: 250,000x on mines). */
    fun maxBet(currency: GambleCurrency) = when (currency) {
        GambleCurrency.PK_POINTS -> 1_000_000L
        GambleCurrency.COINS -> 500_000_000L
    }

    fun currency(player: Player) = GambleCurrency.entries.getOrElse(player.gambleCurrency) { GambleCurrency.PK_POINTS }

    fun switchCurrency(player: Player): GambleCurrency {
        val next = GambleCurrency.entries[(currency(player).ordinal + 1) % GambleCurrency.entries.size]
        player.gambleCurrency = next.ordinal
        return next
    }

    /**
     * The player's seeds. Calls [use] with them and saves the nonce afterwards, so it never repeats even if the
     * server stops mid-game.
     */
    fun <T> withSeeds(player: Player, use: (PlayerSeeds) -> T): T {
        val seeds = seeds(player)
        val result = use(seeds)
        save(player, seeds)
        return result
    }

    fun seeds(player: Player): PlayerSeeds {
        if (player.gambleClientSeed.isEmpty() || player.gambleServerSeed.isEmpty()) {
            val fresh = PlayerSeeds()
            save(player, fresh)
            return fresh
        }
        return PlayerSeeds(player.gambleClientSeed, player.gambleServerSeed, player.gambleNonce)
    }

    fun save(player: Player, seeds: PlayerSeeds) {
        player.gambleClientSeed = seeds.clientSeed()
        player.gambleServerSeed = seeds.serverSeed()
        player.gambleNonce = seeds.nonce()
    }

    /** Why the player can't gamble right now, or null if they can. */
    fun refusal(player: Player): String? = when {
        GambleBan.isGambleBanValid(player) -> "You are currently gamble banned and cannot gamble."
        player.isIronman -> "Ironmen can't gamble."
        WildernessArea.isWithinWilderness(player) -> "You can't gamble in the Wilderness."
        player.isUnderCombat -> "You can't gamble while in combat."
        player.isLocked -> "You can't do that right now."
        else -> null
    }

    fun log(player: Player, game: String, currency: GambleCurrency, bet: Long, payout: Long, detail: String) {
        log.info("{} {}: bet {} {}, paid {} ({}), nonce {}", player.username, game, bet, currency.short, payout, detail, player.gambleNonce - 1)
    }

    // ---- formatting ------------------------------------------------------------------------------------------

    fun amount(n: Long): String = NumberFormat.getIntegerInstance().format(n)

    /** Roat style short amounts: 999, 1.5K, 25M, 1.2B. */
    fun short(n: Long): String {
        val a = Math.abs(n)
        val s = when {
            a >= 1_000_000_000 -> trim(a / 1e9) + "B"
            a >= 1_000_000 -> trim(a / 1e6) + "M"
            a >= 10_000 -> trim(a / 1e3) + "K"
            else -> amount(a)
        }
        return if (n < 0) "-$s" else s
    }

    private fun trim(v: Double) = DecimalFormat("#.##").format(v)

    fun multiplier(m: Double): String = DecimalFormat("0.00").format(m) + "x"

    /** "Your current session: +1,000 PK Points." in Roat's colours. */
    fun session(profit: Long, currency: GambleCurrency) =
        "<col=ffffff>Your current session: " + (if (profit >= 0) "<col=00ff00>+" else "<col=ff0000>-") +
            amount(Math.abs(profit)) + " " + currency.long + "."

    /** Parses "100", "1.5k", "25m", "1b". */
    fun parseAmount(text: String): Long? {
        val t = text.trim().lowercase().replace(",", "")
        if (t.isEmpty()) return null
        val mult = when (t.last()) {
            'k' -> 1_000L
            'm' -> 1_000_000L
            'b' -> 1_000_000_000L
            else -> 1L
        }
        val num = if (mult == 1L) t else t.dropLast(1)
        return num.toDoubleOrNull()?.let { (it * mult).toLong() }?.takeIf { it > 0 }
    }
}

/** Sends updates to one of the Roat-built interfaces, addressed by Roat component ids. */
class GambleView(val player: Player, val ui: RoatUi) {

    private val d get() = player.packetDispatcher

    fun text(roatId: Int, text: String) = d.sendComponentText(ui.interfaceId, ui.c(roatId), RoatUi.convert(text))

    fun hide(roatId: Int, hidden: Boolean) = d.sendComponentVisibility(ui.interfaceId, ui.c(roatId), hidden)

    fun show(roatId: Int) = hide(roatId, false)

    fun hide(roatId: Int) = hide(roatId, true)

    fun sprite(roatId: Int, spriteId: Int) = d.sendClientScript(ClientScriptsCache.SET_GRAPHIC, spriteId, ui.packed(roatId))

    /** Moves a component; [x], [y] are Roat child coordinates (top-left), like Roat's newChild. */
    fun move(roatId: Int, x: Int, y: Int) = player.send(IfSetPosition(ui.interfaceId, ui.c(roatId), x, y))

    /** Moves a centred text so it's centred on [centreX] (texts are 480 wide, see RoatUi.text). */
    fun moveCentredText(roatId: Int, centreX: Int, y: Int) = move(roatId, centreX - 240, y)
}
