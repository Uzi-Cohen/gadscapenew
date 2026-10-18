package com.gadscape.game.content.gamble

import com.gadscape.game.content.gamble.blackjack.BlackjackTable
import com.gadscape.game.content.gamble.dice.DiceTable
import com.gadscape.game.content.gamble.mines.MinesTable
import com.gadscape.game.content.gamble.ui.BlackjackUi
import com.gadscape.game.content.gamble.ui.DiceUi
import com.gadscape.game.content.gamble.ui.MinesUi
import com.gadscape.game.content.gamble.ui.RoatUi
import com.zenyte.game.GameInterface
import com.zenyte.game.model.ui.Interface
import com.zenyte.game.model.ui.NewInterfaceHandler
import com.zenyte.game.world.entity.player.Player
import java.util.Optional

/**
 * Click handlers for the six gambling windows. Each routes a click by the Roat component id that was clicked
 * (see [RoatUi.roatId]) to its table. Registered the first time a table opens, like the PK store.
 */
object GambleInterfaces {

    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        for (c in listOf(
            DiceMain::class.java, DiceSide::class.java, MinesMain::class.java, MinesSide::class.java,
            BlackjackMain::class.java, BlackjackSide::class.java,
        )) NewInterfaceHandler.add(c)
    }

    /** An interface built by [RoatUi]: every click goes to [clicked] with the Roat component id and option (1-10). */
    abstract class RoatInterface : Interface() {
        abstract val ui: RoatUi

        abstract fun clicked(player: Player, roatId: Int, option: Int)

        override fun attach() {}

        override fun build() {}

        override fun getDefaultHandler() = DefaultClickHandler { player, componentId, _, _, option ->
            val roatId = ui.roatId(componentId) ?: return@DefaultClickHandler
            clicked(player, roatId, option)
        }
    }

    class DiceMain : RoatInterface() {
        override val ui get() = DiceUi.main
        override fun getInterface() = GameInterface.GAMBLE_DICE
        override fun close(player: Player, replacement: Optional<GameInterface>) {
            DiceTable.close(player)
            player.interfaceHandler.closeInterface(GameInterface.GAMBLE_DICE_SIDE)
        }

        override fun clicked(player: Player, roatId: Int, option: Int) {
            if (DiceTable.fair.click(player, roatId)) return
            when (roatId) {
                DiceUi.CLOSE -> player.interfaceHandler.closeInterfaces()
                DiceUi.PROVABLY_FAIR -> DiceTable.fair.show(player, true)
                DiceUi.RULES_BUTTON -> DiceTable.showRules(player, true)
                DiceUi.RULES_CLOSE -> DiceTable.showRules(player, false)
                DiceUi.LIGHT -> toggleBackground(player, DiceTable.main(player), DiceUi.BACKGROUND)
                else -> if (roatId in DiceUi.SLIDER_SPOT + DiceUi.MIN_TARGET..DiceUi.SLIDER_SPOT + DiceUi.MAX_TARGET) {
                    DiceTable.setTarget(player, roatId - DiceUi.SLIDER_SPOT)
                }
            }
        }
    }

    class DiceSide : RoatInterface() {
        override val ui get() = DiceUi.side
        override fun getInterface() = GameInterface.GAMBLE_DICE_SIDE
        override fun clicked(player: Player, roatId: Int, option: Int) {
            when (unAuto(roatId, DiceUi.AUTO, DiceUi.STATE)) {
                DiceUi.MODE -> DiceTable.setTab(player, !player.gambleState.diceAuto)
                DiceUi.CURRENCY -> DiceTable.switchCurrency(player)
                DiceUi.HALF -> DiceTable.changeBet(player, 0.5)
                DiceUi.DOUBLE -> DiceTable.changeBet(player, 2.0)
                DiceUi.BET -> DiceTable.enterBet(player)
                DiceUi.ROLL -> DiceTable.roll(player)
                DiceUi.BETS_MINUS -> DiceTable.changeBets(player, -10)
                DiceUi.BETS_PLUS -> DiceTable.changeBets(player, 10)
                DiceUi.BETS_INF -> DiceTable.changeBets(player, 0)
                DiceUi.BETS -> DiceTable.enterBets(player)
                DiceUi.WIN_RESET -> DiceTable.setOnWin(player, false)
                DiceUi.WIN_INCREASE -> DiceTable.setOnWin(player, true)
                DiceUi.WIN_PERCENT -> DiceTable.enterPercent(player, true)
                DiceUi.LOSS_RESET -> DiceTable.setOnLoss(player, false)
                DiceUi.LOSS_INCREASE -> DiceTable.setOnLoss(player, true)
                DiceUi.LOSS_PERCENT -> DiceTable.enterPercent(player, false)
                DiceUi.STOP_PROFIT -> DiceTable.enterStop(player, true)
                DiceUi.STOP_LOSS -> DiceTable.enterStop(player, false)
                DiceUi.AUTOBET -> DiceTable.toggleAuto(player)
            }
        }
    }

    class MinesMain : RoatInterface() {
        override val ui get() = MinesUi.main
        override fun getInterface() = GameInterface.GAMBLE_MINES
        override fun close(player: Player, replacement: Optional<GameInterface>) {
            MinesTable.close(player)
            player.interfaceHandler.closeInterface(GameInterface.GAMBLE_MINES_SIDE)
        }

        override fun clicked(player: Player, roatId: Int, option: Int) {
            if (MinesTable.fair.click(player, roatId)) return
            when (roatId) {
                MinesUi.CLOSE -> player.interfaceHandler.closeInterfaces()
                MinesUi.PROVABLY_FAIR -> MinesTable.fair.show(player, true)
                MinesUi.RULES_BUTTON -> MinesTable.showRules(player, true)
                MinesUi.RULES_CLOSE -> MinesTable.showRules(player, false)
                MinesUi.RATES -> MinesTable.showRates(player)
                MinesUi.WIN_CLOSE -> MinesTable.closeWinPanel(player)
                MinesUi.LIGHT -> toggleBackground(player, MinesTable.main(player), MinesUi.BACKGROUND)
                else -> if (roatId in MinesUi.TILE until MinesUi.TILE + MinesUi.TILES * 2 && (roatId - MinesUi.TILE) % 2 == 0) {
                    MinesTable.tile(player, (roatId - MinesUi.TILE) / 2)
                }
            }
        }
    }

    class MinesSide : RoatInterface() {
        override val ui get() = MinesUi.side
        override fun getInterface() = GameInterface.GAMBLE_MINES_SIDE
        override fun clicked(player: Player, roatId: Int, option: Int) {
            when (unAuto(roatId, MinesUi.AUTO, MinesUi.STATE)) {
                MinesUi.MODE -> MinesTable.setTab(player, !player.gambleState.minesAuto)
                MinesUi.CURRENCY -> MinesTable.switchCurrency(player)
                MinesUi.HALF -> MinesTable.changeBet(player, 0.5)
                MinesUi.DOUBLE -> MinesTable.changeBet(player, 2.0)
                MinesUi.BET_AMOUNT -> MinesTable.enterBet(player)
                MinesUi.MINES_PICKER_TEXT, MinesUi.AUTO_MINES_PICKER_TEXT -> MinesTable.enterMines(player)
                MinesUi.BET -> MinesTable.bet(player)
                MinesUi.CASHOUT -> MinesTable.cashout(player)
                MinesUi.RANDOM_TILE -> MinesTable.randomTile(player)
                MinesUi.BETS_MINUS -> MinesTable.changeBets(player, -10)
                MinesUi.BETS_PLUS -> MinesTable.changeBets(player, 10)
                MinesUi.BETS_INF -> MinesTable.changeBets(player, 0)
                MinesUi.BETS -> MinesTable.enterBets(player)
                MinesUi.SUCCESS_STOP -> MinesTable.setOnSuccess(player, cashout = false)
                MinesUi.SUCCESS_CASHOUT -> MinesTable.setOnSuccess(player, cashout = true)
                MinesUi.WIN_RESET -> MinesTable.setOnWin(player, false)
                MinesUi.WIN_INCREASE -> MinesTable.setOnWin(player, true)
                MinesUi.WIN_PERCENT -> MinesTable.enterPercent(player, true)
                MinesUi.LOSS_RESET -> MinesTable.setOnLoss(player, false)
                MinesUi.LOSS_INCREASE -> MinesTable.setOnLoss(player, true)
                MinesUi.LOSS_PERCENT -> MinesTable.enterPercent(player, false)
                MinesUi.STOP_PROFIT -> MinesTable.enterStop(player, true)
                MinesUi.STOP_LOSS -> MinesTable.enterStop(player, false)
                MinesUi.AUTOBET -> MinesTable.toggleAuto(player)
            }
        }
    }

    class BlackjackMain : RoatInterface() {
        override val ui get() = BlackjackUi.main
        override fun getInterface() = GameInterface.GAMBLE_BLACKJACK
        override fun close(player: Player, replacement: Optional<GameInterface>) {
            BlackjackTable.close(player)
            player.interfaceHandler.closeInterface(GameInterface.GAMBLE_BLACKJACK_SIDE)
        }

        override fun clicked(player: Player, roatId: Int, option: Int) {
            if (BlackjackTable.fair.click(player, roatId)) return
            when (roatId) {
                BlackjackUi.CLOSE -> player.interfaceHandler.closeInterfaces()
                BlackjackUi.PROVABLY_FAIR -> BlackjackTable.fair.show(player, true)
                BlackjackUi.RULES_BUTTON -> BlackjackTable.showRules(player, true)
                BlackjackUi.RULES_CLOSE -> BlackjackTable.showRules(player, false)
                BlackjackUi.LIGHT -> toggleBackground(player, BlackjackTable.main(player), BlackjackUi.BACKGROUND)
            }
        }
    }

    class BlackjackSide : RoatInterface() {
        override val ui get() = BlackjackUi.side
        override fun getInterface() = GameInterface.GAMBLE_BLACKJACK_SIDE
        override fun clicked(player: Player, roatId: Int, option: Int) {
            when (roatId) {
                BlackjackUi.CURRENCY -> BlackjackTable.switchCurrency(player)
                BlackjackUi.HALF -> BlackjackTable.changeBet(player, 0.5)
                BlackjackUi.DOUBLE_BET -> BlackjackTable.changeBet(player, 2.0)
                BlackjackUi.BET_AMOUNT -> BlackjackTable.enterBet(player)
                BlackjackUi.BET -> BlackjackTable.bet(player)
                BlackjackUi.HIT -> BlackjackTable.hit(player)
                BlackjackUi.STAND -> BlackjackTable.stand(player)
                BlackjackUi.SPLIT -> BlackjackTable.split(player)
                BlackjackUi.DOUBLE -> BlackjackTable.doubleDown(player)
                BlackjackUi.INSURANCE_YES -> BlackjackTable.insurance(player, true)
                BlackjackUi.INSURANCE_NO -> BlackjackTable.insurance(player, false)
            }
        }
    }

    /** The manual-tab id for a component's auto-tab copy ([auto] + id); other ids unchanged. */
    private fun unAuto(roatId: Int, auto: Int, state: Int) = if (roatId in auto until state) roatId - auto else roatId

    /** Roat's light bulb: shows the game world through the window, or puts the background back. */
    private fun toggleBackground(player: Player, view: GambleView, background: Int) {
        val key = "gamble-transparent"
        val transparent = !(player.temporaryAttributes[key] as? Boolean ?: false)
        player.temporaryAttributes[key] = transparent
        view.hide(background, transparent)
    }
}
