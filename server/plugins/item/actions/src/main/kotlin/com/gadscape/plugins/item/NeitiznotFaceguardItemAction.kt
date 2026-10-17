package com.gadscape.plugins.item

import com.gadscape.scripts.item.actions.ItemActionScript
import com.zenyte.game.item.ItemId

class NeitiznotFaceguardItemAction : ItemActionScript() {
    init {
        items(ItemId.NEITIZNOT_FACEGUARD)

        "Dismantle" {
            player.dialogueManager.start(NeitiznotFaceguardDismantleDialogue(player))
        }
    }
}
