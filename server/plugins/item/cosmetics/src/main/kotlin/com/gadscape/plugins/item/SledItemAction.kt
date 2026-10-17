package com.gadscape.plugins.item

import com.gadscape.scripts.item.actions.ItemActionScript
import com.zenyte.game.item.ItemId

class SledItemAction : ItemActionScript() {
    init {
        items(ItemId.SLED, ItemId.SLED_4084, ItemId.SLED_25282)

        "Ride" {
            player.equipment.wear(slotID)
        }
    }
}
