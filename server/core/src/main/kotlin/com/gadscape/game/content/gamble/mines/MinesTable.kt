package com.gadscape.game.content.gamble.mines

import com.gadscape.game.content.gamble.Gamble
import com.gadscape.game.content.gamble.GambleInterfaces
import com.gadscape.game.content.gamble.GambleView
import com.gadscape.game.content.gamble.ProvablyFairActions
import com.gadscape.game.content.gamble.gambleState
import com.gadscape.game.content.gamble.ui.MinesUi
import com.gadscape.game.content.gamble.ui.RoatSprites
import com.zenyte.game.GameInterface
import com.zenyte.game.task.WorldTask
import com.zenyte.game.task.WorldTasksManager
import java.util.concurrent.ThreadLocalRandom
import com.zenyte.game.world.WorldThread
import com.zenyte.game.world.entity.player.Player
import java.text.DecimalFormat

/**
 * The mines machine, played like Roat Pkz's: pick 1-24 mines, bet, reveal gems to raise the multiplier
 * (2% edge per reveal, see [MinesGame]), cash out any time; a mine loses the bet. Autoplay picks tiles up front.
 */
object MinesTable {

    private const val GAME = "mines"
    private val TWO = DecimalFormat("0.00")

    fun main(player: Player) = GambleView(player, MinesUi.main)
    fun side(player: Player) = GambleView(player, MinesUi.side)

    private fun game(player: Player) = player.gambleState.mines?.takeIf { it.running() }

    fun open(player: Player) {
        Gamble.refusal(player)?.let { player.sendMessage(it); return }
        GambleInterfaces.register()
        player.interfaceHandler.closeInterfaces()
        GameInterface.GAMBLE_MINES.open(player)
        GameInterface.GAMBLE_MINES_SIDE.open(player)
        val main = main(player)
        main.hide(MinesUi.RULES); main.hide(MinesUi.RULES_DIM); main.hide(MinesUi.FAIR)
        main.show(MinesUi.BACKGROUND)
        hideWinPanel(player)
        player.gambleState.mines = null
        resetBoard(player)
        setTab(player, player.gambleState.minesAuto)
        refresh(player)
    }

    // ---- drawing ---------------------------------------------------------------------------------------------

    fun refresh(player: Player) {
        val state = player.gambleState
        val game = game(player)
        val currency = if (game != null) state.minesCurrency else Gamble.currency(player)
        val bet = game?.bet() ?: state.bet(GAME, currency)
        val side = side(player)
        for (offset in intArrayOf(0, MinesUi.AUTO)) {
            side.text(offset + MinesUi.BALANCE, "@whi@Balance: @gre@" + Gamble.short(currency.balance(player)) + " " + currency.short)
            side.text(offset + MinesUi.BET_AMOUNT, "  " + Gamble.amount(bet) + " " + currency.short)
        }
        val mines = game?.mines() ?: state.minesCount
        side.text(MinesUi.MINES_PICKER_TEXT, "@yel@$mines")
        side.text(MinesUi.AUTO_MINES_PICKER_TEXT, "@yel@$mines")
        val multiplier = game?.currentMultiplier() ?: 1.0
        side.text(MinesUi.PROFIT_LABEL, "Total Profit (@gre@" + TWO.format(multiplier) + "x@whi@):")
        side.text(MinesUi.PROFIT, Gamble.amount((bet * multiplier).toLong() - if (game == null) 0 else bet) + " " + currency.long)

        // playing: gems/mines left instead of the picker, cashout instead of bet
        val playing = game != null
        side.hide(MinesUi.MINES_PICKER, playing)
        side.hide(MinesUi.MINES_PICKER_TEXT, playing)
        side.hide(MinesUi.COUNTS, !playing)
        side.hide(MinesUi.GEMS_LEFT, !playing)
        side.hide(MinesUi.MINES_LEFT, !playing)
        if (game != null) {
            side.text(MinesUi.GEMS_LEFT, "@cya@" + (MinesGame.TILES - game.mines() - game.gems()))
            side.text(MinesUi.MINES_LEFT, "@yel@" + game.mines())
        }
        side.hide(MinesUi.BET, playing)
        side.hide(MinesUi.STATE + MinesUi.BET)
        side.hide(MinesUi.CASHOUT, !playing)
        side.hide(MinesUi.STATE + MinesUi.CASHOUT, game == null || game.gems() > 0)
        side.hide(MinesUi.STATE + MinesUi.RANDOM_TILE, playing)

        main(player).text(MinesUi.SESSION, Gamble.session(state.profit(GAME, currency), currency))
        refreshStrip(player)
        refreshAuto(player)
    }

    /** The multiplier strip along the top: the next seven reveals, the reached ones dimmed. */
    private fun refreshStrip(player: Player) {
        val state = player.gambleState
        val game = game(player)
        val mines = game?.mines() ?: state.minesCount
        val gems = game?.gems() ?: 0
        val first = maxOf(1, gems - 2)
        val main = main(player)
        for (i in 0 until MinesUi.STRIP_SLOTS) {
            val k = first + i
            val slot = MinesUi.STRIP + 1 + i * 2
            if (k > MinesGame.TILES - mines) {
                main.hide(slot)
                main.text(slot + 1, "")
                continue
            }
            main.show(slot)
            main.sprite(slot, RoatSprites.id("MinesInterface/top_part " + if (k <= gems) 1 else 0))
            main.text(slot + 1, (if (k <= gems) "@gr2@" else "") + Gamble.multiplier(MinesGame.multiplier(mines, k)))
        }
    }

    private fun resetBoard(player: Player) {
        val main = main(player)
        for (t in 0 until MinesUi.TILES) {
            main.hide(MinesUi.TILE_STATE + t)
            main.hide(MinesUi.AUTO_PLAYING + t)
        }
        refreshAutoPicks(player)
    }

    private fun showTile(player: Player, tile: Int, look: Int) {
        val main = main(player)
        main.sprite(MinesUi.TILE_STATE + tile, MinesUi.tileSprite(look))
        main.show(MinesUi.TILE_STATE + tile)
    }

    private fun revealBoard(player: Player, game: MinesGame) {
        for (t in 0 until MinesUi.TILES) showTile(player, t, game.endSprite(t))
        main(player).let { m -> for (t in 0 until MinesUi.TILES) m.hide(MinesUi.AUTO_PLAYING + t) }
    }

    // ---- playing ---------------------------------------------------------------------------------------------

    fun bet(player: Player): Boolean {
        val state = player.gambleState
        if (!player.interfaceHandler.isPresent(GameInterface.GAMBLE_MINES)) return false
        Gamble.refusal(player)?.let { player.sendMessage(it); return false }
        if (game(player) != null || state.lastBetTick == WorldThread.WORLD_CYCLE) return false
        val currency = Gamble.currency(player)
        val bet = state.bet(GAME, currency)
        if (bet < Gamble.MIN_BET || bet > Gamble.maxBet(currency)) {
            player.sendMessage("You can bet between ${Gamble.amount(Gamble.MIN_BET)} and ${Gamble.amount(Gamble.maxBet(currency))} ${currency.long}.")
            return false
        }
        if (!currency.take(player, bet)) {
            player.sendMessage("You don't have ${Gamble.amount(bet)} ${currency.long} to bet.")
            return false
        }
        state.lastBetTick = WorldThread.WORLD_CYCLE
        state.minesCurrency = currency
        state.mines = Gamble.withSeeds(player) { MinesGame.start(it, bet, state.minesCount) }
        hideWinPanel(player)
        resetBoard(player)
        refresh(player)
        return true
    }

    fun tile(player: Player, tile: Int) {
        val state = player.gambleState
        if (state.minesAuto && !state.minesAutoBet.running()) {
            // auto tab: clicking tiles picks them for autoplay
            if (!state.minesAutoTiles.remove(tile)) {
                if (state.minesAutoTiles.size >= MinesGame.TILES - state.minesCount) {
                    player.sendMessage("You can't pick more tiles than there are gems.")
                    return
                }
                state.minesAutoTiles += tile
            }
            refreshAutoPicks(player)
            refresh(player)
            return
        }
        val game = game(player) ?: return
        if (state.minesAutoBet.running()) return
        reveal(player, game, tile)
    }

    fun randomTile(player: Player) {
        val game = game(player) ?: return
        if (player.gambleState.minesAutoBet.running()) return
        reveal(player, game, game.randomUnrevealedTile(ThreadLocalRandom.current()))
    }

    private fun reveal(player: Player, game: MinesGame, tile: Int) {
        when (game.reveal(tile)) {
            MinesGame.Reveal.GEM -> {
                showTile(player, tile, 1)
                if (!game.running()) finish(player, game) else refresh(player)
            }
            MinesGame.Reveal.MINE -> {
                showTile(player, tile, 2)
                finish(player, game)
            }
            else -> {}
        }
    }

    fun cashout(player: Player) {
        val game = game(player) ?: return
        if (game.gems() == 0) {
            player.sendMessage("Reveal at least one gem before cashing out.")
            return
        }
        if (player.gambleState.minesAutoBet.running()) return
        finish(player, game)
    }

    /** Ends the round: pays out (nothing after a mine), shows the board and Roat's win panel. */
    private fun finish(player: Player, game: MinesGame): Long {
        val state = player.gambleState
        val currency = state.minesCurrency
        val payout = game.cashout()
        if (payout > 0) currency.give(player, payout)
        state.addProfit(GAME, currency, payout - game.bet())
        Gamble.log(player, GAME, currency, game.bet(), payout, "${game.mines()} mines, ${game.gems()} gems" + if (game.lost()) ", hit a mine" else "")
        lastGame[player.username] = game
        revealBoard(player, game)
        state.mines = null
        if (game.lost()) {
            player.sendMessage("You hit a mine and lost ${Gamble.amount(game.bet())} ${currency.long}.")
        } else {
            player.sendMessage("<col=00a000>You cashed out at ${Gamble.multiplier(game.currentMultiplier())} for ${Gamble.amount(payout)} ${currency.long}!</col>")
            if (!state.minesAutoBet.running()) showWinPanel(player, game.currentMultiplier(), payout, currency.long)
        }
        refresh(player)
        return payout
    }

    private fun showWinPanel(player: Player, multiplier: Double, payout: Long, currency: String) {
        val main = main(player)
        main.text(MinesUi.WIN_MULTIPLIER, Gamble.multiplier(multiplier))
        main.text(MinesUi.WIN_AMOUNT, Gamble.amount(payout) + " " + currency)
        for (id in WIN_PARTS) main.show(id)
    }

    private fun hideWinPanel(player: Player) {
        val main = main(player)
        for (id in WIN_PARTS) main.hide(id)
    }

    private val WIN_PARTS = intArrayOf(MinesUi.DIM, MinesUi.WIN_PANEL, MinesUi.WIN_TITLE, MinesUi.WIN_MULTIPLIER, MinesUi.WIN_AMOUNT, MinesUi.WIN_CLOSE)

    fun closeWinPanel(player: Player) {
        hideWinPanel(player)
        resetBoard(player)
    }

    val lastGame = HashMap<String, MinesGame>()

    // ---- settings --------------------------------------------------------------------------------------------

    fun setTab(player: Player, auto: Boolean) {
        val state = player.gambleState
        if (game(player) != null) {
            player.sendMessage("Finish your game first.")
            return
        }
        if (!auto && state.minesAutoBet.running()) stopAuto(player)
        state.minesAuto = auto
        val side = side(player)
        side.hide(MinesUi.MANUAL, auto)
        side.hide(MinesUi.AUTO_TAB, !auto)
        side.text(MinesUi.MODE, "@cu1@mode: @cu2@" + if (auto) "auto" else "manual")
        main(player).hide(MinesUi.AUTO_LABEL, !auto)
        main(player).hide(MinesUi.SESSION, auto)
        refreshAutoPicks(player)
    }

    fun switchCurrency(player: Player) {
        if (game(player) != null || player.gambleState.minesAutoBet.running()) return
        val currency = Gamble.switchCurrency(player)
        player.sendMessage("You're now betting ${currency.long}.")
        refresh(player)
    }

    fun changeBet(player: Player, factor: Double) {
        if (game(player) != null) return
        val currency = Gamble.currency(player)
        val state = player.gambleState
        val bet = (state.bet(GAME, currency) * factor).toLong().coerceAtLeast(Gamble.MIN_BET)
        state.setBet(GAME, currency, minOf(bet, maxOf(Gamble.MIN_BET, currency.balance(player))))
        refresh(player)
    }

    fun enterBet(player: Player) {
        if (game(player) != null) return
        player.sendInputString("Enter your bet (e.g. 500, 25k, 1.5m):") { text ->
            val amount = Gamble.parseAmount(text) ?: return@sendInputString player.sendMessage("That's not a valid amount.")
            if (game(player) != null) return@sendInputString
            player.gambleState.setBet(GAME, Gamble.currency(player), amount)
            refresh(player)
        }
    }

    fun enterMines(player: Player) {
        if (game(player) != null || player.gambleState.minesAutoBet.running()) return
        player.sendInputInt("How many mines? (1-24)") { n ->
            if (game(player) != null) return@sendInputInt
            val state = player.gambleState
            state.minesCount = n.coerceIn(1, 24)
            while (state.minesAutoTiles.size > MinesGame.TILES - state.minesCount) state.minesAutoTiles.remove(state.minesAutoTiles.last())
            refreshAutoPicks(player)
            refresh(player)
        }
    }

    fun showRules(player: Player, show: Boolean) {
        val main = main(player)
        main.hide(MinesUi.RULES, !show)
        main.hide(MinesUi.RULES_DIM, !show)
    }

    /** Roat's "Click Here to View Mine Rates!": the multipliers for the chosen mine count. */
    fun showRates(player: Player) {
        val mines = game(player)?.mines() ?: player.gambleState.minesCount
        val rates = (1..MinesGame.TILES - mines).joinToString(", ") { "$it: " + Gamble.multiplier(MinesGame.multiplier(mines, it)) }
        player.sendMessage("<col=00a000>Rates with $mines mines</col> (gems: multiplier): $rates")
    }

    fun close(player: Player) {
        stopAuto(player)
        // leaving mid-game cashes out what's been revealed (a game with no gems gets the bet back)
        val game = game(player) ?: return
        if (game.gems() > 0) {
            finish(player, game)
        } else {
            val state = player.gambleState
            state.mines = null
            state.minesCurrency.give(player, game.bet())
            player.sendMessage("Your bet of ${Gamble.amount(game.bet())} ${state.minesCurrency.long} was returned.")
        }
    }

    val fair = ProvablyFairActions.Panel(MinesUi.FAIR, MinesUi.DIM, "Game Number", ::main, { game(it) != null }) { player ->
        lastGame[player.username]?.let {
            "Mines game #${it.nonce()}: ${it.mines()} mines, ${it.gems()} gems revealed - " + if (it.lost()) "hit a mine" else "cashed out"
        }
    }

    // ---- autoplay --------------------------------------------------------------------------------------------

    private fun refreshAutoPicks(player: Player) {
        val state = player.gambleState
        val main = main(player)
        for (t in 0 until MinesUi.TILES) main.hide(MinesUi.AUTO_PICK + t, !(state.minesAuto && t in state.minesAutoTiles))
        val currency = Gamble.currency(player)
        main.text(MinesUi.AUTO_LABEL, if (state.minesAutoTiles.isEmpty()) {
            "@yel@Please select tiles above to set your auto-play selection"
        } else {
            val multiplier = MinesGame.multiplier(state.minesCount, state.minesAutoTiles.size)
            "@ora@You will win on Cashout:@gre@ " + Gamble.amount((state.bet(GAME, currency) * multiplier).toLong()) +
                " " + currency.short + " (" + Gamble.multiplier(multiplier) + ")"
        })
    }

    private fun refreshAuto(player: Player) {
        val state = player.gambleState
        val auto = state.minesAutoBet
        val side = side(player)
        side.text(MinesUi.BETS, "  " + if (auto.numberOfBets < 0) "Infinite" else Gamble.amount(auto.numberOfBets.toLong()))
        side.hide(MinesUi.STATE + MinesUi.SUCCESS_STOP, state.minesAutoCashout)
        side.hide(MinesUi.STATE + MinesUi.SUCCESS_CASHOUT, !state.minesAutoCashout)
        side.hide(MinesUi.STATE + MinesUi.WIN_RESET, auto.increaseOnWin)
        side.hide(MinesUi.STATE + MinesUi.WIN_INCREASE, !auto.increaseOnWin)
        side.hide(MinesUi.STATE + MinesUi.LOSS_RESET, auto.increaseOnLoss)
        side.hide(MinesUi.STATE + MinesUi.LOSS_INCREASE, !auto.increaseOnLoss)
        side.text(MinesUi.WIN_PERCENT, TWO.format(auto.onWinPercent).removeSuffix(".00") + "%")
        side.text(MinesUi.LOSS_PERCENT, TWO.format(auto.onLossPercent).removeSuffix(".00") + "%")
        val currency = Gamble.currency(player)
        side.text(MinesUi.STOP_PROFIT, "  " + Gamble.amount(auto.stopOnProfit) + " " + currency.short)
        side.text(MinesUi.STOP_LOSS, "  " + Gamble.amount(auto.stopOnLoss) + " " + currency.short)
        side.hide(MinesUi.STATE + MinesUi.AUTOBET, !auto.running())
    }

    fun toggleAuto(player: Player) {
        val state = player.gambleState
        if (state.minesAutoBet.running()) {
            stopAuto(player)
            return
        }
        if (state.minesAutoTiles.isEmpty()) {
            player.sendMessage("Pick the tiles to reveal first: click them on the board.")
            return
        }
        if (game(player) != null) return
        val auto = state.minesAutoBet
        auto.start(state.bet(GAME, Gamble.currency(player)))
        refreshAuto(player)
        val main = main(player)
        // each round: bet, reveal the picked tiles one a tick, cash out (or stop on the first win if set to)
        val task = object : WorldTask {
            var pending = ArrayDeque<Int>()
            override fun run() {
                if (!auto.running() || !player.interfaceHandler.isPresent(GameInterface.GAMBLE_MINES) || player.isFinished) {
                    stopAuto(player)
                    return
                }
                val game = game(player)
                if (game == null) {
                    state.setBet(GAME, Gamble.currency(player), auto.currentBet())
                    if (!bet(player)) {
                        stopAuto(player)
                        return
                    }
                    pending = ArrayDeque(state.minesAutoTiles)
                    for (t in state.minesAutoTiles) main.show(MinesUi.AUTO_PLAYING + t)
                    return
                }
                val tile = pending.removeFirstOrNull()
                if (tile != null) {
                    main.hide(MinesUi.AUTO_PLAYING + tile)
                    reveal(player, game, tile)
                    if (game.running()) return
                } else {
                    finish(player, game)
                }
                val won = !game.lost()
                val profit = if (won) (game.bet() * game.currentMultiplier()).toLong() - game.bet() else -game.bet()
                auto.record(won, profit)
                if (won && !state.minesAutoCashout) auto.stop()
                if (!auto.running()) {
                    val currency = state.minesCurrency
                    player.sendMessage("Autoplay finished: ${auto.played()} games, profit " +
                        (if (auto.profit() >= 0) "+" else "") + Gamble.amount(auto.profit()) + " ${currency.long}.")
                    stopAuto(player)
                }
            }
        }
        state.autoTask = task
        WorldTasksManager.schedule(task, 0, 1)
    }

    fun stopAuto(player: Player) {
        val state = player.gambleState
        state.minesAutoBet.stop()
        state.autoTask?.stop()
        state.autoTask = null
        if (player.interfaceHandler.isPresent(GameInterface.GAMBLE_MINES_SIDE)) refreshAuto(player)
    }

    fun changeBets(player: Player, delta: Int) {
        val auto = player.gambleState.minesAutoBet
        if (auto.running()) return
        auto.numberOfBets = if (delta == 0) -1 else (maxOf(0, auto.numberOfBets) + delta).coerceIn(1, 100_000)
        refreshAuto(player)
    }

    fun enterBets(player: Player) = player.sendInputInt("How many games? (0 = infinite)") { n ->
        player.gambleState.minesAutoBet.numberOfBets = if (n <= 0) -1 else n
        refreshAuto(player)
    }

    fun setOnSuccess(player: Player, cashout: Boolean) {
        player.gambleState.minesAutoCashout = cashout
        refreshAuto(player)
    }

    fun setOnWin(player: Player, increase: Boolean) {
        player.gambleState.minesAutoBet.increaseOnWin = increase
        refreshAuto(player)
    }

    fun setOnLoss(player: Player, increase: Boolean) {
        player.gambleState.minesAutoBet.increaseOnLoss = increase
        refreshAuto(player)
    }

    fun enterPercent(player: Player, win: Boolean) = player.sendInputInt("Increase the bet by how many percent?") { n ->
        val auto = player.gambleState.minesAutoBet
        if (win) { auto.onWinPercent = n.coerceIn(0, 1000).toDouble(); auto.increaseOnWin = n > 0 }
        else { auto.onLossPercent = n.coerceIn(0, 1000).toDouble(); auto.increaseOnLoss = n > 0 }
        refreshAuto(player)
    }

    fun enterStop(player: Player, profit: Boolean) = player.sendInputString(
        if (profit) "Stop once you're up by (0 = off):" else "Stop once you're down by (0 = off):"
    ) { text ->
        val amount = if (text.trim() == "0") 0L else Gamble.parseAmount(text) ?: return@sendInputString player.sendMessage("That's not a valid amount.")
        val auto = player.gambleState.minesAutoBet
        if (profit) auto.stopOnProfit = amount else auto.stopOnLoss = amount
        refreshAuto(player)
    }
}
