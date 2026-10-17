package com.gadscape.game.content.gambling

import com.gadscape.game.content.gambling.provablyfair.ProvablyFair
import com.zenyte.game.world.entity.attribute
import com.zenyte.game.world.entity.persistentAttribute
import com.zenyte.game.world.entity.player.Player
import kotlin.math.roundToLong

/**
 * Solo Blackjack against the house, dealt via provably fair draws (a Fisher-Yates-style bag
 * over 52 indices, seeded per-round from the player's own editable client seed plus a fresh
 * server seed - see [ProvablyFair]), so the whole shoe's order can be replayed once the server
 * seed is revealed. Dealer stands on 17+, blackjack pays 3:2, ordinary wins pay 2:1, pushes
 * refund the stake. Presentation lives in [BlackjackInterface]; this object only holds the deal
 * math and session state.
 */

var Player.blackjackWins by persistentAttribute("gambling:blackjackWins", 0)
var Player.blackjackLosses by persistentAttribute("gambling:blackjackLosses", 0)
var Player.blackjackProfit by persistentAttribute("gambling:blackjackProfit", 0L)
var Player.blackjackClientSeed by persistentAttribute("gambling:blackjackClientSeed", "")
var Player.blackjackBet by attribute("gambling:blackjackPendingBet", BlackjackGame.MIN_BET)

var Player.blackjackSession: BlackjackGame.Session?
    get() = temporaryAttributes["gambling:blackjackSession"] as? BlackjackGame.Session
    set(value) {
        if (value == null) temporaryAttributes.remove("gambling:blackjackSession")
        else temporaryAttributes["gambling:blackjackSession"] = value
    }

object BlackjackGame {

    const val COINS = 995
    const val MIN_BET = 1_000
    const val MAX_BET = 50_000_000
    private const val DECK_SIZE = 52

    fun clientSeedFor(player: Player): String {
        if (player.blackjackClientSeed.isEmpty()) {
            player.blackjackClientSeed = ProvablyFair.randomSeed().take(16)
        }
        return player.blackjackClientSeed
    }

    data class Card(val rank: Int, val suit: Int) {
        val baseValue: Int get() = when {
            rank == 1 -> 11
            rank >= 10 -> 10
            else -> rank
        }

        override fun toString(): String {
            val name = when (rank) {
                1 -> "Ace"
                11 -> "Jack"
                12 -> "Queen"
                13 -> "King"
                else -> rank.toString()
            }
            val suitName = when (suit) {
                0 -> "Hearts"
                1 -> "Diamonds"
                2 -> "Clubs"
                else -> "Spades"
            }
            return "$name of $suitName"
        }
    }

    class Session(var bet: Int, val serverSeed: String, val clientSeed: String) {
        val bag = IntArray(DECK_SIZE) { it }
        var remaining = DECK_SIZE
        var drawCounter = 0L
        var doubled = false
        var finished = false

        val playerHand = mutableListOf<Card>()
        val dealerHand = mutableListOf<Card>()

        fun draw(): Card {
            val idx = ProvablyFair.randomInt(clientSeed, serverSeed, drawCounter++.toInt(), remaining)
            val cardIndex = bag[idx]
            remaining--
            bag[idx] = bag[remaining]
            return Card(cardIndex % 13 + 1, cardIndex / 13)
        }
    }

    fun startRound(player: Player, bet: Int): Session {
        player.inventory.deleteItem(COINS, bet)
        val serverSeed = ProvablyFair.randomSeed()
        val session = Session(bet, serverSeed, clientSeedFor(player))
        player.blackjackSession = session
        repeat(2) {
            session.playerHand.add(session.draw())
            session.dealerHand.add(session.draw())
        }
        return session
    }

    fun handText(hand: List<Card>) = hand.joinToString(", ")

    /** Standard soft-ace handling: start every ace at 11, then downgrade to 1 one at a time while busting. */
    fun handValue(hand: List<Card>): Int {
        var total = hand.sumOf { it.baseValue }
        var aces = hand.count { it.rank == 1 }
        while (total > 21 && aces > 0) {
            total -= 10
            aces--
        }
        return total
    }

    fun isBlackjack(hand: List<Card>) = hand.size == 2 && handValue(hand) == 21

    fun playDealer(session: Session) {
        while (handValue(session.dealerHand) < 17) {
            session.dealerHand.add(session.draw())
        }
    }

    class Result(val payout: Long, val outcome: String, val won: Boolean, val push: Boolean)

    fun resolve(player: Player, session: Session): Result {
        session.finished = true
        val playerValue = handValue(session.playerHand)
        val dealerValue = handValue(session.dealerHand)
        val playerBlackjack = isBlackjack(session.playerHand)
        val dealerBlackjack = isBlackjack(session.dealerHand)

        val payout: Long
        val outcome: String
        var won = false
        var push = false
        when {
            playerValue > 21 -> {
                payout = 0
                outcome = "Bust! You lose your stake of ${session.bet} coins."
            }
            playerBlackjack && dealerBlackjack -> {
                payout = session.bet.toLong()
                outcome = "Both blackjack - push. Your ${session.bet} coins are refunded."
                push = true
            }
            playerBlackjack -> {
                payout = (session.bet * 2.5).roundToLong()
                outcome = "Blackjack! You receive $payout coins."
                won = true
            }
            dealerBlackjack -> {
                payout = 0
                outcome = "Dealer has blackjack. You lose your stake of ${session.bet} coins."
            }
            dealerValue > 21 -> {
                payout = session.bet * 2L
                outcome = "Dealer busts! You receive $payout coins."
                won = true
            }
            playerValue > dealerValue -> {
                payout = session.bet * 2L
                outcome = "You win! You receive $payout coins."
                won = true
            }
            playerValue == dealerValue -> {
                payout = session.bet.toLong()
                outcome = "Push. Your ${session.bet} coins are refunded."
                push = true
            }
            else -> {
                payout = 0
                outcome = "Dealer wins. You lose your stake of ${session.bet} coins."
            }
        }

        if (payout > 0) {
            player.inventory.addOrDrop(com.zenyte.game.item.Item(COINS, payout.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        }
        if (!push) {
            if (won) player.blackjackWins++ else player.blackjackLosses++
        }
        player.blackjackProfit += payout - session.bet
        player.blackjackSession = null
        return Result(payout, outcome, won, push)
    }
}
