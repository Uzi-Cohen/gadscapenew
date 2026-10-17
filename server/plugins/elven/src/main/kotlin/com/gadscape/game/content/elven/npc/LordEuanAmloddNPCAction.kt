package com.gadscape.game.content.elven.npc

import com.gadscape.game.content.elven.npc.dialogue.LordIeuanAmloddDialogue
import com.gadscape.scripts.npc.actions.NPCActionScript
import com.zenyte.game.world.entity.npc.NpcId

class LordEuanAmloddNPCAction : NPCActionScript() {
    init {
        npcs(NpcId.LORD_IEUAN_AMLODD_9119)

        "Talk-To" {
            player.dialogueManager.start(LordIeuanAmloddDialogue(player, npc))
        }
    }
}
