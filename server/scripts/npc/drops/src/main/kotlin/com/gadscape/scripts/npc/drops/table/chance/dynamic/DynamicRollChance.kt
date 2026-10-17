package com.gadscape.scripts.npc.drops.table.chance.dynamic

import com.gadscape.scripts.npc.drops.table.chance.RollChance
import com.zenyte.game.world.entity.player.Player

interface DynamicRollChance : RollChance {

    val rarityProvider: (Player) -> Int
}
