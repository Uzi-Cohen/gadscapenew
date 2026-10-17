package com.gadscape.plugins.spawns

import com.gadscape.scripts.npc.spawns.NPCSpawnsScript
import com.zenyte.game.world.entity.npc.NpcId
import com.zenyte.game.world.entity.npc.NpcId.*
import com.zenyte.game.util.Direction.*

class Region12186Spawns : NPCSpawnsScript() {
    init {
        HELLRAT_BEHEMOTH(3069, 9895, 0, SOUTH, 0)
        HELLRAT_BEHEMOTH(3070, 9881, 0, SOUTH, 0)
    }
}