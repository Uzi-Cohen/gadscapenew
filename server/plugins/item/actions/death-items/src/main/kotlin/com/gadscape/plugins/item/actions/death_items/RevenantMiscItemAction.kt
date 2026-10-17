package com.gadscape.plugins.item.actions.death_items

import com.gadscape.plugins.item.actions.ItemActionPlugin
import com.zenyte.game.model.item.pluginextensions.ItemDeathStatus
import com.zenyte.game.world.region.area.wilderness.WildernessArea
import com.zenyte.game.item.ItemId.*

class RevenantMiscItemAction : ItemActionPlugin() {
    init {
        items(
            ANCIENT_EMBLEM, ANCIENT_TOTEM, ANCIENT_STATUETTE,
            ANCIENT_MEDALLION, ANCIENT_EFFIGY, ANCIENT_RELIC
        )

        death {
            setAlwaysLostOnDeath()
            if (pvp || WildernessArea.isWithinWilderness(player.x, player.y))
                lost { yield(item) }
            else
                kept { yield(item) }
            status { if (pvp) ItemDeathStatus.DROP_ON_DEATH else ItemDeathStatus.GO_TO_GRAVESTONE }
        }
    }
}
