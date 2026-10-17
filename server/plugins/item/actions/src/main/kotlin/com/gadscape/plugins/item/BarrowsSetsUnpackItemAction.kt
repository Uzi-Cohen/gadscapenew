package com.gadscape.plugins.item

import com.gadscape.scripts.item.actions.ItemActionScript
import com.zenyte.game.content.grandexchange.ItemSets
import com.zenyte.game.item.ItemId

class BarrowsSetsUnpackItemAction : ItemActionScript() {
    init {
        items(
            ItemId.TORAGS_ARMOUR_SET,
            ItemId.DHAROKS_ARMOUR_SET,
            ItemId.KARILS_ARMOUR_SET,
            ItemId.AHRIMS_ARMOUR_SET,
            ItemId.GUTHANS_ARMOUR_SET,
            ItemId.VERACS_ARMOUR_SET
        )

        "Unpack" {
            ItemSets.unpack(player, this.item.id)
        }
    }
}
