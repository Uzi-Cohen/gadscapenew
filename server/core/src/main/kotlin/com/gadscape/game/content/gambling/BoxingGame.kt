package com.gadscape.game.content.gambling

import com.gadscape.game.content.gambling.provablyfair.ProvablyFair
import com.gadscape.game.content.gambling.provablyfair.ProvablyFairBoxing
import com.zenyte.game.content.flowerpoker.GambleBan
import com.zenyte.game.item.Item
import com.zenyte.game.util.Colour
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.dialogue.Dialogue
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.game.world.entity.player.dialogue.options

/**
 * Standalone, solo "Boxing" gamble: the player stakes coins against the house over a best-of-3
 * bout. Every punch is a provably fair 0-16 hit derived from [ProvablyFairBoxing]; the higher hit
 * wins the round, the house wins ties (the only house edge). The server seed used for the whole
 * bout is revealed at the end so the player can recompute every punch themselves.
 */
object BoxingGame {

    private const val COINS = 995
    private const val MIN_BET = 1_000
    private const val MAX_BET = 50_000_000
    private const val HOUSE_SEED_COMPONENT = "house"

    private class Session(val bet: Int, val serverSeed: String, val clientSeed: String) {
        var round = 0
        var playerWins = 0
        var houseWins = 0
    }

    fun start(player: Player) {
        if (GambleBan.isGambleBanValid(player)) {
            player.sendMessage("You are currently gamble banned and cannot gamble.")
            return
        }
        player.sendInputInt("Enter the amount of coins to bet ($MIN_BET - $MAX_BET):") { amount ->
            openBet(player, amount)
        }
    }

    private fun openBet(player: Player, amount: Int) {
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
        val session = Session(amount, serverSeed, player.username)
        player.temporaryAttributes["gambling:boxing"] = session

        player.dialogue {
            plain("<col=808080>Provably fair - server seed hash: ${ProvablyFair.sha256Hex(serverSeed)}</col>")
            plain("You step into the ring, staking ${amount} coins. Best of 3 rounds - the house wins any tie.")
            punchMenu(player, session)
        }
    }

    private fun Dialogue.punchMenu(player: Player, session: Session) {
        options("What do you do?") {
            "Punch!" {
                punch(player, session)
            }
            "Forfeit (lose your stake)" {
                player.temporaryAttributes.remove("gambling:boxing")
                player.sendMessage("You forfeit the bout and lose your stake.")
            }
        }
    }

    private fun punch(player: Player, session: Session) {
        val playerHit = ProvablyFairBoxing.hitForPlayer(1, session.round, session.clientSeed, HOUSE_SEED_COMPONENT, session.serverSeed)
        val houseHit = ProvablyFairBoxing.hitForPlayer(2, session.round, session.clientSeed, HOUSE_SEED_COMPONENT, session.serverSeed)
        session.round++

        val playerWonRound = playerHit > houseHit
        if (playerWonRound) session.playerWins++ else session.houseWins++

        player.dialogue {
            plain("You hit for $playerHit. The house hits for $houseHit." +
                    if (playerWonRound) " You win the round!" else " The house wins the round.")

            when {
                session.playerWins == 2 -> finish(player, session, won = true)
                session.houseWins == 2 -> finish(player, session, won = false)
                else -> punchMenu(player, session)
            }
        }
    }

    private fun Dialogue.finish(player: Player, session: Session, won: Boolean) {
        player.temporaryAttributes.remove("gambling:boxing")
        val hash = ProvablyFair.sha256Hex(session.serverSeed)
        if (won) {
            val payout = session.bet * 2
            player.inventory.addOrDrop(Item(COINS, payout))
            plain(Colour.RS_GREEN.wrap("You won the bout! You receive $payout coins."))
        } else {
            plain(Colour.RS_RED.wrap("You lost the bout and your stake of ${session.bet} coins."))
        }
        plain("<col=808080>Provably fair - server seed: ${session.serverSeed} | hash: $hash</col>")
        plain("<col=808080>Verify: sha256(\"${session.clientSeed}:$HOUSE_SEED_COMPONENT:${session.serverSeed}:hitNo\") for each punch.</col>")
    }
}
