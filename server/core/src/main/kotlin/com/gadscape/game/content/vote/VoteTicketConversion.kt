package com.gadscape.game.content.vote

import com.gadscape.api.service.vote.voteShardsConverted
import com.gadscape.game.item.CustomItemId
import com.google.common.eventbus.Subscribe
import com.zenyte.game.item.Item
import com.zenyte.game.util.Colour
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.container.Container
import com.zenyte.game.world.entity.player.container.impl.bank.Bank
import com.zenyte.plugins.events.LoginEvent
import org.slf4j.LoggerFactory

/**
 * Vote tickets replace vote shards: at login, once, every vote shard the character holds (inventory, bank, looting
 * bag) becomes a vote ticket, 1 for 1, where the shards were.
 */
object VoteTicketConversion {

    private val logger = LoggerFactory.getLogger(VoteTicketConversion::class.java)

    @Subscribe
    @JvmStatic
    fun onLogin(event: LoginEvent) {
        convert(event.player)
    }

    @JvmStatic
    fun convert(player: Player) {
        if (player.voteShardsConverted) return
        var total = 0L
        total += inContainer(player, player.inventory.container)
        total += inBank(player, player.bank)
        total += inContainer(player, player.lootingBag.container)
        player.voteShardsConverted = true
        player.inventory.refresh()
        if (total > 0) {
            logger.info("Turned {} vote shards of {} into vote tickets", total, player.name)
            player.sendMessage(Colour.RS_GREEN.wrap("Vote shards are now vote tickets: your $total vote shard${if (total == 1L) " was" else "s were"} " +
                    "turned into as many vote tickets. Use them on a player to give vote points, or trade them."))
        }
    }

    private fun shardSlots(items: Map<Int, Item?>): List<Pair<Int, Long>> = items.entries.mapNotNull { (slot, item) ->
        if (item == null || item.id != CustomItemId.VOTE_SHARD) null else slot to item.amount.toLong()
    }

    private fun tickets(amount: Long): Int = amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun inContainer(player: Player, container: Container): Long {
        val slots = shardSlots(container.items)
        if (slots.isEmpty()) return 0
        for ((slot, _) in slots) container.set(slot, null)
        val amount = tickets(slots.sumOf { it.second })
        if (amount > 0) {
            container.add(Item(CustomItemId.VOTE_TICKET, amount)).onFailure { rest ->
                player.bank.add(rest).onFailure { World.spawnFloorItem(it, player) }
            }
        }
        container.refresh(player)
        return amount.toLong()
    }

    private fun inBank(player: Player, bank: Bank): Long {
        val slots = shardSlots(bank.container.items)
        if (slots.isEmpty()) return 0
        // highest slot first: the bank closes the gap after each removed slot
        for ((slot, _) in slots.sortedByDescending { it.first }) bank.set(slot, null)
        val amount = tickets(slots.sumOf { it.second })
        if (amount > 0) {
            bank.add(Item(CustomItemId.VOTE_TICKET, amount)).onFailure { rest ->
                player.inventory.addOrDrop(rest)
            }
        }
        return amount.toLong()
    }
}
