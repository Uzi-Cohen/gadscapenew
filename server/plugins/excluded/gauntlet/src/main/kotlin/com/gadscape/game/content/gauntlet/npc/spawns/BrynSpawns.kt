package com.gadscape.game.content.gauntlet.npc.spawns

import com.gadscape.scripts.npc.spawns.NPCSpawnsScript
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.npc.NpcId

class BrynSpawns : NPCSpawnsScript() {
    init {
        NpcId.BRYN(3030, 6128, 1, Direction.SOUTH, 5)
    }
}
