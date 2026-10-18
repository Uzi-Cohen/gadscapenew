package com.gadscape.game.content.gamble

import com.zenyte.game.task.WorldTask
import com.zenyte.game.world.entity.attribute
import com.zenyte.game.world.entity.player.Player

/** What a player has set up at the tables this login (bets, slider, autoplay, session totals). */
class GambleState {
    /** Bet size per game and currency, e.g. "dice:PK_POINTS". */
    val bets = HashMap<String, Long>()
    /** Session profit per game and currency. */
    val session = HashMap<String, Long>()

    var diceTarget = 50
    val diceHistory = ArrayDeque<Pair<Double, Boolean>>()
    var diceAuto = false
    val diceAutoBet = AutoBet()

    var minesCount = 3
    var minesAuto = false
    val minesAutoBet = AutoBet()
    val minesAutoTiles = sortedSetOf<Int>()
    var minesAutoCashout = true
    var mines: com.gadscape.game.content.gamble.mines.MinesGame? = null
    var minesCurrency = GambleCurrency.PK_POINTS

    var blackjack: com.gadscape.game.content.gamble.blackjack.BlackjackGame? = null
    var blackjackCurrency = GambleCurrency.PK_POINTS
    var blackjackWins = 0
    var blackjackLosses = 0

    /** Autoplay that's running (dice or mines), so closing the window can stop it. */
    var autoTask: WorldTask? = null
    /** Tick of the last bet, so a click can't place two in one tick. */
    var lastBetTick = -1L

    fun bet(game: String, currency: GambleCurrency): Long = bets["$game:${currency.name}"] ?: defaultBet(currency)

    fun setBet(game: String, currency: GambleCurrency, amount: Long) {
        bets["$game:${currency.name}"] = amount.coerceIn(Gamble.MIN_BET, Gamble.maxBet(currency))
    }

    fun profit(game: String, currency: GambleCurrency): Long = session["$game:${currency.name}"] ?: 0L

    fun addProfit(game: String, currency: GambleCurrency, amount: Long) {
        session.merge("$game:${currency.name}", amount, Long::plus)
    }

    private fun defaultBet(currency: GambleCurrency) = when (currency) {
        GambleCurrency.PK_POINTS -> 10L
        GambleCurrency.COINS -> 100_000L
    }
}

var Player.gambleState: GambleState by attribute("gamble-state") { GambleState() }
