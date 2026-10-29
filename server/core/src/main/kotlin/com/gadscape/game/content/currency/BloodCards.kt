package com.gadscape.game.content.currency

import com.zenyte.game.item.Item
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.dialogue.dialogue

/**
 * Blood cards: 1,000 blood tokens each, stackable and tradeable. Use tokens on a bank booth or a banker to make
 * cards, and cards to turn them back into tokens (never past the 2,147,483,647 a stack can hold).
 */
object BloodCards {

    private const val MAX_STACK = Int.MAX_VALUE.toLong()

    /** Item on a bank booth / banker: true when it was tokens or cards (and so is handled here). */
    @JvmStatic
    fun handle(player: Player, item: Item): Boolean {
        when (item.id) {
            BloodTokens.TOKEN -> toCards(player)
            BloodTokens.CARD -> toTokens(player)
            BloodTokens.COINS, BloodTokens.PLATINUM_TOKEN -> {
                // only before the login conversion ran (CoinConversion)
                CoinConversion.convert(player)
            }
            else -> return false
        }
        return true
    }

    private fun toCards(player: Player) {
        val tokens = player.inventory.container.getRawAmountOf(BloodTokens.TOKEN)
        val most = tokens / BloodTokens.TOKENS_PER_CARD
        if (most <= 0) {
            player.dialogue { item(Item(BloodTokens.TOKEN, tokens), "You need at least 1,000 blood tokens for a Blood card.") }
            return
        }
        player.sendInputInt("How many Blood cards (1,000 tokens each)? You can make up to $most.") { asked ->
            val have = player.inventory.container.getRawAmountOf(BloodTokens.TOKEN)
            val cards = minOf(asked.toLong(), (have / BloodTokens.TOKENS_PER_CARD).toLong(),
                MAX_STACK - player.inventory.container.getRawAmountOf(BloodTokens.CARD)).toInt()
            if (cards <= 0) return@sendInputInt
            val cost = cards * BloodTokens.TOKENS_PER_CARD
            if (!player.inventory.containsItem(BloodTokens.CARD) && have > cost && !player.inventory.hasFreeSlots()) {
                player.sendMessage("You need a free inventory space for the Blood cards.")
                return@sendInputInt
            }
            if (player.inventory.deleteItem(BloodTokens.TOKEN, cost).succeededAmount != cost) return@sendInputInt
            player.inventory.addOrDrop(BloodTokens.CARD, cards)
            player.dialogue {
                item(Item(BloodTokens.CARD, cards), "The bank turns ${BloodTokens.format(cost.toLong())} into " +
                        "$cards Blood card${if (cards == 1) "" else "s"}.")
            }
        }
    }

    private fun toTokens(player: Player) {
        val cardsHeld = player.inventory.container.getRawAmountOf(BloodTokens.CARD)
        if (cardsHeld <= 0) return
        player.sendInputInt("How many Blood cards would you like to cash in? You have $cardsHeld.") { asked ->
            val held = player.inventory.container.getRawAmountOf(BloodTokens.CARD)
            val tokensHeld = player.inventory.container.getRawAmountOf(BloodTokens.TOKEN).toLong()
            val room = (MAX_STACK - tokensHeld) / BloodTokens.TOKENS_PER_CARD
            val cards = minOf(asked.toLong(), held.toLong(), room).toInt()
            if (cards <= 0) {
                if (room <= 0 && asked > 0) player.sendMessage("You can't hold any more blood tokens.")
                return@sendInputInt
            }
            if (asked > room) player.sendMessage("A stack holds at most 2,147,483,647 blood tokens, so only $cards card${if (cards == 1) " was" else "s were"} cashed in.")
            if (tokensHeld == 0L && held > cards && !player.inventory.hasFreeSlots()) {
                player.sendMessage("You need a free inventory space for the blood tokens.")
                return@sendInputInt
            }
            if (player.inventory.deleteItem(BloodTokens.CARD, cards).succeededAmount != cards) return@sendInputInt
            val tokens = cards * BloodTokens.TOKENS_PER_CARD
            player.inventory.addOrDrop(BloodTokens.TOKEN, tokens)
            player.dialogue {
                item(Item(BloodTokens.TOKEN, tokens), "The bank turns $cards Blood card${if (cards == 1) "" else "s"} into " +
                        "${BloodTokens.format(tokens.toLong())}.")
            }
        }
    }
}
