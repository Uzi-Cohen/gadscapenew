package com.gadscape.plugins.spawns.custom

import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.region.area.RegisterIslandArea

import com.gadscape.scripts.npc.spawns.NPCSpawnsScript
import com.zenyte.game.world.entity.npc.NpcId
import com.zenyte.game.world.entity.npc.NpcId.*
import com.zenyte.game.util.Direction.*

class StarterGuideSpawns : NPCSpawnsScript() {
    init {
        val loc: Location = RegisterIslandArea.ZENYTE_GUIDE_LOCATION
        
        GADSCAPE_GUIDE(loc.x, loc.y, loc.plane)

        // Super skill island (::ssi, Super Donator and above): fishing spots around the dock (region 12334).
        // Kept in this already-registered spawns class so the deploy needs no plugins.dat update.
        1530(3101, 2955) // net / bait: shrimp, anchovies, sardine, herring
        1519(3106, 2955) // cage / harpoon: lobster, tuna, swordfish
        1520(3101, 2952) // big net / harpoon: sharks
        4316(3106, 2952) // monkfish
        6825(3101, 2949) // anglerfish
        4712(3106, 2949) // karambwan
        1536(3104, 2946) // dark crab
    }
}