package com.gadscape.scripts.npc.drops.table.chance.immutable

import com.gadscape.scripts.npc.drops.table.chance.RollNothingChance

class StaticRollNothingChance(override val rarity: Int = 128)
    : StaticRollChance, RollNothingChance
