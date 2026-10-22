package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.raid.StaticTile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * Static geometry of the Dreadnoth arena (region 16728, cache plane 0), in static coordinates.
 *
 * Evidence [CACHE map 16728, `raids4/dreadnoth/arena_map.txt`]: an open 15x15 box x 4184-4198, y 5656-5670 whose four
 * corner tiles are blocked (221 walkable tiles), sealed by loc 85 and walls, no cover inside. Octagram decal 63112 at
 * the centre (4191,5663). Only plane 0 is used.
 */
object DreadnothArena {

    const val MIN_X = 4184
    const val MAX_X = 4198
    const val MIN_Y = 5656
    const val MAX_Y = 5670
    const val PLANE = 0

    /** Dreadnoth 16665 is size 5 [CACHE]. */
    const val BOSS_SIZE = 5

    @JvmField
    val CENTRE = StaticTile(4191, 5663, PLANE)

    /** The four blocked corner tiles of the box. */
    @JvmField
    val CORNERS: Set<StaticTile> = setOf(
        StaticTile(MIN_X, MIN_Y, PLANE), StaticTile(MAX_X, MIN_Y, PLANE),
        StaticTile(MIN_X, MAX_Y, PLANE), StaticTile(MAX_X, MAX_Y, PLANE),
    )

    /** Every walkable arena tile, row by row from the south-west (221 tiles). */
    @JvmField
    val WALKABLE: List<StaticTile> = buildList {
        for (y in MIN_Y..MAX_Y) for (x in MIN_X..MAX_X) {
            val t = StaticTile(x, y, PLANE)
            if (t !in CORNERS) add(t)
        }
    }

    /** Inside the 15x15 box (corners included), on the arena plane. */
    fun inBox(tile: StaticTile): Boolean =
        tile.plane == PLANE && tile.x in MIN_X..MAX_X && tile.y in MIN_Y..MAX_Y

    /** A tile a player or a size-1 NPC can stand on. */
    fun isWalkable(tile: StaticTile): Boolean = inBox(tile) && tile !in CORNERS

    // ------------------------------------------------------------------------------------------- footprint

    fun inFootprint(sw: StaticTile, tile: StaticTile, size: Int = BOSS_SIZE): Boolean =
        tile.plane == sw.plane && tile.x in sw.x until sw.x + size && tile.y in sw.y until sw.y + size

    /** Chebyshev distance from [tile] to the nearest footprint tile; 0 under the boss. */
    fun distanceToFootprint(sw: StaticTile, tile: StaticTile, size: Int = BOSS_SIZE): Int {
        val dx = when {
            tile.x < sw.x -> sw.x - tile.x
            tile.x > sw.x + size - 1 -> tile.x - (sw.x + size - 1)
            else -> 0
        }
        val dy = when {
            tile.y < sw.y -> sw.y - tile.y
            tile.y > sw.y + size - 1 -> tile.y - (sw.y + size - 1)
            else -> 0
        }
        return max(dx, dy)
    }

    /**
     * The AoE melee area: every tile within Chebyshev [reach] of the footprint, under him included (reach 1 = the 7x7
     * square around a 5x5) [INF, §4.5 attack table].
     */
    fun inMeleeZone(sw: StaticTile, tile: StaticTile, reach: Int = 1): Boolean =
        tile.plane == sw.plane && distanceToFootprint(sw, tile) <= reach

    /** The footprint centre tile (where a size-5 NPC's middle is). */
    fun centreOf(sw: StaticTile, size: Int = BOSS_SIZE): StaticTile = sw.offset(size / 2, size / 2)

    /** The footprint tile nearest to [tile] (the clamp of [tile] into the footprint). */
    fun nearestFootprintTile(sw: StaticTile, tile: StaticTile, size: Int = BOSS_SIZE): StaticTile =
        StaticTile(tile.x.coerceIn(sw.x, sw.x + size - 1), tile.y.coerceIn(sw.y, sw.y + size - 1), sw.plane)

    // ------------------------------------------------------------------------------------------- enrage jump

    /**
     * The landing area of the enrage jump: the 5x5 centred on [target], moved inside the 15x15 box (spec §4.5
     * "landing area L = 5x5 centred on P, clamped to the arena"). Returns its south-west tile.
     */
    fun landingArea(target: StaticTile, size: Int = BOSS_SIZE): StaticTile {
        val half = size / 2
        val x = (target.x - half).coerceIn(MIN_X, MAX_X - size + 1)
        val y = (target.y - half).coerceIn(MIN_Y, MAX_Y - size + 1)
        return StaticTile(x, y, PLANE)
    }

    // ------------------------------------------------------------------------------------------- movement

    /**
     * The one-tile step (dx, dy) a size-5 boss at [sw] takes toward [target] (naive NPC pathing: diagonal when the
     * target is off both axes of the footprint). (0, 0) when the target is already inside the footprint's rows and
     * columns.
     */
    fun stepToward(sw: StaticTile, target: StaticTile, size: Int = BOSS_SIZE): Pair<Int, Int> {
        val dx = when {
            target.x < sw.x -> -1
            target.x > sw.x + size - 1 -> 1
            else -> 0
        }
        val dy = when {
            target.y < sw.y -> -1
            target.y > sw.y + size - 1 -> 1
            else -> 0
        }
        return dx to dy
    }

    /** True if a size-[size] footprint at [sw] lies inside the box (corners may be covered; the box is open). */
    fun footprintInBox(sw: StaticTile, size: Int = BOSS_SIZE): Boolean =
        sw.plane == PLANE && sw.x >= MIN_X && sw.y >= MIN_Y && sw.x + size - 1 <= MAX_X && sw.y + size - 1 <= MAX_Y

    /** Chebyshev distance between two tiles. */
    fun chebyshev(a: StaticTile, b: StaticTile): Int = max(abs(a.x - b.x), abs(a.y - b.y))

    /** Client orientation (0-2047, 0 = south) for a move from [from] to [to], as `DirectionUtil.getFaceDirection`. */
    fun faceDirection(from: StaticTile, to: StaticTile): Int {
        val dx = (to.x - from.x).toDouble()
        val dy = (to.y - from.y).toDouble()
        if (dx == 0.0 && dy == 0.0) return 0
        return ((Math.atan2(-dx, -dy) * 325.949).toInt() and 0x7ff)
    }

    internal fun sgn(v: Int): Int = v.sign
}
