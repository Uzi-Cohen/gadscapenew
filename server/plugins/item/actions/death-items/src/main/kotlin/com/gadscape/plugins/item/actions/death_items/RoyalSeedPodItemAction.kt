package com.gadscape.plugins.item.actions.death_items

import com.gadscape.plugins.item.actions.ItemActionPlugin
import com.zenyte.game.model.item.pluginextensions.ItemDeathStatus
import com.zenyte.game.item.ItemId.*

/**
 * @author Kris | 11/06/2022
 */
class RoyalSeedPodItemAction : ItemActionPlugin() {
    init {
        items(ROYAL_SEED_POD)

        death {
            kept {
                yield(item)
            }
            status {
                ItemDeathStatus.KEEP_ON_DEATH
            }
        }
    }
}