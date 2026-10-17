package com.gadscape.scripts.npc.drops.table.dsl

import com.gadscape.scripts.npc.drops.table.DropQuantity
import com.gadscape.scripts.npc.drops.table.chance.immutable.StaticRollItemChance

/**
 * Represents a temporary state only used by the DSL for building a [StaticRollItemChance].
 *
 * @param id        the id[Int] to set [StaticRollItemChance.id] as.
 * @param quantity  the quantity[DropQuantity] to set [StaticRollItemChance.quantity] as.
 *
 * @author Stan van der Bend
 */
class PartialRollItemChance(internal val id: Int, internal val quantity: DropQuantity)
