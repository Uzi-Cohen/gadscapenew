package com.gadscape.raids.tfa.death

import com.gadscape.raids.tfa.raid.StaticTile

/**
 * The Fractured Archive death zone, a copy of proc 10182 [CACHE], and the gravestone fee rule of script 1797
 * (`if (~script10182(%gravestone_creation_death_coord) & %total_completed_thefracturedarchive < 5) fee = scale(1,4,fee)`),
 * spec §3.8:
 * - map squares (64,87) nexus, (63,86) / (63,87) / (63,88) west rooms, (65,86) / (65,87) / (65,88) east rooms, all planes;
 * - plus `0_64_87_0_56 .. 2_64_88_63_63`: x 4096-4159, y 5624-5695 on planes 0-2 (north nexus and the Ocellus arena).
 * The lobby (64,86) and the vault (64,90) are outside it. Coordinates are static: test `TfaInstance.toStatic(tile)`.
 *
 * Pure: unit-tested in `DeathFeeZoneTest`.
 */
object DeathFeeZone {

    private val SQUARES = setOf(64 to 87, 63 to 86, 63 to 87, 63 to 88, 65 to 86, 65 to 87, 65 to 88)

    @JvmStatic
    fun contains(tile: StaticTile): Boolean {
        if ((tile.x shr 6) to (tile.y shr 6) in SQUARES) return true
        return tile.plane in 0..2 && tile.x in 4096..4159 && tile.y in 5624..5695
    }

    /**
     * Fee percentage for a death at [deathTile] (static, or null when not in the raid) by a player with [kc]
     * completions: [reducedPct] (25) in the zone below [kcThreshold] (5) completions, else 100.
     */
    @JvmStatic
    fun feePercent(deathTile: StaticTile?, kc: Int, kcThreshold: Int = 5, reducedPct: Int = 25): Int =
        if (deathTile != null && kc < kcThreshold && contains(deathTile)) reducedPct else 100
}
