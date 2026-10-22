package com.gadscape.raids.tfa.death

import com.zenyte.game.content.gravestone.GravestoneExt
import com.zenyte.game.world.entity.player.Player

/**
 * The TFA gravestone fee reduction (script 1797: `fee = scale(1,4,fee)` in the death zone below 5 KC [CACHE]).
 *
 * GadScape's gravestone fee is computed when the player pays (`GravestoneExt.getRetrievalServiceCache`), so the
 * reduction is stored on the player as the persistent attribute [ATTRIBUTE] (percent of the fee taken off; absent or
 * 0 = full fee). `GravestoneExt` applies it and clears it whenever a new gravestone is made, so it only ever covers the
 * gravestone of the TFA wipe that set it.
 */
object GravestoneFee {

    /** Persistent player attribute read by core `GravestoneExt`: percent of the fee taken off (0-100). */
    const val ATTRIBUTE = GravestoneExt.FEE_DISCOUNT_ATTRIBUTE

    /** Records that the current gravestone of [player] costs [feePercent] % of the normal fee. */
    @JvmStatic
    fun set(player: Player, feePercent: Int) {
        val discount = (100 - feePercent).coerceIn(0, 100)
        if (discount == 0) player.attributes.remove(ATTRIBUTE) else player.addAttribute(ATTRIBUTE, discount)
    }

    @JvmStatic
    fun discountOf(player: Player): Int = (player.attributes[ATTRIBUTE] as? Number)?.toInt()?.coerceIn(0, 100) ?: 0
}
