package com.gadscape.game.content.currency

import com.google.common.eventbus.Subscribe
import com.zenyte.game.content.grandexchange.GrandExchangeHandler
import com.zenyte.game.item.Item
import com.zenyte.game.util.Colour
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.persistentAttribute
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.container.Container
import com.zenyte.game.world.entity.player.container.impl.bank.Bank
import com.zenyte.plugins.events.LoginEvent
import com.zenyte.utils.TextUtils
import org.slf4j.LoggerFactory

/** Set once the character's coins were turned into blood tokens. */
private var Player.coinsConvertedToTokens: Boolean by persistentAttribute("blood_tokens_coins_converted", false)

/**
 * Turns every coin a character still holds into blood tokens, once, at login: 1,000 coins (or one platinum token)
 * = 1 token, rounded up, in the inventory, bank, looting bag, Death's item retrieval and Grand Exchange collection
 * boxes. Each place gets its tokens back where its coins were. Coin amounts kept as numbers (Death's coffer, the
 * servant's moneybag) are already worth tokens at the same rate (BloodTokens) and are left as they are.
 */
object CoinConversion {

    private val logger = LoggerFactory.getLogger(CoinConversion::class.java)

    @Subscribe
    @JvmStatic
    fun onLogin(event: LoginEvent) {
        convert(event.player)
    }

    @JvmStatic
    fun convert(player: Player) {
        if (player.coinsConvertedToTokens) return
        var total = 0L
        total += inContainer(player, player.inventory.container)
        total += inBank(player, player.bank)
        total += inContainer(player, player.lootingBag.container)
        total += inContainer(player, player.retrievalService.container)
        val offers = GrandExchangeHandler.getOffers()?.get(TextUtils.formatNameForProtocol(player.username))
        if (offers != null) for (offer in offers.values) {
            val container = offer.container ?: continue
            total += inContainer(player, container)
        }
        player.coinsConvertedToTokens = true
        player.inventory.refresh()
        if (total > 0) {
            logger.info("Turned the coins of {} into {} blood tokens", player.name, total)
            player.sendMessage(Colour.RS_GREEN.wrap("Coins are gone: blood tokens are GadScape's currency now. " +
                    "Your coins were turned into ${BloodTokens.format(total)} (1,000 coins = 1 token)."))
        }
    }

    /** Coins in [items] by slot, in coins (a platinum token counts as 1,000). */
    private fun coinSlots(items: Map<Int, Item?>): List<Pair<Int, Long>> = items.entries.mapNotNull { (slot, item) ->
        if (item == null) return@mapNotNull null
        when (item.id) {
            BloodTokens.COINS -> slot to item.amount.toLong()
            BloodTokens.PLATINUM_TOKEN -> slot to item.amount.toLong() * BloodTokens.COINS_PER_TOKEN
            else -> null
        }
    }

    private fun tokensFor(coins: Long): Int = BloodTokens.costInTokens(coins)

    private fun inContainer(player: Player, container: Container): Long {
        val slots = coinSlots(container.items)
        if (slots.isEmpty()) return 0
        for ((slot, _) in slots) container.set(slot, null)
        val tokens = tokensFor(slots.sumOf { it.second })
        if (tokens > 0) {
            container.add(Item(BloodTokens.TOKEN, tokens)).onFailure { rest ->
                // a stack already at the int max: the rest goes to the bank, else to the floor
                player.bank.add(rest).onFailure { World.spawnFloorItem(it, player) }
            }
        }
        container.refresh(player)
        return tokens.toLong()
    }

    private fun inBank(player: Player, bank: Bank): Long {
        val slots = coinSlots(bank.container.items)
        if (slots.isEmpty()) return 0
        // highest slot first: the bank closes the gap after each removed slot
        for ((slot, _) in slots.sortedByDescending { it.first }) bank.set(slot, null)
        val tokens = tokensFor(slots.sumOf { it.second })
        if (tokens > 0) {
            bank.add(Item(BloodTokens.TOKEN, tokens)).onFailure { rest ->
                player.inventory.addOrDrop(rest)
            }
        }
        return tokens.toLong()
    }
}
