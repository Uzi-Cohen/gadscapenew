package com.gadscape.plugins.spawns

import com.gadscape.scripts.npc.spawns.NPCSpawnsScript
import com.zenyte.game.world.entity.npc.NpcId
import com.zenyte.game.world.entity.npc.NpcId.*
import com.zenyte.game.util.Direction.*

class Region8010Spawns : NPCSpawnsScript() {
    init {
        STRANGE_WATCHER_334(2006, 4755, 0, SOUTH, 0)
        STRANGE_WATCHER_333(2012, 4754, 0, SOUTH, 0)
        STRANGE_WATCHER(2015, 4756, 0, SOUTH, 0)
        MIME(2011, 4762, 0, SOUTH, 0)
    }
}