package com.gadscape.game.content.elven.npc

import com.gadscape.game.content.elven.npc.dialogue.PrifddinasRandomDialogue
import com.gadscape.scripts.npc.actions.NPCActionScript
import com.zenyte.game.world.entity.npc.NpcId.*

class PrifddinasNPCAction : NPCActionScript() {
    init {
        npcs(
            FINDUILAS, MITHRELLAS, ENERDHIL, TATIE, IMINYE, CURUFIN,
            GELMIR, MAHTAN, FINGOLFIN, INDIS, ANAIRE, CELEBRIAN, OROPHER,
            MIRIEL, IDRIL
        )

        "Talk-To" {
            player.dialogueManager.start(PrifddinasRandomDialogue(player, npc))
        }
    }
}
