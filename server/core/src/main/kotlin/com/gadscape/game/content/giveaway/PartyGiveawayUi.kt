package com.gadscape.game.content.giveaway

/**
 * Interface and component ids of the staff drop party chest and the giveaway screens, as the cache patch makes them
 * (editor utils.PartyGiveawayPatch; `java -cp <editor jar> utils.PartyGiveawayPatch --server-ids` prints them).
 * A layout change in the patch must change these too.
 */
@Suppress("unused")
object PartyGiveawayUi {

    /** 5240: interface 265 copied, plus the Party button. */
    object DropPartyChest {
        const val ID = 5240
        const val ONE = 7
        const val FIVE = 8
        const val X = 9
        const val ALL = 10
        const val CHEST = 11
        const val OFFER = 16
        const val CONFIRM = 17
        const val PARTY = 19
    }

    /** 5241: YouTuber Giveaway. */
    object Giveaway {
        const val ID = 5241
        const val PRIZE_SLOTS = 15
        /** Prize tile i: its layer is TILE_FIRST + i * TILE_STRIDE, then the 9 frame parts, the item, the amount text. */
        const val TILE_FIRST = 6
        const val TILE_STRIDE = 12
        const val TILE_FRAME_OFFSET = 1
        const val TILE_ITEM_OFFSET = 10
        const val TILE_AMOUNT_OFFSET = 11
        const val PREV = 186
        const val PAGE = 187
        const val NEXT = 188
        const val WINNER_BOX = 190
        const val WINNER_NAME = 193
        const val WINNER_STATUS = 194
        const val PRIZE_ITEM = 197
        const val PRIZE_NAME = 198
        const val PRIZE_INFO = 199
        const val GIVE = 200
        val RECENT = intArrayOf(212, 213, 214)

        fun tile(slot: Int) = TILE_FIRST + slot * TILE_STRIDE
    }

    /** 5242: Confirm giveaway. */
    object GiveawayConfirm {
        const val ID = 5242
        const val QUESTION = 2
        const val ITEM = 4
        const val ITEM_NAME = 5
        const val CONFIRM = 7
        const val CANCEL = 18
    }

    /** The vanilla nine-part button frame: normal and selected (red), in the patch's part order. */
    object Sprites {
        val BUTTON = intArrayOf(1141, 1142, 1143, 1144, 1145, 1146, 1147, 1148, 1149)
        val BUTTON_SELECTED = intArrayOf(1150, 1151, 1152, 1153, 1154, 1155, 1156, 1157, 1158)
    }
}
