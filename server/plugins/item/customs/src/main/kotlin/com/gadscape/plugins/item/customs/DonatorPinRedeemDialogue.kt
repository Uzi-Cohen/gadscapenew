package com.gadscape.plugins.item.customs

import com.gadscape.api.model.Bond
import com.gadscape.game.content.commands.DeveloperCommands
import com.gadscape.website.WebsiteService
import com.zenyte.game.GameConstants
import com.zenyte.game.item.Item
import com.zenyte.game.util.Colour
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.dialogue.Dialogue
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.game.world.entity.player.dialogue.options

/**
 * Handles the redeeming of donator pin items through [WebsiteService.redeemPin].
 *
 * @author Stan van der Bend
 */
class DonatorPinRedeemDialogue(player: Player, private val item: Item) : Dialogue(player) {

    override fun buildDialogue() {

        if (!DeveloperCommands.enabledDPinRedeeming){
            if (GameConstants.isOwner(player)) {
                player.sendDeveloperMessage(
                    "You are allowed to exchange donator pins even though they are disabled for others " +
                            "because you're just simply cooler than the rest.")
            } else {
                player.dialogue {
                plain("Redeeming donator pins is currently disabled.")
            }
                return
            }
        }

        if (GameConstants.WORLD_PROFILE.private || GameConstants.WORLD_PROFILE.isBeta()) {
            if (GameConstants.isOwner(player)) {
                player.sendDeveloperMessage(
                    "You are allowed to exchange donator pins on this world " +
                        "because you're just simply cooler than the rest.")
            } else {
                player.dialogue { plain("You cannot exchange donator pins on this world.") }
                return
            }
        }

        val bond = Bond[item.id] ?: error("Invalid dialogue initialised for non donator-pin item (item=$item)")
        val shade = bond.getShade()
        val shadedName = "$shade${item.name}</shad>"
        // The pin counts toward the donator rank (tracked on gadscape.com when the website link is on).
        item(item, "Would you like to redeem this $shadedName and add " +
                "${Colour.DARK_BLUE.wrap("$${bond.amount}")} to your donator total?<br>" +
                "Your donator rank goes up as your total grows.")
        options("Redeem the $shadedName?") {
            "Yes." { WebsiteService.redeemPin(player, bond) }
            "No." {}
        }
    }
}

fun Bond.getShade() = when (this) {
    Bond.DONATOR_PIN_10 -> "<shad=00FF00>"
    Bond.DONATOR_PIN_25 -> "<shad=FC02E7>"
    Bond.DONATOR_PIN_50 -> "<shad=006d62>"
    Bond.DONATOR_PIN_100 -> "<shad=600000>"
}
