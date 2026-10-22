package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random

/**
 * Pure geometry of the tome effects (spec §4.6 "Tome effects", NOTES §7.4), shared by [DagonhaiFight] and its tests.
 *
 * - **Bandos shockwave**: rings of `width` tiles moving out one ring per tick from the blocker (or the cauldron's
 *   footprint): ring k (1-based) covers Chebyshev distances `(k-1)·width+1 .. k·width` (1-2, 3-4, 5-6, 7-8; spots
 *   4156-4159). A player is hit by every ring that reaches the distance they stand at on that tick, so a runner moving
 *   outwards with the wave is hit again (COMM).
 * - **Zamorak fireballs**: every hazard tile of the square of radius `zamRadius` (7x7) around the blocker gets a fuse
 *   spot 4145-4148 (3 / 4 / 5 / 6 t) and blows up (4149) when its fuse ends.
 * - **Saradomin storm**: `saraTiles` heal tiles and as many damage tiles, distinct, "around the arena" (COMM).
 */
object GodEffects {

    /** Distances ring [k] (1-based) of a shockwave covers. */
    fun ringRange(k: Int, width: Int): IntRange = ((k - 1) * width + 1)..(k * width)

    /**
     * The fighters ring [k] hits: alive, not [exclude] (the blocker is never hit by its own wave), at a footprint
     * distance from ([sw], [size]) inside the ring.
     */
    fun ringVictims(sw: StaticTile, size: Int, k: Int, width: Int, fighters: List<Fighter>, exclude: Fighter?): List<Fighter> {
        val range = ringRange(k, width)
        return fighters.filter { it.alive && it !== exclude && DagonhaiArena.footprintDistance(sw, size, it.tile) in range }
    }

    /**
     * The fireball field around [centre]: each hazard tile within [radius] with its fuse index (0..3 = spots
     * 4145..4148). [layout]: "random" (default), "rings_out" (the centre first) or "rings_in" (the edge first).
     */
    fun zamorakField(centre: StaticTile, radius: Int, layout: String, fuses: Int, random: Random): List<Pair<StaticTile, Int>> {
        val last = fuses - 1
        return DagonhaiArena.hazardTilesAround(centre, radius).map { tile ->
            val ring = DagonhaiArena.chebyshev(centre, tile)
            val index = when (layout) {
                "rings_out" -> minOf(ring, last)
                "rings_in" -> last - minOf(ring, last)
                else -> random.nextInt(fuses)
            }
            tile to index
        }
    }

    /** Heal tiles and damage tiles of a storm, [count] each, distinct, within [radius] of [centre]. */
    fun stormTiles(centre: StaticTile, radius: Int, count: Int, random: Random): Pair<List<StaticTile>, List<StaticTile>> {
        val picks = DagonhaiArena.pickTiles(DagonhaiArena.hazardTilesAround(centre, radius), count * 2, random)
        val heal = picks.take(minOf(count, picks.size))
        val damage = picks.drop(heal.size)
        return heal to damage
    }
}
