package com.gadscape.plugins.item.customs

import com.gadscape.api.model.Bond
import com.gadscape.game.content.commands.DeveloperCommands
import com.gadscape.scripts.item.actions.ItemActionScript
import com.gadscape.website.WebsiteService
import com.zenyte.game.GameConstants
import com.zenyte.game.item.Item
import com.zenyte.game.item.ItemId
import com.zenyte.game.util.Colour
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.container.RequestResult
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.game.world.entity.player.dialogue.options

/**
 * "Redeem" on the old donator scrolls ($10/$50/$100/$200, sets-prayers area SKSP-17): the scroll's value is added to
 * the donator total exactly like donator pins, through [WebsiteService.redeemPin] with the equivalent pins
 * ($200 = two $100 pins), so the website link, the local fallback and the rank tiers stay in one place.
 */
class DonatorScrollItemAction : ItemActionScript() {
    init {
        items(ItemId._10_DONATOR_SCROLL, ItemId._50_DONATOR_SCROLL, ItemId._100_DONATOR_SCROLL, ItemId._200_DONATOR_SCROLL)

        "Redeem" {
            redeem(player, item)
        }
    }

    private fun pinsFor(scrollId: Int): List<Bond> = when (scrollId) {
        ItemId._10_DONATOR_SCROLL -> listOf(Bond.DONATOR_PIN_10)
        ItemId._50_DONATOR_SCROLL -> listOf(Bond.DONATOR_PIN_50)
        ItemId._100_DONATOR_SCROLL -> listOf(Bond.DONATOR_PIN_100)
        ItemId._200_DONATOR_SCROLL -> listOf(Bond.DONATOR_PIN_100, Bond.DONATOR_PIN_100)
        else -> emptyList()
    }

    private fun redeem(player: Player, scroll: Item) {
        val pins = pinsFor(scroll.id)
        if (pins.isEmpty()) return
        if (!DeveloperCommands.enabledDPinRedeeming && !GameConstants.isOwner(player)) {
            player.dialogue { plain("Redeeming donator scrolls is currently disabled.") }
            return
        }
        if ((GameConstants.WORLD_PROFILE.private || GameConstants.WORLD_PROFILE.isBeta()) && !GameConstants.isOwner(player)) {
            player.dialogue { plain("You cannot redeem donator scrolls on this world.") }
            return
        }
        val total = pins.sumOf { it.amount }
        player.dialogue {
            item(scroll, "Would you like to redeem this ${scroll.name} and add ${Colour.DARK_BLUE.wrap("$$total")} " +
                    "to your donator total?<br>Your donator rank goes up as your total grows.")
            options("Redeem the ${scroll.name}?") {
                "Yes." {
                    if (player.inventory.deleteItem(Item(scroll.id, 1)).result == RequestResult.SUCCESS) {
                        for (bond in pins) {
                            // redeemPin takes the pin out of the inventory again (and gives it back if the website fails).
                            player.inventory.addOrDrop(Item(bond.id, 1))
                            WebsiteService.redeemPin(player, bond)
                        }
                    }
                }
                "No." {}
            }
        }
    }
}
