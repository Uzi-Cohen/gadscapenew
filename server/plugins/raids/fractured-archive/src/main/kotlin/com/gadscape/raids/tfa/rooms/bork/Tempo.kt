package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.rooms.bork.BorkSmorksConfig.TempoTier

/**
 * The drum tempo (spec §4.3): "as their health lowers, Bork will begin to beat his drum faster, subsequently causing
 * both enemies' attack speed to increase" [DOC], thresholds "independent per NPC" [COMM]. Each NPC reads its own HP.
 *
 * | Own HP | Speed | Bork's daze |
 * |---|---|---|
 * | > 66% | 7 | 14 t (seq 14545 + spot 4041) |
 * | 33-66% | 6 | 12 t (14544 + 4040) |
 * | < 33% | 4 | 8 t (14543 + 4038) |
 *
 * Pure; unit tested.
 */
object Tempo {

    /** Index of the tier [hp]/[maxHp] falls in: the first tier whose `abovePct` the HP % is strictly above. */
    @JvmStatic
    fun tierIndex(tiers: List<TempoTier>, hp: Int, maxHp: Int): Int {
        if (tiers.isEmpty()) return 0
        if (maxHp <= 0) return tiers.lastIndex
        val pct = hp * 100.0 / maxHp
        for ((i, t) in tiers.withIndex()) if (pct > t.abovePct) return i
        return tiers.lastIndex
    }

    /** The attack speed (ticks between attacks) at [hp]/[maxHp]. */
    @JvmStatic
    fun speed(tiers: List<TempoTier>, hp: Int, maxHp: Int): Int =
        if (tiers.isEmpty()) DEFAULT_SPEED else tiers[tierIndex(tiers, hp, maxHp)].speed

    /** Daze length at [speed]: [perSpeed] × speed (7 → 14, 6 → 12, 4 → 8 with the spec's 2). */
    @JvmStatic
    fun dazeTicks(speed: Int, perSpeed: Int): Int = (speed * perSpeed).coerceAtLeast(1)

    /** CACHE npc param 14 (attackrate) of both NPCs. */
    const val DEFAULT_SPEED = 7
}
