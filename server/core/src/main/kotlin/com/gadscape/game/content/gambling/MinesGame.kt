package com.gadscape.game.content.gambling

import com.gadscape.game.content.gambling.provablyfair.ProvablyFair
import com.gadscape.game.content.gambling.provablyfair.ProvablyFairMines
import com.zenyte.game.content.flowerpoker.GambleBan
import com.zenyte.game.item.Item
import com.zenyte.game.util.Colour
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.dialogue.Dialogue
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.game.world.entity.player.dialogue.options
import java.util.BitSet
import kotlin.math.roundToLong

/**
 * Standalone, solo "Mines" gamble: pick a mine count and a bet, then reveal tiles on a 5x5 (25
 * cell) grid one at a time. Each safe reveal raises the cash-out multiplier towards the fair odds
 * for how many mines you're dodging; hitting a mine loses the stake. The full mine layout comes
 * from [ProvablyFairMines], seeded once per round and revealed (mines included) when the round
 * ends, so every pick can be checked against the real layout afterwards.
 */
object MinesGame {

    private const val COINS = 995
    private const val MIN_BET = 1_000
    private const val MAX_BET = 50_000_000
    private const val HOUSE_EDGE = 0.03 // 3%, matches the fair-odds-minus-edge convention used by casino Mines clones
    private const val GRID_SIZE = 5

    private class Session(val bet: Int, val mineCount: Int, val serverSeed: String, val clientSeed: String) {
        val layout: BitSet = ProvablyFairMines.generateLayout(clientSeed, serverSeed, 0, mineCount)
        val revealed = BitSet(ProvablyFairMines.SLOTS)
        var picks = 0
    }

    fun start(player: Player) {
        if (GambleBan.isGambleBanValid(player)) {
            player.sendMessage("You are currently gamble banned and cannot gamble.")
            return
        }
        player.sendInputInt("How many mines (1-24) should the 5x5 grid have?") { mines ->
            if (mines < 1 || mines > 24) {
                player.sendMessage("Mine count must be between 1 and 24.")
                return@sendInputInt
            }
            player.sendInputInt("Enter the amount of coins to bet ($MIN_BET - $MAX_BET):") { amount ->
                openBet(player, mines, amount)
            }
        }
    }

    private fun openBet(player: Player, mines: Int, amount: Int) {
        if (amount < MIN_BET || amount > MAX_BET) {
            player.sendMessage("You must bet between $MIN_BET and $MAX_BET coins.")
            return
        }
        if (!player.inventory.containsItem(COINS, amount)) {
            player.sendMessage("You don't have $amount coins.")
            return
        }
        player.inventory.deleteItem(COINS, amount)

        val serverSeed = ProvablyFair.randomSeed()
        val session = Session(amount, mines, serverSeed, player.username)
        player.temporaryAttributes["gambling:mines"] = session

        player.dialogue {
            plain("<col=808080>Provably fair - server seed hash: ${ProvablyFair.sha256Hex(serverSeed)}</col>")
            plain("Grid set: $mines mines, staking $amount coins. Pick tiles 1-25, or cash out any time you're ahead.")
            pickMenu(player, session)
        }
    }

    private fun Dialogue.pickMenu(player: Player, session: Session) {
        val safeLeft = ProvablyFairMines.SLOTS - session.mineCount - session.picks
        if (session.picks == 0) {
            options("What do you do?") {
                "Pick a tile" { promptPick(player, session) }
                "Forfeit (lose your stake)" { forfeit(player, session) }
            }
        } else {
            val multiplier = currentMultiplier(session)
            val cashOut = (session.bet * multiplier).roundToLong()
            plain("Safe picks so far: ${session.picks}. Current cash-out: $cashOut coins. $safeLeft safe tiles remain.")
            options("What do you do?") {
                "Pick another tile" { promptPick(player, session) }
                "Cash out ($cashOut coins)" { cashOut(player, session) }
            }
        }
    }

    private fun promptPick(player: Player, session: Session) {
        player.sendInputInt("Pick a tile (1-25):") { tile ->
            val index = tile - 1
            if (index !in 0 until ProvablyFairMines.SLOTS) {
                player.sendMessage("Pick a number between 1 and 25.")
                return@sendInputInt
            }
            if (session.revealed.get(index)) {
                player.sendMessage("You've already revealed that tile.")
                return@sendInputInt
            }
            session.revealed.set(index)

            if (session.layout.get(index)) {
                loseToMine(player, session, index)
            } else {
                session.picks++
                player.dialogue {
                    plain("Tile ${tile} was safe!")
                    pickMenu(player, session)
                }
            }
        }
    }

    /** Fair-odds multiplier for having safely revealed [Session.picks] tiles, minus the house edge. */
    private fun currentMultiplier(session: Session): Double {
        var survivalProbability = 1.0
        val safeCells = ProvablyFairMines.SLOTS - session.mineCount
        for (i in 0 until session.picks) {
            survivalProbability *= (safeCells - i).toDouble() / (ProvablyFairMines.SLOTS - i)
        }
        val fairMultiplier = 1.0 / survivalProbability
        return fairMultiplier * (1 - HOUSE_EDGE)
    }

    private fun cashOut(player: Player, session: Session) {
        val payout = (session.bet * currentMultiplier(session)).roundToLong()
        player.temporaryAttributes.remove("gambling:mines")
        player.inventory.addOrDrop(Item(COINS, payout.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        player.dialogue {
            plain(Colour.RS_GREEN.wrap("You cash out with $payout coins after ${session.picks} safe picks."))
            revealProof(player, session)
        }
    }

    private fun loseToMine(player: Player, session: Session, hitIndex: Int) {
        player.temporaryAttributes.remove("gambling:mines")
        player.dialogue {
            plain(Colour.RS_RED.wrap("Boom! Tile ${hitIndex + 1} was a mine. You lose your stake of ${session.bet} coins."))
            revealProof(player, session)
        }
    }

    private fun forfeit(player: Player, session: Session) {
        player.temporaryAttributes.remove("gambling:mines")
        player.sendMessage("You forfeit and lose your stake.")
    }

    private fun Dialogue.revealProof(player: Player, session: Session) {
        val mineTiles = (0 until ProvablyFairMines.SLOTS).filter { session.layout.get(it) }.map { it + 1 }
        plain("Full layout - mines were at: ${mineTiles.joinToString(", ")}")
        plain("<col=808080>Provably fair - server seed: ${session.serverSeed} | hash: ${ProvablyFair.sha256Hex(session.serverSeed)}</col>")
        plain("<col=808080>Verify: ProvablyFairMines.generateLayout(\"${session.clientSeed}\", serverSeed, 0, ${session.mineCount})</col>")
    }
}
