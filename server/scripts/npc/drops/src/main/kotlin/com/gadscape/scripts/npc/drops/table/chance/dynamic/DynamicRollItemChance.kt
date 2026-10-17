package com.gadscape.scripts.npc.drops.table.chance.dynamic

import com.gadscape.scripts.npc.drops.table.DropQuantity
import com.gadscape.scripts.npc.drops.table.chance.RollItemChance
import com.zenyte.game.world.entity.player.Player

class DynamicRollItemChance(
    id: Int,
    quantity: DropQuantity,
    override val rarityProvider: (Player) -> Int
) : RollItemChance(id, quantity), DynamicRollChance
