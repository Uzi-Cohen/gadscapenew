package com.gadscape.plugins.item.actions.death_items

import com.gadscape.plugins.item.actions.ItemActionPlugin
import com.zenyte.game.content.lootkeys.LootkeyConstants
import com.zenyte.game.model.item.pluginextensions.ItemDeathStatus
import com.zenyte.game.item.ItemId.*

/**
 * @author Kris | 12/06/2022
 *
 * Loot keys (all five ids, wildy_loot_key0..4) are never protected: they are always lost on death, PvP or PvM
 * (OSRS). LootkeyDeath then hands them to a player killer or destroys them after a PvM death.
 */
class LarransKeyItemAction : ItemActionPlugin() {
    init {
        items(LARRANS_KEY, *LootkeyConstants.LOOT_KEY_IDS)

        death {
            setAlwaysLostOnDeath()
            if (pvp || LootkeyConstants.isLootKey(item.id)) {
                lost { yield(item) }
                status { ItemDeathStatus.DROP_ON_DEATH }
            } else {
                kept { yield(item) }
                status { ItemDeathStatus.GO_TO_GRAVESTONE }
            }
        }
    }
}
