package com.gadscape.game.content.gambling

import com.gadscape.game.content.gambling.provablyfair.ProvablyFair
import com.zenyte.game.GameInterface
import com.zenyte.game.content.flowerpoker.GambleBan
import com.zenyte.game.model.ui.Interface
import com.zenyte.game.world.entity.player.Player

/**
 * The real "Roat Pkz Blackjack" experience: a persistent interface (not chat dialogues) with a
 * player-editable client seed (the "Seed" button, matching RoatPkz's Green Lock affordance - the
 * provably fair trust feature every gambling game here was missing, since they all hardcoded the
 * seed to the player's username), live session stats, a bet stepper with 1/2 and 2x shortcuts,
 * and Hit/Stand/Double/Split/Forfeit buttons once a hand is in play.
 */
class BlackjackInterface : Interface() {

    override fun attach() {
        put(3, "seed_button")
        put(6, "close_button")
        put(8, "stats_text")
        put(9, "seed_text")
        put(10, "table_text")
        put(11, "bet_row")
        put(12, "half_button")
        put(14, "double_bet_button")
        put(16, "bet_text")
        put(17, "bet_button")
        put(19, "action_row")
        put(20, "hit_button")
        put(22, "stand_button")
        put(24, "double_action_button")
        put(26, "split_button")
        put(28, "forfeit_button")
        put(30, "rules_button")
    }

    override fun build() {
        bind("close_button") { player -> player.interfaceHandler.closeInterface(this) }
        bind("rules_button") { player ->
            player.sendMessage("Dealer must draw to 16 and stand on all 17's. Insurance pays 2-1, blackjack pays 3-2.")
        }
        bind("seed_button") { player ->
            if (player.blackjackSession != null) {
                player.sendMessage("You can't change your seed mid-round.")
                return@bind
            }
            player.sendInputString("Enter your client seed:") { seed ->
                val filtered = seed.trim().take(32)
                if (filtered.isEmpty()) {
                    player.sendMessage("Your client seed cannot be empty.")
                } else {
                    player.blackjackClientSeed = filtered
                    player.sendMessage("Your client seed has been set. It will be used for every future hand until you change it again.")
                    render(player)
                }
            }
        }
        bind("half_button") { player ->
            if (player.blackjackSession != null) return@bind
            player.blackjackBet = (player.blackjackBet / 2).coerceAtLeast(BlackjackGame.MIN_BET)
            render(player)
        }
        bind("double_bet_button") { player ->
            if (player.blackjackSession != null) return@bind
            player.blackjackBet = (player.blackjackBet * 2).coerceAtMost(BlackjackGame.MAX_BET)
            render(player)
        }
        bind("bet_button") { player -> placeBet(player) }
        bind("hit_button") { player -> hit(player) }
        bind("stand_button") { player -> stand(player) }
        bind("double_action_button") { player -> doubleDown(player) }
        bind("split_button") { player ->
            if (player.blackjackSession != null) {
                player.sendMessage("Splitting isn't available yet.")
            }
        }
        bind("forfeit_button") { player -> forfeit(player) }
    }

    override fun open(player: Player) {
        super.open(player)
        render(player)
    }

    override fun getInterface(): GameInterface = GameInterface.BLACKJACK

    private fun placeBet(player: Player) {
        if (player.blackjackSession != null) return
        if (GambleBan.isGambleBanValid(player)) {
            player.sendMessage("You are currently gamble banned and cannot gamble.")
            return
        }
        val bet = player.blackjackBet
        if (bet < BlackjackGame.MIN_BET || bet > BlackjackGame.MAX_BET) {
            player.sendMessage("You must bet between ${BlackjackGame.MIN_BET} and ${BlackjackGame.MAX_BET} coins.")
            return
        }
        if (!player.inventory.containsItem(BlackjackGame.COINS, bet)) {
            player.sendMessage("You don't have $bet coins.")
            return
        }
        val session = BlackjackGame.startRound(player, bet)
        if (BlackjackGame.isBlackjack(session.playerHand) || BlackjackGame.isBlackjack(session.dealerHand)) {
            finishRound(player, session)
        } else {
            render(player)
        }
    }

    private fun hit(player: Player) {
        val session = player.blackjackSession ?: return
        session.playerHand.add(session.draw())
        val value = BlackjackGame.handValue(session.playerHand)
        if (value >= 21) {
            if (value == 21) BlackjackGame.playDealer(session)
            finishRound(player, session)
        } else {
            render(player)
        }
    }

    private fun stand(player: Player) {
        val session = player.blackjackSession ?: return
        BlackjackGame.playDealer(session)
        finishRound(player, session)
    }

    private fun doubleDown(player: Player) {
        val session = player.blackjackSession ?: return
        if (session.playerHand.size != 2) {
            player.sendMessage("You can only double down on your first two cards.")
            return
        }
        if (!player.inventory.containsItem(BlackjackGame.COINS, session.bet)) {
            player.sendMessage("You don't have enough coins to double down.")
            return
        }
        player.inventory.deleteItem(BlackjackGame.COINS, session.bet)
        session.bet *= 2
        session.doubled = true
        session.playerHand.add(session.draw())
        if (BlackjackGame.handValue(session.playerHand) <= 21) {
            BlackjackGame.playDealer(session)
        }
        finishRound(player, session)
    }

    private fun forfeit(player: Player) {
        val session = player.blackjackSession ?: return
        player.blackjackSession = null
        player.blackjackLosses++
        player.blackjackProfit -= session.bet
        player.sendMessage("You forfeit and lose your stake of ${session.bet} coins.")
        render(player)
    }

    private fun finishRound(player: Player, session: BlackjackGame.Session) {
        val result = BlackjackGame.resolve(player, session)
        player.sendMessage(result.outcome)
        render(player, session, result)
    }

    /** Refreshes every dynamic label and toggles the bet controls vs. in-hand action buttons. */
    private fun render(player: Player, finishedSession: BlackjackGame.Session? = null, result: BlackjackGame.Result? = null) {
        val session = player.blackjackSession
        val dispatcher = player.packetDispatcher

        dispatcher.sendComponentText(getInterface(), getComponent("stats_text"),
            "Wins: ${player.blackjackWins}   Losses: ${player.blackjackLosses}   Profit: ${player.blackjackProfit}")
        dispatcher.sendComponentText(getInterface(), getComponent("seed_text"),
            "Client seed: ${BlackjackGame.clientSeedFor(player)} (click Seed to change before your next bet)")
        dispatcher.sendComponentText(getInterface(), getComponent("bet_text"), "Bet: ${player.blackjackBet}")

        val inRound = session != null
        dispatcher.sendComponentVisibility(getInterface(), getComponent("bet_row"), inRound)
        dispatcher.sendComponentVisibility(getInterface(), getComponent("action_row"), !inRound)

        val text = when {
            session != null -> buildString {
                append("<col=808080>Provably fair - server seed hash: ${ProvablyFair.sha256Hex(session.serverSeed)}</col><br><br>")
                append("Your hand: ${BlackjackGame.handText(session.playerHand)} (${BlackjackGame.handValue(session.playerHand)}).<br>")
                append("Dealer shows: ${session.dealerHand[0]}, and a hidden card.")
            }
            finishedSession != null && result != null -> buildString {
                append("Your hand: ${BlackjackGame.handText(finishedSession.playerHand)} (${BlackjackGame.handValue(finishedSession.playerHand)}).<br>")
                append("Dealer's hand: ${BlackjackGame.handText(finishedSession.dealerHand)} (${BlackjackGame.handValue(finishedSession.dealerHand)}).<br><br>")
                append("${result.outcome}<br><br>")
                append("<col=808080>Provably fair - server seed: ${finishedSession.serverSeed} | hash: ${ProvablyFair.sha256Hex(finishedSession.serverSeed)}</col><br>")
                append("<col=808080>Client seed: ${finishedSession.clientSeed}</col>")
            }
            else -> "DEALER MUST DRAW TO 16 AND STAND ON ALL 17'S<br>INSURANCE PAYS 2-1  |  BJ PAYS 3-2<br><br>Set your bet and press Bet to deal in."
        }
        dispatcher.sendComponentText(getInterface(), getComponent("table_text"), text)
    }
}
