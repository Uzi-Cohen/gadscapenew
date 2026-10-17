package com.gadscape.game.content.elven.item

import com.gadscape.game.content.crystal.CrystalRecipe
import com.gadscape.game.content.crystal.recipes.CrystalChargeable
import com.gadscape.game.content.crystal.recipes.CrystalCorrupted
import com.gadscape.scripts.item.actions.ItemActionScript

class CrystalItemAction : ItemActionScript() {
    val idWearableMap: Map<Int, CrystalRecipe> =
        CrystalChargeable.all.associateBy { it.productItemId } +
                CrystalChargeable.all.associateBy { it.inactiveId } +
                CrystalCorrupted.all.associateBy { it.productItemId }

    init {
        items(idWearableMap.keys)

        "Revert" {
            player.dialogueManager.start(RevertCrystalItemDialogue(player, slotID, item, idWearableMap[item.id]!!))
        }

        "Uncharge" {
            player.dialogueManager.start(RevertCrystalItemDialogue(player, slotID, item, idWearableMap[item.id]!!))
        }
    }
}
