package com.gadscape.plugins.item.actions.death_items

import com.gadscape.plugins.item.actions.ItemActionPlugin
import com.zenyte.game.item.ItemId
import com.zenyte.game.model.item.pluginextensions.ItemDeathStatus

/**
 * @author Kris | 12/06/2022
 */
class HerbSackItemAction : ItemActionPlugin() {
    init {
        items(ItemId.HERB_SACK, ItemId.OPEN_HERB_SACK)

        death {
            kept {
                yield(item)
            }
            status { ItemDeathStatus.KEEP_ON_DEATH }
        }
    }
}
