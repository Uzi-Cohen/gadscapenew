package com.gadscape.plugins.item.customs

import com.gadscape.api.service.vote.totalVoteCredits
import com.gadscape.api.service.user.updateVoteStatisticOnPlayerDetailsTab
import com.gadscape.game.item.CustomItemId
import com.zenyte.game.item.Item
import com.zenyte.game.model.item.ItemOnPlayerPlugin
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.container.RequestResult
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.game.world.entity.player.dialogue.options

/**
 * Use vote tickets (or old vote shards, which login turns into tickets) on a player to give them that many vote
 * points: one point a ticket. The tickets are used up.
 *
 * @author Stan van der Bend
 */
@Suppress("unused")
class VoteShardOnPlayer : ItemOnPlayerPlugin {

    override fun handleItemOnPlayerAction(player: Player?, item: Item?, slot: Int, target: Player?) {
        if (player == null || item == null || target == null) return
        val what = name(item)
        player.dialogue {
            options("Are you sure you wish to turn ${item.amount} $what into vote points for ${target.name}?") {
                dialogueOption("Yes", noPlayerMessage = true) {
                    transfer(player, target, item)
                }
                dialogueOption("No")
            }
        }
    }

    private fun name(item: Item) = (if (item.id == CustomItemId.VOTE_SHARD) "vote shard" else "vote ticket") + if (item.amount > 1) "s" else ""

    private fun transfer(player: Player, target: Player, item: Item) {
        if (!target.isLoggedIn || target.isNulled) {
            player.sendMessage("${target.name} isn't here any more.")
            return
        }
        if (player.inventory.deleteItem(item).result != RequestResult.SUCCESS) {
            player.sendMessage("You do not have enough ${name(item)} to transfer.")
            return
        }
        target.totalVoteCredits += item.amount
        target.updateVoteStatisticOnPlayerDetailsTab()
        player.sendMessage("You have turned ${item.amount} ${name(item)} into vote points for ${target.name}.")
        target.sendMessage("${player.name} has given you ${item.amount} vote point${if (item.amount > 1) "s" else ""}.")
    }

    override fun getItems(): IntArray = intArrayOf(CustomItemId.VOTE_TICKET, CustomItemId.VOTE_SHARD)
}
