package com.gadscape.scripts.npc.drops.table.chance.immutable

import com.gadscape.scripts.npc.drops.table.DropQuantity
import com.gadscape.scripts.npc.drops.table.chance.RollAlways

class StaticRollItemAlways(id: Int, quantity: DropQuantity)
    : StaticRollItemChance(id, quantity, 0), RollAlways
