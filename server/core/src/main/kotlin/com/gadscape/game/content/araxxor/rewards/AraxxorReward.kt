package com.gadscape.game.content.araxxor.rewards

import com.zenyte.game.item.Item


/**
 * @author Glabay | Glabay-Studios
 * @project gadscape-server
 * @social Discord: Glabay
 * @since 2024-10-29
 */
interface AraxxorReward {
    fun rollForItem(): Item?
}