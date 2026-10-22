package com.gadscape.raids.tfa.rooms.moia

import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random

/**
 * Where Moia stands (spec §4.4 arena table). The four arms are 2-tile-deep platforms outside the moat, reachable only
 * by teleport; each SW tile is 3 tiles outside the graveyard fence, so melee and halberds cannot reach her in
 * phase 1. [CENTRE] is the enrage position inside the graveyard. Tiles come from `moia.json arms`.
 */
enum class MoiaArm(
    /** Key in `moia.json arms`. */
    val key: String,
    /** The direction she faces from this arm (towards the graveyard). */
    val facing: Compass,
) {
    W("W", Compass.E),
    E("E", Compass.W),
    S("S", Compass.N),
    N("N", Compass.S),
    CENTRE("centre", Compass.S);

    val isOuter: Boolean get() = this != CENTRE

    companion object {
        val OUTER: List<MoiaArm> = listOf(W, E, S, N)

        /** A random outer arm other than [current] (the teleport special never stays put). */
        @JvmStatic
        fun randomOther(current: MoiaArm, random: Random): MoiaArm {
            val options = OUTER.filter { it != current }
            return options[random.nextInt(options.size)]
        }
    }
}

/**
 * Static geometry of region 16216 (spec §4.4 Arena [CACHE map]; `raids4/moia/arena_grid.txt`).
 */
object MoiaGeometry {

    /** Moia is 3x3 (cache 16667 size 3). */
    const val MOIA_SIZE = 3

    /** The 9x9 walkable graveyard (81 tiles by flood fill), fenced, with a 2-tile terrain moat around it. */
    const val FLOOR_MIN_X = 4059
    const val FLOOR_MAX_X = 4067
    const val FLOOR_MIN_Y = 5659
    const val FLOOR_MAX_Y = 5667
    const val PLANE = 0

    /** Centre tile of the graveyard (the middle grave cell). */
    val CENTRE: StaticTile = StaticTile(4063, 5663)

    fun onFloor(x: Int, y: Int): Boolean = x in FLOOR_MIN_X..FLOOR_MAX_X && y in FLOOR_MIN_Y..FLOOR_MAX_Y

    fun onFloor(tile: StaticTile): Boolean = tile.plane == PLANE && onFloor(tile.x, tile.y)

    /** Chebyshev distance between two 1x1 tiles. */
    fun chebyshev(a: StaticTile, b: StaticTile): Int = maxOf(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))

    /** Chebyshev distance from a [size]x[size] footprint at SW [sw] to the 1x1 tile [t] (0 when inside). */
    fun footprintDistance(sw: StaticTile, size: Int, t: StaticTile): Int = footprintDistance(sw.x, sw.y, size, t.x, t.y)

    fun footprintDistance(swX: Int, swY: Int, size: Int, x: Int, y: Int): Int {
        val dx = maxOf(0, maxOf(swX - x, x - (swX + size - 1)))
        val dy = maxOf(0, maxOf(swY - y, y - (swY + size - 1)))
        return maxOf(dx, dy)
    }
}
