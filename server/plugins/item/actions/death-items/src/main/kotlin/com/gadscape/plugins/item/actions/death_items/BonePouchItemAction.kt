package com.gadscape.plugins.item.actions.death_items

import com.gadscape.scripts.item.actions.ItemActionScript
import com.zenyte.game.model.item.pluginextensions.ItemDeathStatus

class BonePouchItemAction : ItemActionScript() {
    init {
        items(26306, 26304)

        death {
            kept {
                yield(item)
            }
            status { ItemDeathStatus.KEEP_ON_DEATH }
        }
    }
}
