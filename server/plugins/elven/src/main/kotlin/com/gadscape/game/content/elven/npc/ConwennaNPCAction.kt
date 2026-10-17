package com.gadscape.game.content.elven.npc

import com.gadscape.game.content.elven.npc.dialogue.ConwennaDialogue
import com.gadscape.scripts.npc.actions.NPCActionScript

class ConwennaNPCAction : NPCActionScript() {

    init {
        npcs(9240)

        "Talk-To" {
            player.dialogueManager.start(ConwennaDialogue(player, npc))
        }
    }

}
