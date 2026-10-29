package com.gadscape.plugins.item.customs

import com.gadscape.api.model.Bond
import com.gadscape.game.content.commands.DeveloperCommands
import com.gadscape.game.content.donator.DonatorRanks
import com.gadscape.game.item.CustomItemId
import com.gadscape.scripts.item.actions.ItemActionScript
import com.gadscape.website.WebsiteService
import com.gadscape.website.donatorPoints
import com.zenyte.game.GameConstants
import com.zenyte.game.item.Item
import com.zenyte.game.util.Colour
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.container.RequestResult
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.game.world.entity.player.dialogue.options
import com.zenyte.game.world.entity.player.privilege.MemberRank

/**
 * Donator rank tickets (CustomItemId 50001-50006, made by the editor's CurrencyDonatorPatch). "Redeem" gives the
 * ticket's rank and its free donator points, the same as a website DONATOR_RANK claim (DonatorRanks.claimRank). A
 * ticket for a rank at or below the player's own is kept.
 */
class DonatorTicketItemAction : ItemActionScript() {
    init {
        items(TICKETS.keys)

        "Redeem" {
            redeem(player, item)
        }
    }

    private fun redeem(player: Player, ticket: Item) {
        val rank = TICKETS[ticket.id] ?: return
        if (!mayRedeem(player, "donator tickets")) return
        if (DonatorRanks.upgraded(player.memberRank, rank) == player.memberRank) {
            player.dialogue {
                item(ticket, "You are already a ${player.memberRank}, so this ticket for $rank can't do anything for you. " +
                        "It stays in your inventory.")
            }
            return
        }
        player.dialogue {
            item(ticket, "Redeem this ticket to become a ${Colour.DARK_BLUE.wrap(rank.toString())}" +
                    (if (rank.freeDonatorPoints > 0) " and get ${Colour.DARK_BLUE.wrap(rank.freeDonatorPoints.toString())} donator points" else "") + "?")
            options("Redeem the ${ticket.name}?") {
                "Yes." { redeemTicket(player, ticket, rank) }
                "No." {}
            }
        }
    }

    private fun redeemTicket(player: Player, ticket: Item, rank: MemberRank) {
        // checked again: the rank may have changed while the dialogue was open
        if (DonatorRanks.upgraded(player.memberRank, rank) == player.memberRank) return
        if (player.inventory.deleteItem(Item(ticket.id, 1)).result != RequestResult.SUCCESS) return
        player.donatorPoints += rank.freeDonatorPoints
        DonatorRanks.upgrade(player, rank, announce = true)
    }

    companion object {
        val TICKETS = linkedMapOf(
            CustomItemId.GAD_DONATOR_TICKET to MemberRank.DONATOR,
            CustomItemId.SUPER_GAD_DONATOR_TICKET to MemberRank.SUPER,
            CustomItemId.EXTREME_GAD_DONATOR_TICKET to MemberRank.EXTREME,
            CustomItemId.LEGENDARY_GAD_DONATOR_TICKET to MemberRank.LEGENDARY,
            CustomItemId.ROYAL_GAD_DONATOR_TICKET to MemberRank.ROYAL,
            CustomItemId.DIVINE_GAD_DONATOR_TICKET to MemberRank.DIVINE,
        )
    }
}

/**
 * The $1 Donator pin (stackable) and the Donator bonds ($10-$1000), CustomItemId 50007-50013. "Redeem" adds the
 * value to the lifetime donated total (the total MemberRank.forTotalCents reads, through
 * [WebsiteService.redeemPin] like the other pins, see [Bond]) plus [DONATOR_POINTS_PER_DOLLAR] donator points per dollar,
 * and raises the rank when the new total reaches a higher rank's price (never lowers it). "Combine" on pins makes
 * a bond of the chosen value.
 */
class DonatorPinBondItemAction : ItemActionScript() {
    init {
        item(CustomItemId.DONATOR_PIN_1)
        items(BONDS.keys)

        "Redeem" {
            redeem(player, item)
        }
        "Combine" {
            combine(player)
        }
    }

    private fun redeem(player: Player, used: Item) {
        if (!mayRedeem(player, "donator pins and bonds")) return
        val pin = used.id == CustomItemId.DONATOR_PIN_1
        val held = if (pin) player.inventory.container.getRawAmountOf(used.id) else 1
        val dollars = if (pin) held else BONDS[used.id] ?: return
        val bond = Bond[used.id] ?: return
        if (dollars <= 0) return
        val redeemed = Item(used.id, if (pin) held else 1)
        val points = dollars * DONATOR_POINTS_PER_DOLLAR
        val what = if (pin) "$held Donator pin${if (held == 1) "" else "s"}" else "this ${used.name}"
        player.dialogue {
            item(redeemed, "Redeem $what and add ${Colour.DARK_BLUE.wrap("$$dollars")} to your donator total" +
                    (if (points > 0) " (and get ${Colour.DARK_BLUE.wrap(points.toString())} donator points)" else "") +
                    "?<br>Your donator rank goes up as your total grows.")
            options("Redeem $what?") {
                "Yes." {
                    if (player.inventory.container.getRawAmountOf(redeemed.id) >= redeemed.amount)
                        WebsiteService.redeemPin(player, bond, redeemed.amount, points)
                }
                "No." {}
            }
        }
    }

    private fun combine(player: Player) {
        val pins = player.inventory.container.getRawAmountOf(CustomItemId.DONATOR_PIN_1)
        val affordable = BONDS.entries.filter { it.value <= pins }
        if (affordable.isEmpty()) {
            player.dialogue { item(Item(CustomItemId.DONATOR_PIN_1, pins), "You need at least 10 Donator pins to make a bond.") }
            return
        }
        choose(player, affordable, 0)
    }

    /** The bonds the player can make, four to a page with "More..." when there are more. */
    private fun choose(player: Player, bonds: List<Map.Entry<Int, Int>>, from: Int) {
        val page = if (bonds.size - from > 5) bonds.subList(from, from + 4) else bonds.subList(from, bonds.size)
        player.dialogue {
            options("Combine pins into which bond?") {
                for ((bond, value) in page) {
                    "$$value bond ($value pins)" { make(player, bond, value) }
                }
                if (from + page.size < bonds.size) "More..." { choose(player, bonds, from + page.size) }
            }
        }
    }

    private fun make(player: Player, bond: Int, value: Int) {
        if (player.inventory.container.getRawAmountOf(CustomItemId.DONATOR_PIN_1) < value) return
        val pinsLeft = player.inventory.container.getRawAmountOf(CustomItemId.DONATOR_PIN_1) - value
        if (pinsLeft > 0 && !player.inventory.hasFreeSlots()) {
            player.sendMessage("You need a free inventory space for the bond.")
            return
        }
        if (player.inventory.deleteItem(Item(CustomItemId.DONATOR_PIN_1, value)).result != RequestResult.SUCCESS) return
        player.inventory.addOrDrop(Item(bond, 1))
        player.dialogue { item(Item(bond, 1), "You combine $value Donator pins into a ${Colour.DARK_BLUE.wrap("$$value")} Donator bond.") }
    }

    companion object {
        /** Donator points for each dollar a pin or bond adds to the total. */
        const val DONATOR_POINTS_PER_DOLLAR = 1

        /** Bond item -> its dollar value (also the pins it takes), cheapest first. */
        val BONDS = linkedMapOf(
            CustomItemId.DONATOR_BOND_10 to 10,
            CustomItemId.DONATOR_BOND_20 to 20,
            CustomItemId.DONATOR_BOND_50 to 50,
            CustomItemId.DONATOR_BOND_100 to 100,
            CustomItemId.DONATOR_BOND_250 to 250,
            CustomItemId.DONATOR_BOND_1000 to 1000,
        )
    }
}

/** The same world and switch checks as the other donator pins (DonatorPinRedeemDialogue). */
private fun mayRedeem(player: Player, what: String): Boolean {
    if (GameConstants.isOwner(player)) return true
    if (!DeveloperCommands.enabledDPinRedeeming) {
        player.dialogue { plain("Redeeming $what is currently disabled.") }
        return false
    }
    if (GameConstants.WORLD_PROFILE.private || GameConstants.WORLD_PROFILE.isBeta()) {
        player.dialogue { plain("You cannot redeem $what on this world.") }
        return false
    }
    return true
}
