package com.gadscape.plugins.item.customs

import com.gadscape.game.content.currency.BloodTokens
import com.gadscape.game.item.CustomItemId
import com.zenyte.game.content.follower.impl.MiscPet
import com.zenyte.game.item.Item
import com.zenyte.game.model.item.pluginextensions.ItemPlugin
import com.zenyte.game.world.broadcasts.BroadcastType
import com.zenyte.game.world.broadcasts.WorldBroadcasts
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.container.Container
import com.zenyte.game.world.entity.player.dialogue.dialogue
import java.util.concurrent.ThreadLocalRandom

/**
 * The vote mystery box (every 3rd claimed vote, VotePerks): Open gives one prize.
 *
 * - Very rare: a vote-only pet (1/500) or a piece of the Voter's outfit (1/300).
 * - Rare (1/40): a donator pin or 10-25 vote tickets.
 * - Uncommon (1/6): a holiday outfit piece, a graceful piece or a pet mystery box pet.
 * - Common, the rest: 25-150 blood tokens, a costume piece or 1-3 vote tickets.
 *
 * The costume, holiday and graceful pieces are the vote shop's (HomeVoteShopShop).
 */
@Suppress("unused")
class VoteMysteryBox : ItemPlugin() {

    override fun handle() {
        bind("Open") { player: Player, item: Item, container: Container, slotId: Int -> open(player, item, container, slotId) }
    }

    private fun open(player: Player, item: Item, container: Container, slotId: Int) {
        if (container.get(slotId)?.id != CustomItemId.VOTE_MYSTERY_BOX) return
        val (prize, tier) = roll()
        container.remove(slotId, 1)
        container.refresh(player)
        player.inventory.addOrDrop(prize)
        val name = prize.name
        val text = if (prize.amount > 1) "${prize.amount} x $name" else name
        if (tier >= RARE) {
            WorldBroadcasts.sendMessage("<img=48><col=e59400><shad=000000>${player.name} found $text in a vote mystery box!</shad></col>",
                BroadcastType.MYSTERY_BOX_RARE_ITEM, false)
        }
        player.dialogue { item(prize, "You open the vote mystery box and find $text!") }
    }

    private fun roll(): Pair<Item, Int> {
        val r = ThreadLocalRandom.current()
        val roll = r.nextDouble()
        var at = 1.0 / 500
        if (roll < at) return Item(VOTE_PETS[r.nextInt(VOTE_PETS.size)]) to VERY_RARE
        at += 1.0 / 300
        if (roll < at) return Item(VOTERS_OUTFIT[r.nextInt(VOTERS_OUTFIT.size)]) to VERY_RARE
        at += 1.0 / 40
        if (roll < at) return (if (r.nextBoolean()) Item(CustomItemId.DONATOR_PIN_1) else Item(CustomItemId.VOTE_TICKET, r.nextInt(10, 26))) to RARE
        at += 1.0 / 6
        if (roll < at) return when (r.nextInt(3)) {
            0 -> Item(HOLIDAY[r.nextInt(HOLIDAY.size)])
            1 -> Item(GRACEFUL[r.nextInt(GRACEFUL.size)])
            else -> Item(PET_BOX_PETS[r.nextInt(PET_BOX_PETS.size)])
        } to UNCOMMON
        return when (r.nextInt(3)) {
            0 -> Item(BloodTokens.TOKEN, r.nextInt(25, 151))
            1 -> Item(COSTUMES[r.nextInt(COSTUMES.size)])
            else -> Item(CustomItemId.VOTE_TICKET, r.nextInt(1, 4))
        } to COMMON
    }

    override fun getItems(): IntArray = intArrayOf(CustomItemId.VOTE_MYSTERY_BOX)

    private companion object {
        const val COMMON = 0
        const val UNCOMMON = 1
        const val RARE = 2
        const val VERY_RARE = 3

        val VOTE_PETS = intArrayOf(CustomItemId.PET_LIL_MITHRIL, CustomItemId.PET_FROSTBITE, CustomItemId.PET_BEASTIE,
            CustomItemId.PET_ABBY, CustomItemId.PET_RUNEY)
        val VOTERS_OUTFIT = intArrayOf(CustomItemId.VOTERS_CROWN, CustomItemId.VOTERS_SCEPTRE, CustomItemId.VOTERS_CAPE)

        /** The pet mystery box's pets (PetMysteryBox: MiscPet Spirit kalphite to Chameleon). */
        val PET_BOX_PETS: IntArray = MiscPet.VALUES
            .filter { it.ordinal >= MiscPet.SPIRIT_KALPHITE.ordinal && it.ordinal <= MiscPet.CHAMELEON_10.ordinal }
            .map { it.itemId }.distinct().toIntArray()

        /** The vote shop's costumes. */
        val COSTUMES = intArrayOf(3057, 3058, 3059, 3060, 3061, 7594, 7592, 7593, 7595, 7596, 6656, 6654, 6655, 6184, 6185,
            6186, 6187, 22689, 22692, 22695, 22698, 22701, 11919, 12956, 12957, 12958, 12959, 6182, 6180, 6181, 9945, 9944,
            8950, 2631, 12432, 12353, 12361, 12428, 6188, 13655, 11015, 11017, 11018, 11016, 13283, 13284, 13285, 13287,
            13286, 20008, 25840, 19991, 12397, 12393, 12395, 12439, 23407, 23410, 20439, 20436, 20442, 20433, 4566, 4079, 20059)

        /** The vote shop's holiday-style outfits. */
        val HOLIDAY = intArrayOf(21841, 21842, 21843, 21844, 21845, 21846, 13663, 13664, 13665, 13182, 9925, 9924, 9923,
            9922, 9921, 24300, 24301, 24302, 24303, 24304, 7927, 20832, 22316, 12887, 12888, 12889, 12890, 12891, 12892,
            12893, 12894, 12895, 12896, 24325, 24327, 10507, 21214, 23448, 20836, 9920, 6722, 20266, 20269, 12845, 1037,
            13343, 13344, 1050, 1419)

        /** The vote shop's graceful recolours. */
        val GRACEFUL = intArrayOf(13579, 13581, 13583, 13585, 13587, 13589, 13591, 13593, 13595, 13597, 13599, 13601,
            13603, 13605, 13607, 13609, 13611, 13613, 13615, 13617, 13619, 13621, 13623, 13625, 13627, 13629, 13631, 13633,
            13635, 13637, 13667, 13669, 13671, 13673, 13675, 13677, 21061, 21064, 21067, 21070, 21073, 21076, 24743, 24746,
            24749, 24752, 24755, 24758, 25069, 25072, 25075, 25078, 25081, 25084)
    }
}
