package com.gadscape.game.content.gamble.blackjack

import com.gadscape.game.content.gamble.Gamble
import com.gadscape.game.content.gamble.GambleInterfaces
import com.gadscape.game.content.gamble.GambleView
import com.gadscape.game.content.gamble.ProvablyFairActions
import com.gadscape.game.content.gamble.gambleState
import com.gadscape.game.content.gamble.ui.BlackjackUi
import com.zenyte.game.GameInterface
import com.zenyte.game.world.WorldThread
import com.zenyte.game.world.entity.player.Player

/**
 * The blackjack table, played like Roat Pkz's: 8 decks shuffled each hand with the provably-fair shuffle,
 * dealer stands on all 17s, blackjack pays 3:2, insurance 2:1, double on any first two cards and after a split,
 * one split, split aces get one card (see [BlackjackGame]).
 */
object BlackjackTable {

    private const val GAME = "blackjack"

    // where Roat deals: dealer row and player row, cards 29px apart
    private const val SX = 5
    private const val DEALER_Y = SX + 27
    private const val PLAYER_Y = SX + 28 + 157
    private const val SPACING = 29
    private const val CENTRE = SX + 250
    private const val SPLIT_LEFT = 150
    private const val SPLIT_RIGHT = 360
    private const val SLOTS_PER_HAND = 10

    fun main(player: Player) = GambleView(player, BlackjackUi.main)
    fun side(player: Player) = GambleView(player, BlackjackUi.side)

    private fun game(player: Player) = player.gambleState.blackjack

    private fun playing(player: Player) = game(player)?.state()?.let { it != BlackjackGame.State.FINISHED } ?: false

    fun open(player: Player) {
        Gamble.refusal(player)?.let { player.sendMessage(it); return }
        GambleInterfaces.register()
        player.interfaceHandler.closeInterfaces()
        GameInterface.GAMBLE_BLACKJACK.open(player)
        GameInterface.GAMBLE_BLACKJACK_SIDE.open(player)
        val main = main(player)
        main.hide(BlackjackUi.RULES); main.hide(BlackjackUi.FAIR); main.hide(BlackjackUi.DIM)
        main.show(BlackjackUi.BACKGROUND)
        player.gambleState.blackjack = null
        clearTable(player)
        refresh(player)
    }

    // ---- drawing ---------------------------------------------------------------------------------------------

    private fun clearTable(player: Player) {
        val main = main(player)
        for (i in 0 until BlackjackUi.CARD_SLOTS) {
            main.hide(BlackjackUi.CARD + i)
            main.hide(BlackjackUi.CARD_GLOW + i)
        }
        for (id in intArrayOf(BlackjackUi.ARROW_LEFT, BlackjackUi.ARROW_RIGHT, BlackjackUi.HAND1_GLOW,
                BlackjackUi.HAND2_BG, BlackjackUi.HAND2_GLOW, BlackjackUi.HAND2_TOTAL, BlackjackUi.DEALER_BG, BlackjackUi.DEALER_GLOW)) {
            main.hide(id)
        }
        bubble(player, BlackjackUi.HAND1_BG, BlackjackUi.HAND1_GLOW, BlackjackUi.HAND1_TOTAL, CENTRE, 291, "Place bets", -1)
        main.text(BlackjackUi.DEALER_TOTAL, "")
    }

    /** A total bubble centred on [cx]: background, optional glow (0 win, 1 loss, 2 push) and text. */
    private fun bubble(player: Player, bg: Int, glow: Int, text: Int, cx: Int, y: Int, value: String, glowColour: Int) {
        val main = main(player)
        main.move(bg, cx - 45, y)
        main.show(bg)
        main.moveCentredText(text, cx, y + 6)
        main.text(text, value)
        main.show(text)
        if (glowColour >= 0) {
            main.sprite(glow, BlackjackUi.bubbleGlowSprite(glowColour))
            main.move(glow, cx - 48, y - 3)
            main.show(glow)
        } else {
            main.hide(glow)
        }
    }

    private fun drawCards(player: Player, cards: List<Int>, firstSlot: Int, cx: Int, y: Int, glow: Int) {
        val main = main(player)
        val width = BlackjackUi.CARD_WIDTH + (cards.size - 1) * SPACING
        val start = cx - width / 2
        for (i in 0 until SLOTS_PER_HAND) {
            val slot = firstSlot + i
            val card = cards.getOrNull(i)
            if (card == null) {
                main.hide(BlackjackUi.CARD + slot)
                main.hide(BlackjackUi.CARD_GLOW + slot)
                continue
            }
            main.sprite(BlackjackUi.CARD + slot, BlackjackUi.cardSprite(card))
            main.move(BlackjackUi.CARD + slot, start + i * SPACING, y)
            main.show(BlackjackUi.CARD + slot)
            if (glow >= 0) {
                main.sprite(BlackjackUi.CARD_GLOW + slot, BlackjackUi.glowSprite(glow))
                main.move(BlackjackUi.CARD_GLOW + slot, start + i * SPACING, y)
                main.show(BlackjackUi.CARD_GLOW + slot)
            } else {
                main.hide(BlackjackUi.CARD_GLOW + slot)
            }
        }
    }

    private fun totalText(cards: List<Int>): String {
        val total = BlackjackGame.total(cards)
        return if (BlackjackGame.isSoft(cards) && total < 21) "${total - 10}/$total" else total.toString()
    }

    private fun outcomeGlow(outcome: BlackjackGame.Outcome) = when (outcome) {
        BlackjackGame.Outcome.WIN, BlackjackGame.Outcome.BLACKJACK -> 0
        BlackjackGame.Outcome.LOSE, BlackjackGame.Outcome.BUST -> 1
        BlackjackGame.Outcome.PUSH -> 2
        BlackjackGame.Outcome.NONE -> -1
    }

    private fun outcomeText(hand: BlackjackGame.Hand) = when (hand.outcome) {
        BlackjackGame.Outcome.BLACKJACK -> "@gre@Blackjack!"
        BlackjackGame.Outcome.WIN -> "@gre@Win (${hand.total()})"
        BlackjackGame.Outcome.PUSH -> "@or1@Push (${hand.total()})"
        BlackjackGame.Outcome.BUST -> "@red@Bust (${hand.total()})"
        BlackjackGame.Outcome.LOSE -> "@red@Lose (${hand.total()})"
        BlackjackGame.Outcome.NONE -> totalText(hand.cards)
    }

    private fun drawTable(player: Player) {
        val game = game(player) ?: return
        val main = main(player)
        val finished = game.state() == BlackjackGame.State.FINISHED

        val dealer = game.dealerCards().indices.map { game.dealerVisibleCard(it) }
        drawCards(player, dealer, 0, CENTRE, DEALER_Y, -1)
        val dealerTotal = if (finished) totalText(game.dealerCards()) else totalText(listOf(game.dealerCards()[0]))
        bubble(player, BlackjackUi.DEALER_BG, BlackjackUi.DEALER_GLOW, BlackjackUi.DEALER_TOTAL, CENTRE, SX + 286 - 158, dealerTotal, -1)

        val hands = game.hands()
        val split = hands.size > 1
        for ((i, hand) in hands.withIndex()) {
            val cx = if (!split) CENTRE else if (i == 0) SPLIT_LEFT else SPLIT_RIGHT
            val glow = if (finished) outcomeGlow(hand.outcome) else -1
            drawCards(player, hand.cards, SLOTS_PER_HAND * (1 + i), cx, PLAYER_Y, glow)
            val text = if (finished) outcomeText(hand) else totalText(hand.cards)
            if (i == 0) bubble(player, BlackjackUi.HAND1_BG, BlackjackUi.HAND1_GLOW, BlackjackUi.HAND1_TOTAL, cx, 291, text, glow)
            else bubble(player, BlackjackUi.HAND2_BG, BlackjackUi.HAND2_GLOW, BlackjackUi.HAND2_TOTAL, cx, 291, text, glow)
        }
        if (!split) {
            for (i in 0 until SLOTS_PER_HAND) {
                main.hide(BlackjackUi.CARD + 2 * SLOTS_PER_HAND + i)
                main.hide(BlackjackUi.CARD_GLOW + 2 * SLOTS_PER_HAND + i)
            }
            main.hide(BlackjackUi.HAND2_BG); main.hide(BlackjackUi.HAND2_GLOW); main.hide(BlackjackUi.HAND2_TOTAL)
        }
        // the arrow points at the hand being played after a split
        if (split && !finished) {
            val cx = if (game.activeIndex() == 0) SPLIT_LEFT else SPLIT_RIGHT
            val width = BlackjackUi.CARD_WIDTH + (hands[game.activeIndex()].cards.size - 1) * SPACING
            main.move(BlackjackUi.ARROW_RIGHT, cx - width / 2 - 44, PLAYER_Y + 36)
            main.show(BlackjackUi.ARROW_RIGHT)
        } else {
            main.hide(BlackjackUi.ARROW_RIGHT)
        }
    }

    fun refresh(player: Player) {
        val state = player.gambleState
        val game = game(player)
        val inPlay = playing(player)
        val currency = if (inPlay) state.blackjackCurrency else Gamble.currency(player)
        val side = side(player)
        side.text(BlackjackUi.BALANCE, "@whi@Balance: @gre@" + Gamble.short(currency.balance(player)) + " " + currency.short)
        side.text(BlackjackUi.BET_AMOUNT, "  " + Gamble.amount(state.bet(GAME, currency)) + " " + currency.short)

        val insurance = game?.state() == BlackjackGame.State.INSURANCE_OFFERED
        for (id in BlackjackUi.PLAY_PARTS) side.hide(id, insurance)
        for (id in BlackjackUi.INSURANCE_PARTS) side.hide(id, !insurance)
        val turn = game?.state() == BlackjackGame.State.PLAYER_TURN
        val balance = currency.balance(player)
        greyOut(player, BlackjackUi.HIT, !(turn && game!!.canHit()), insurance)
        greyOut(player, BlackjackUi.STAND, !turn, insurance)
        greyOut(player, BlackjackUi.SPLIT, !(turn && game!!.canSplit() && balance >= game.costToSplit()), insurance)
        greyOut(player, BlackjackUi.DOUBLE, !(turn && game!!.canDouble() && balance >= game.costToDouble()), insurance)
        greyOut(player, BlackjackUi.BET, inPlay, insurance)

        val main = main(player)
        main.text(BlackjackUi.STATS_WINS, "@whi@- Wins: @gre@" + state.blackjackWins)
        main.text(BlackjackUi.STATS_LOSSES, "@whi@- Losses: @red@" + state.blackjackLosses)
        val profit = state.profit(GAME, currency)
        main.text(BlackjackUi.STATS_PROFIT, "@whi@- Profit: " + (if (profit >= 0) "@gre@" else "@red@") + Gamble.amount(profit) + " " + currency.short)
    }

    /** A side button's greyed-out look while it can't be used; nothing while insurance hides the buttons. */
    private fun greyOut(player: Player, id: Int, grey: Boolean, insurance: Boolean) {
        side(player).hide(BlackjackUi.STATE + id, !grey || insurance)
    }

    // ---- playing ---------------------------------------------------------------------------------------------

    fun bet(player: Player) {
        val state = player.gambleState
        if (!player.interfaceHandler.isPresent(GameInterface.GAMBLE_BLACKJACK)) return
        Gamble.refusal(player)?.let { player.sendMessage(it); return }
        if (playing(player) || state.lastBetTick == WorldThread.WORLD_CYCLE) return
        val currency = Gamble.currency(player)
        val bet = state.bet(GAME, currency)
        if (bet < Gamble.MIN_BET || bet > Gamble.maxBet(currency)) {
            player.sendMessage("You can bet between ${Gamble.amount(Gamble.MIN_BET)} and ${Gamble.amount(Gamble.maxBet(currency))} ${currency.long}.")
            return
        }
        if (!currency.take(player, bet)) {
            player.sendMessage("You don't have ${Gamble.amount(bet)} ${currency.long} to bet.")
            return
        }
        state.lastBetTick = WorldThread.WORLD_CYCLE
        state.blackjackCurrency = currency
        clearTable(player)
        val game = Gamble.withSeeds(player) { BlackjackGame.start(it, bet) }
        state.blackjack = game
        afterAction(player)
    }

    fun hit(player: Player) = act(player) { it.canHit().also { ok -> if (ok) it.hit() } }

    fun stand(player: Player) = act(player) { it.stand(); true }

    fun doubleDown(player: Player) = act(player) { game ->
        if (!game.canDouble()) return@act false
        if (!player.gambleState.blackjackCurrency.take(player, game.costToDouble())) {
            player.sendMessage("You can't afford to double.")
            return@act false
        }
        game.doubleDown()
        true
    }

    fun split(player: Player) = act(player) { game ->
        if (!game.canSplit()) return@act false
        if (!player.gambleState.blackjackCurrency.take(player, game.costToSplit())) {
            player.sendMessage("You can't afford to split.")
            return@act false
        }
        game.split()
        true
    }

    fun insurance(player: Player, take: Boolean) {
        val game = game(player) ?: return
        if (game.state() != BlackjackGame.State.INSURANCE_OFFERED) return
        val currency = player.gambleState.blackjackCurrency
        if (take && !currency.take(player, game.costOfInsurance())) {
            player.sendMessage("You can't afford insurance.")
            return
        }
        game.insurance(take)
        afterAction(player)
    }

    private fun act(player: Player, action: (BlackjackGame) -> Boolean) {
        val game = game(player) ?: return
        if (game.state() != BlackjackGame.State.PLAYER_TURN) return
        if (action(game)) afterAction(player)
    }

    private fun afterAction(player: Player) {
        val game = game(player) ?: return
        drawTable(player)
        if (game.state() == BlackjackGame.State.FINISHED) settle(player, game)
        refresh(player)
    }

    private fun settle(player: Player, game: BlackjackGame) {
        val state = player.gambleState
        val currency = state.blackjackCurrency
        val payout = game.totalPayout()
        val wagered = game.totalWagered()
        if (payout > 0) currency.give(player, payout)
        state.addProfit(GAME, currency, payout - wagered)
        for (hand in game.hands()) when (hand.outcome) {
            BlackjackGame.Outcome.WIN, BlackjackGame.Outcome.BLACKJACK -> state.blackjackWins++
            BlackjackGame.Outcome.LOSE, BlackjackGame.Outcome.BUST -> state.blackjackLosses++
            else -> {}
        }
        Gamble.log(player, GAME, currency, wagered, payout, game.hands().joinToString { "${it.outcome} ${it.total()}" } +
            " vs dealer ${BlackjackGame.total(game.dealerCards())}")
        lastGame[player.username] = game
        val net = payout - wagered
        player.sendMessage(when {
            net > 0 -> "<col=00a000>You won ${Gamble.amount(net)} ${currency.long}!</col>"
            net == 0L -> "Push: your bet of ${Gamble.amount(wagered)} ${currency.long} was returned."
            else -> "You lost ${Gamble.amount(-net)} ${currency.long}."
        })
    }

    val lastGame = HashMap<String, BlackjackGame>()

    // ---- settings --------------------------------------------------------------------------------------------

    fun switchCurrency(player: Player) {
        if (playing(player)) return
        val currency = Gamble.switchCurrency(player)
        player.sendMessage("You're now betting ${currency.long}.")
        refresh(player)
    }

    fun changeBet(player: Player, factor: Double) {
        if (playing(player)) return
        val currency = Gamble.currency(player)
        val state = player.gambleState
        val bet = (state.bet(GAME, currency) * factor).toLong().coerceAtLeast(Gamble.MIN_BET)
        state.setBet(GAME, currency, minOf(bet, maxOf(Gamble.MIN_BET, currency.balance(player))))
        refresh(player)
    }

    fun enterBet(player: Player) {
        if (playing(player)) return
        player.sendInputString("Enter your bet (e.g. 500, 25k, 1.5m):") { text ->
            val amount = Gamble.parseAmount(text) ?: return@sendInputString player.sendMessage("That's not a valid amount.")
            if (playing(player)) return@sendInputString
            player.gambleState.setBet(GAME, Gamble.currency(player), amount)
            refresh(player)
        }
    }

    fun showRules(player: Player, show: Boolean) {
        val main = main(player)
        main.hide(BlackjackUi.RULES, !show)
        main.hide(BlackjackUi.DIM, !show)
    }

    /** Leaving mid-hand: insurance is declined and every hand left stands, so the hand still settles. */
    fun close(player: Player) {
        val game = game(player) ?: return
        if (game.state() == BlackjackGame.State.INSURANCE_OFFERED) game.insurance(false)
        while (game.state() == BlackjackGame.State.PLAYER_TURN) game.stand()
        if (lastGame[player.username] !== game) settle(player, game)
        player.gambleState.blackjack = null
    }

    val fair = ProvablyFairActions.Panel(BlackjackUi.FAIR, BlackjackUi.DIM, "Hand Number", ::main, { playing(it) }) { player ->
        lastGame[player.username]?.let { g ->
            "Hand #${g.nonce()}: you " + g.hands().joinToString(" / ") { h -> h.cards.joinToString(" ") { BlackjackGame.name(it) } } +
                ", dealer " + g.dealerCards().joinToString(" ") { BlackjackGame.name(it) }
        }
    }
}
