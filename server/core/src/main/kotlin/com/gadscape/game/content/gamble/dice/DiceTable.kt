package com.gadscape.game.content.gamble.dice

import com.gadscape.game.content.gamble.Gamble
import com.gadscape.game.content.gamble.GambleInterfaces
import com.gadscape.game.content.gamble.GambleView
import com.gadscape.game.content.gamble.ProvablyFairActions
import com.gadscape.game.content.gamble.gambleState
import com.gadscape.game.content.gamble.ui.DiceUi
import com.gadscape.game.content.gamble.ui.RoatSprites
import com.zenyte.game.GameInterface
import com.zenyte.game.task.WorldTask
import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.world.WorldThread
import com.zenyte.game.world.entity.player.Player
import java.text.DecimalFormat

/**
 * The dice machine, played like Roat Pkz's: slide the "roll over" target (2-98), set a bet, roll 0.00-100.00.
 * A roll at or over the target pays bet x 99 / (100 - target). Autoplay as in their Auto tab.
 */
object DiceTable {

    private const val GAME = "dice"
    private val FOUR = DecimalFormat("0.0000")
    private val TWO = DecimalFormat("0.00")

    fun open(player: Player) {
        Gamble.refusal(player)?.let { player.sendMessage(it); return }
        GambleInterfaces.register()
        player.interfaceHandler.closeInterfaces()
        GameInterface.GAMBLE_DICE.open(player)
        GameInterface.GAMBLE_DICE_SIDE.open(player)
        val main = main(player)
        main.hide(DiceUi.RULES); main.hide(DiceUi.FAIR); main.hide(DiceUi.DIM); main.hide(DiceUi.ARROW)
        main.show(DiceUi.BACKGROUND)
        setTab(player, auto = player.gambleState.diceAuto)
        refresh(player)
        refreshHistory(player)
    }

    fun main(player: Player) = GambleView(player, DiceUi.main)
    fun side(player: Player) = GambleView(player, DiceUi.side)

    fun refresh(player: Player) {
        val state = player.gambleState
        val currency = Gamble.currency(player)
        val target = state.diceTarget
        val multiplier = DiceGame.multiplier(target.toDouble())
        val main = main(player)
        main.text(DiceUi.MULTIPLIER, FOUR.format(multiplier))
        main.text(DiceUi.ROLL_OVER, TWO.format(target))
        main.text(DiceUi.WIN_CHANCE, FOUR.format(DiceGame.winChance(target.toDouble())))
        main.text(DiceUi.SESSION, Gamble.session(state.profit(GAME, currency), currency))
        val knob = DiceUi.knobX(target)
        main.move(DiceUi.SLIDER_GREEN_LAYER, 5 + 26 + knob, 5 + 178)
        main.move(DiceUi.SLIDER_GREEN, -knob, 0)
        main.move(DiceUi.SLIDER_KNOB, 5 + 26 + knob, 5 + 178)

        val bet = state.bet(GAME, currency)
        val side = side(player)
        for (offset in intArrayOf(0, DiceUi.AUTO)) {
            side.text(offset + DiceUi.BALANCE, "@whi@Balance: @gre@" + Gamble.short(currency.balance(player)) + " " + currency.short)
            side.text(offset + DiceUi.BET, "  " + Gamble.amount(bet) + " " + currency.short)
        }
        side.text(DiceUi.PROFIT_LABEL, "Profit on Win (@gre@" + FOUR.format(multiplier) + "x@whi@):")
        side.text(DiceUi.PROFIT, Gamble.amount((bet * multiplier).toLong()) + " " + currency.long)
        refreshAuto(player)
    }

    private fun refreshAuto(player: Player) {
        val auto = player.gambleState.diceAutoBet
        val side = side(player)
        side.text(DiceUi.BETS, "  " + if (auto.numberOfBets < 0) "Infinite" else Gamble.amount(auto.numberOfBets.toLong()))
        side.hide(DiceUi.STATE + DiceUi.WIN_RESET, auto.increaseOnWin)
        side.hide(DiceUi.STATE + DiceUi.WIN_INCREASE, !auto.increaseOnWin)
        side.hide(DiceUi.STATE + DiceUi.LOSS_RESET, auto.increaseOnLoss)
        side.hide(DiceUi.STATE + DiceUi.LOSS_INCREASE, !auto.increaseOnLoss)
        side.text(DiceUi.WIN_PERCENT, TWO.format(auto.onWinPercent).removeSuffix(".00") + "%")
        side.text(DiceUi.LOSS_PERCENT, TWO.format(auto.onLossPercent).removeSuffix(".00") + "%")
        val currency = Gamble.currency(player)
        side.text(DiceUi.STOP_PROFIT, "  " + Gamble.amount(auto.stopOnProfit) + " " + currency.short)
        side.text(DiceUi.STOP_LOSS, "  " + Gamble.amount(auto.stopOnLoss) + " " + currency.short)
        side.hide(DiceUi.STATE + DiceUi.AUTOBET, !auto.running())
    }

    private fun refreshHistory(player: Player) {
        val history = player.gambleState.diceHistory
        val main = main(player)
        for (i in 0 until DiceUi.HISTORY_SLOTS) {
            val entry = history.getOrNull(i)
            val tab = DiceUi.HISTORY + 1 + i * 2
            val text = DiceUi.HISTORY + 2 + i * 2
            if (entry == null) {
                main.hide(tab)
                main.text(text, "")
                continue
            }
            main.sprite(tab, RoatSprites.id(if (entry.second) "DiceInterface/win_tab" else "DiceInterface/loss_tab"))
            main.show(tab)
            main.text(text, (if (entry.second) "@gre@" else "@red@") + TWO.format(entry.first))
        }
    }

    fun setTarget(player: Player, target: Int) {
        if (player.gambleState.diceAutoBet.running()) return
        player.gambleState.diceTarget = target.coerceIn(DiceUi.MIN_TARGET, DiceUi.MAX_TARGET)
        main(player).hide(DiceUi.ARROW)
        refresh(player)
    }

    fun setTab(player: Player, auto: Boolean) {
        val state = player.gambleState
        if (!auto && state.diceAutoBet.running()) stopAuto(player)
        state.diceAuto = auto
        val side = side(player)
        side.hide(DiceUi.MANUAL, auto)
        side.hide(DiceUi.AUTO_TAB, !auto)
        side.text(DiceUi.MODE, "@cu1@mode: @cu2@" + if (auto) "auto" else "manual")
    }

    /** One roll at the current settings. Returns whether it went through. */
    fun roll(player: Player, bet: Long = player.gambleState.bet(GAME, Gamble.currency(player))): Boolean {
        val state = player.gambleState
        if (!GameInterfaceCheck.isOpen(player, GameInterface.GAMBLE_DICE)) return false
        Gamble.refusal(player)?.let { player.sendMessage(it); return false }
        if (state.lastBetTick == WorldThread.WORLD_CYCLE) return false
        val currency = Gamble.currency(player)
        if (bet < Gamble.MIN_BET || bet > Gamble.maxBet(currency)) {
            player.sendMessage("You can bet between ${Gamble.amount(Gamble.MIN_BET)} and ${Gamble.amount(Gamble.maxBet(currency))} ${currency.long}.")
            return false
        }
        if (!currency.take(player, bet)) {
            player.sendMessage("You don't have ${Gamble.amount(bet)} ${currency.long} to bet.")
            return false
        }
        state.lastBetTick = WorldThread.WORLD_CYCLE
        val target = state.diceTarget
        val result = Gamble.withSeeds(player) { DiceGame.roll(it, bet, target.toDouble()) }
        if (result.payout > 0) currency.give(player, result.payout)
        state.addProfit(GAME, currency, result.profit())
        Gamble.log(player, GAME, currency, bet, result.payout, "target $target, roll ${result.roll}")

        val main = main(player)
        val rollX = rollLocation(result.roll)
        main.move(DiceUi.ARROW, 5 + 26 + rollX - 26, 5 + 156)
        main.text(DiceUi.ARROW_TEXT, (if (result.win) "@gre@" else "@red@") + TWO.format(result.roll))
        main.show(DiceUi.ARROW)
        state.diceHistory.addFirst(result.roll to result.win)
        while (state.diceHistory.size > DiceUi.HISTORY_SLOTS) state.diceHistory.removeLast()
        refreshHistory(player)
        refresh(player)
        val side = side(player)
        side.show(DiceUi.STATE + DiceUi.ROLL)
        WorldTasksManager.schedule(WorldTask { side.hide(DiceUi.STATE + DiceUi.ROLL) }, 1)
        if (result.win) {
            player.sendMessage("<col=00a000>You rolled ${TWO.format(result.roll)} and won ${Gamble.amount(result.payout)} ${currency.long}!</col>")
        } else {
            player.sendMessage("You rolled ${TWO.format(result.roll)} (needed ${TWO.format(target)}+) and lost ${Gamble.amount(bet)} ${currency.long}.")
        }
        lastResult[player.username] = result
        if (state.diceAutoBet.running()) state.diceAutoBet.record(result.win, result.profit())
        return true
    }

    /** x of a roll on the bar: 0.00 at the left end of the scale, 100.00 at the right. */
    private fun rollLocation(roll: Double): Int {
        val left = DiceUi.SLIDER_OFFSET
        val right = DiceUi.SLIDER_WIDTH - DiceUi.SLIDER_OFFSET
        return (left + (right - left) * roll / 100.0).toInt()
    }

    val lastResult = HashMap<String, DiceGame.Result>()

    // ---- bet controls ----------------------------------------------------------------------------------------

    fun changeBet(player: Player, factor: Double) {
        val currency = Gamble.currency(player)
        val state = player.gambleState
        val bet = (state.bet(GAME, currency) * factor).toLong().coerceAtLeast(Gamble.MIN_BET)
        state.setBet(GAME, currency, minOf(bet, maxOf(Gamble.MIN_BET, currency.balance(player))))
        refresh(player)
    }

    fun enterBet(player: Player) = player.sendInputString("Enter your bet (e.g. 500, 25k, 1.5m):") { text ->
        val amount = Gamble.parseAmount(text) ?: return@sendInputString player.sendMessage("That's not a valid amount.")
        player.gambleState.setBet(GAME, Gamble.currency(player), amount)
        refresh(player)
    }

    fun switchCurrency(player: Player) {
        if (player.gambleState.diceAutoBet.running()) return
        val currency = Gamble.switchCurrency(player)
        player.sendMessage("You're now betting ${currency.long}.")
        refresh(player)
    }

    // ---- autoplay --------------------------------------------------------------------------------------------

    fun toggleAuto(player: Player) {
        val state = player.gambleState
        if (state.diceAutoBet.running()) {
            stopAuto(player)
            return
        }
        val currency = Gamble.currency(player)
        val auto = state.diceAutoBet
        auto.start(state.bet(GAME, currency))
        refreshAuto(player)
        val task = object : WorldTask {
            override fun run() {
                if (!auto.running() || !GameInterfaceCheck.isOpen(player, GameInterface.GAMBLE_DICE) || player.isFinished) {
                    stopAuto(player)
                    return
                }
                if (!roll(player, auto.currentBet())) {
                    stopAuto(player)
                    return
                }
                if (!auto.running()) {
                    player.sendMessage("Autobet finished: ${auto.played()} bets, profit " +
                        (if (auto.profit() >= 0) "+" else "") + Gamble.amount(auto.profit()) + " ${currency.long}.")
                    stopAuto(player)
                }
            }
        }
        state.autoTask = task
        WorldTasksManager.schedule(task, 0, 2)
    }

    fun stopAuto(player: Player) {
        val state = player.gambleState
        state.diceAutoBet.stop()
        state.autoTask?.stop()
        state.autoTask = null
        if (GameInterfaceCheck.isOpen(player, GameInterface.GAMBLE_DICE_SIDE)) refreshAuto(player)
    }

    fun changeBets(player: Player, delta: Int) {
        val auto = player.gambleState.diceAutoBet
        if (auto.running()) return
        auto.numberOfBets = if (delta == 0) -1 else (maxOf(0, auto.numberOfBets) + delta).coerceIn(1, 100_000)
        refreshAuto(player)
    }

    fun enterBets(player: Player) = player.sendInputInt("How many bets? (0 = infinite)") { n ->
        player.gambleState.diceAutoBet.numberOfBets = if (n <= 0) -1 else n
        refreshAuto(player)
    }

    fun setOnWin(player: Player, increase: Boolean) {
        player.gambleState.diceAutoBet.increaseOnWin = increase
        refreshAuto(player)
    }

    fun setOnLoss(player: Player, increase: Boolean) {
        player.gambleState.diceAutoBet.increaseOnLoss = increase
        refreshAuto(player)
    }

    fun enterPercent(player: Player, win: Boolean) = player.sendInputInt("Increase the bet by how many percent?") { n ->
        val auto = player.gambleState.diceAutoBet
        if (win) { auto.onWinPercent = n.coerceIn(0, 1000).toDouble(); auto.increaseOnWin = n > 0 }
        else { auto.onLossPercent = n.coerceIn(0, 1000).toDouble(); auto.increaseOnLoss = n > 0 }
        refreshAuto(player)
    }

    fun enterStop(player: Player, profit: Boolean) = player.sendInputString(
        if (profit) "Stop once you're up by (0 = off):" else "Stop once you're down by (0 = off):"
    ) { text ->
        val amount = if (text.trim() == "0") 0L else Gamble.parseAmount(text) ?: return@sendInputString player.sendMessage("That's not a valid amount.")
        val auto = player.gambleState.diceAutoBet
        if (profit) auto.stopOnProfit = amount else auto.stopOnLoss = amount
        refreshAuto(player)
    }

    // ---- panels ----------------------------------------------------------------------------------------------

    fun showRules(player: Player, show: Boolean) {
        val main = main(player)
        main.hide(DiceUi.RULES, !show)
        main.hide(DiceUi.DIM, !show)
    }

    fun close(player: Player) {
        stopAuto(player)
    }

    val fair = ProvablyFairActions.Panel(DiceUi.FAIR, DiceUi.DIM, "Game Number", ::main, { false }) { player ->
        lastResult[player.username]?.let {
            "Dice game #${it.nonce}: target ${TWO.format(it.target)}, roll ${TWO.format(it.roll)} - " + if (it.win) "win" else "loss"
        }
    }

    private object GameInterfaceCheck {
        fun isOpen(player: Player, inter: GameInterface) = player.interfaceHandler.isPresent(inter)
    }
}
