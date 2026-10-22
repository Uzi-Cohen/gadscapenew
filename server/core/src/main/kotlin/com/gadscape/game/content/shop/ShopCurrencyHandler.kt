package com.gadscape.game.content.shop

import com.gadscape.api.service.vote.totalVoteCredits
import com.gadscape.game.world.entity.player.bountyHunterPoints
import com.gadscape.game.world.entity.player.exchangePoints
import com.gadscape.game.world.entity.player.pvmArenaPoints
import com.zenyte.game.content.skills.afk.AfkSkillingConstants
import com.zenyte.game.item.Item
import com.zenyte.game.model.shop.CurrencyPalette
import com.zenyte.game.model.shop.ShopCurrency
import com.zenyte.game.world.entity.player.Player
import kotlin.math.max
import kotlin.math.min

object ShopCurrencyHandler {

    /**
     * Whether [getAmount], [remove] and [add] handle [type]. Callers that take something from the player before paying
     * them (selling to a shop) check this first, so an unhandled currency can't destroy the item.
     */
    @JvmStatic
    fun isSupported(type: CurrencyPalette): Boolean = when (type) {
        ShopCurrency.VOTE_POINTS, ShopCurrency.AFK_POINTS, ShopCurrency.BH_POINTS, ShopCurrency.LOYALTY_POINTS,
        ShopCurrency.EXCHANGE_POINTS, ShopCurrency.TOURNAMENT_POINTS, ShopCurrency.PVM_ARENA_POINTS,
        ShopCurrency.SLAYER_POINTS -> true
        else -> type.isPhysical && type.id > 0
    }

    @JvmStatic
    fun getAmount(type: CurrencyPalette, player: Player): Int {
        return when(type) {
            ShopCurrency.VOTE_POINTS -> player.totalVoteCredits
            ShopCurrency.AFK_POINTS -> player.getNumericAttribute(AfkSkillingConstants.AFK_POINTS).toInt()
            ShopCurrency.BH_POINTS -> player.bountyHunterPoints
            ShopCurrency.LOYALTY_POINTS -> player.loyaltyManager.loyaltyPoints
            ShopCurrency.EXCHANGE_POINTS -> player.exchangePoints
            ShopCurrency.TOURNAMENT_POINTS -> player.getNumericAttribute("tournament points").toInt()
            ShopCurrency.PVM_ARENA_POINTS -> player.pvmArenaPoints.toInt()
            // Universal shop Slayer tab (category 8); setSlayerPoints(.., true) refreshes varbit 4068.
            ShopCurrency.SLAYER_POINTS -> player.slayer.slayerPoints
            else -> {
                if (type.isPhysical) {
                    val itemId = type.id.takeIf { it > 0 } ?: error("Invalid item id: ${type.id} for currency type: ${type::class.simpleName}")
                    return player.inventory.getAmountOf(itemId)
                } else
                    error("Invalid currency type: ${type::class.simpleName}")
            }
        }
    }

    @JvmStatic
    fun remove(type: ShopCurrency, player: Player, amount: Int) {
        when(type) {
            ShopCurrency.VOTE_POINTS -> player.totalVoteCredits -= amount
            ShopCurrency.BH_POINTS -> player.bountyHunterPoints -= amount
            ShopCurrency.AFK_POINTS -> player.incrementNumericAttribute(AfkSkillingConstants.AFK_POINTS, -amount)
            ShopCurrency.LOYALTY_POINTS -> {
                val currentAmount = player.loyaltyManager.loyaltyPoints
                player.loyaltyManager.setLoyaltyPoints(max(0, (currentAmount - amount)))
            }
            ShopCurrency.TOURNAMENT_POINTS -> player.incrementNumericAttribute("tournament points", -amount)
            ShopCurrency.EXCHANGE_POINTS -> player.exchangePoints -= amount
            ShopCurrency.PVM_ARENA_POINTS -> player.pvmArenaPoints -= amount
            ShopCurrency.SLAYER_POINTS -> player.slayer.setSlayerPoints(max(0, player.slayer.slayerPoints - amount), true)
            else -> {
                if (type.isPhysical) {
                    val itemId = type.id.takeIf { it > 0 } ?: error("Invalid item id: ${type.id} for currency type: ${type::class.simpleName}")
                    player.inventory.deleteItem(Item(itemId, amount))
                } else
                    error("Invalid currency type: ${type::class.simpleName}")
            }
        }
    }

    @JvmStatic
    fun add(type: ShopCurrency, player: Player, amount: Int) {
        when(type) {
            ShopCurrency.VOTE_POINTS -> player.totalVoteCredits += amount
            ShopCurrency.BH_POINTS -> player.bountyHunterPoints += amount
            ShopCurrency.AFK_POINTS -> player.incrementNumericAttribute(AfkSkillingConstants.AFK_POINTS, amount)
            ShopCurrency.LOYALTY_POINTS -> {
                val currentAmount = player.loyaltyManager.loyaltyPoints
                player.loyaltyManager.setLoyaltyPoints(min((currentAmount + amount), Int.MAX_VALUE))
            }
            ShopCurrency.TOURNAMENT_POINTS -> player.incrementNumericAttribute("tournament points", amount)
            ShopCurrency.EXCHANGE_POINTS -> player.exchangePoints += amount
            ShopCurrency.PVM_ARENA_POINTS -> player.pvmArenaPoints += amount
            ShopCurrency.SLAYER_POINTS -> player.slayer.setSlayerPoints(
                min(player.slayer.slayerPoints.toLong() + amount, Int.MAX_VALUE.toLong()).toInt(), true
            )
            else -> {
                if (type.isPhysical) {
                    val itemId = type.id.takeIf { it > 0 } ?: error("Invalid item id: ${type.id} for currency type: ${type::class.simpleName}")
                    player.inventory.addItem(Item(itemId, amount))
                } else
                    error("Invalid currency type: ${type::class.simpleName}")
            }
        }
    }
}
