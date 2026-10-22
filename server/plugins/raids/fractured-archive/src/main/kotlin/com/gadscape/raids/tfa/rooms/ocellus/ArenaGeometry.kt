package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import kotlin.math.abs
import kotlin.math.max

/**
 * The Ocellus arena in static cache coordinates (spec §4.8 "Arena"; `raids4/ocellus/arena_map.txt`, terrain of
 * region 16472 on cache plane 1) [CACHE]:
 * - an octagon of 481 tiles, x 4115-4139, y 5661-5685, centre (4127,5673) = the hanging Blade loc 62706;
 *   rows are 9, 11, ... 23 wide from y 5661 to 5668, 25 wide for y 5669-5677, then back down;
 * - the cross: x 4126-4128 (N-S arm) and y 5672-5674 (E-W arm), 141 tiles;
 * - four 85-tile quadrants: 11 x 11 squares clipped by the octagon's diagonal walls.
 *
 * Pure geometry: no world access, unit tested in `ArenaGeometryTest`. [plane] is the static plane players stand
 * on (cache plane 1 plus `ocellus.json planeShift`).
 */
class ArenaGeometry(val plane: Int = ARENA_PLANE) {

    fun tile(x: Int, y: Int): StaticTile = StaticTile(x, y, plane)

    val centre: StaticTile get() = tile(CX, CY)

    /** Every walkable arena tile (481). */
    val tiles: List<StaticTile> by lazy {
        (MIN_Y..MAX_Y).flatMap { y -> (MIN_X..MAX_X).filter { x -> inOctagon(x, y) }.map { x -> tile(x, y) } }
    }

    fun quadrantTiles(q: Quadrant): List<StaticTile> = tiles.filter { q.contains(it.x, it.y) }

    fun crossTiles(): List<StaticTile> = tiles.filter { onCross(it.x, it.y) }

    fun contains(t: StaticTile): Boolean = t.plane == plane && inOctagon(t.x, t.y)

    fun quadrantOf(t: StaticTile): Quadrant? = if (t.plane != plane) null else Quadrant.of(t.x, t.y)

    /**
     * The tendril line of [dir] for an NPC of [size] with SW tile ([swX],[swY]): the tiles from the one just past
     * its footprint (centre + dir × (size/2 + 1)) outward to the arena edge (spec §4.8 P1 "drawn from the arena
     * edge to his footprint"). Empty if the first tile is already outside the octagon.
     */
    fun tendrilLine(swX: Int, swY: Int, size: Int, dir: Compass): List<StaticTile> {
        val cx = swX + size / 2
        val cy = swY + size / 2
        val out = ArrayList<StaticTile>()
        var step = size / 2 + 1
        while (true) {
            val x = cx + dir.dx * step
            val y = cy + dir.dy * step
            if (!inOctagon(x, y)) break
            out += tile(x, y)
            step++
        }
        return out
    }

    /** Tiles within Chebyshev [radius] of the [size] footprint at ([swX],[swY]), footprint excluded, inside the arena. */
    fun ringAround(swX: Int, swY: Int, size: Int, radius: Int): List<StaticTile> =
        tiles.filter { val d = distanceToFootprint(it.x, it.y, swX, swY, size); d in 1..radius }

    companion object {
        /** Cache plane of the arena floor and every gameplay loc of region 16472 [CACHE]. */
        const val ARENA_PLANE = 1
        const val CX = 4127
        const val CY = 5673
        const val MIN_X = 4115
        const val MAX_X = 4139
        const val MIN_Y = 5661
        const val MAX_Y = 5685
        /** Half the side of the octagon's square (25 x 25). */
        const val RADIUS = 12

        /** Half-width of octagon row [y] (0 = only the centre column), or -1 outside. */
        @JvmStatic
        fun halfWidth(y: Int): Int {
            val dy = abs(y - CY)
            if (dy > RADIUS) return -1
            return RADIUS - max(0, dy - 4)
        }

        @JvmStatic
        fun inOctagon(x: Int, y: Int): Boolean {
            val hw = halfWidth(y)
            return hw >= 0 && abs(x - CX) <= hw
        }

        @JvmStatic
        fun onCross(x: Int, y: Int): Boolean = inOctagon(x, y) && (abs(x - CX) <= 1 || abs(y - CY) <= 1)

        /** Rotates a tile a quarter turn clockwise about the centre: SW → NW → NE → SE (the quadrants' order). */
        @JvmStatic
        fun rotateCw(t: StaticTile, quarterTurns: Int = 1): StaticTile {
            var x = t.x
            var y = t.y
            repeat(((quarterTurns % 4) + 4) % 4) {
                val nx = CX + (y - CY)
                val ny = CY - (x - CX)
                x = nx
                y = ny
            }
            return StaticTile(x, y, t.plane)
        }

        /** Chebyshev distance from ([x],[y]) to the [size] footprint at ([swX],[swY]); 0 inside it. */
        @JvmStatic
        fun distanceToFootprint(x: Int, y: Int, swX: Int, swY: Int, size: Int): Int {
            val dx = when {
                x < swX -> swX - x
                x > swX + size - 1 -> x - (swX + size - 1)
                else -> 0
            }
            val dy = when {
                y < swY -> swY - y
                y > swY + size - 1 -> y - (swY + size - 1)
                else -> 0
            }
            return max(dx, dy)
        }

        /** The eight tendril directions clockwise from north (the centre-sequence order, spec §4.8 P1 step 2). */
        @JvmField
        val CLOCKWISE: List<Compass> = listOf(Compass.N, Compass.NE, Compass.E, Compass.SE, Compass.S, Compass.SW, Compass.W, Compass.NW)

        /** Lower-case config key of a direction (`ocellus.json p1.crackRotations`). */
        @JvmStatic
        fun key(dir: Compass): String = dir.name.lowercase()
    }
}

/**
 * The four quadrants in clockwise order SW → NW → NE → SE (spec §4.8: P1 anchors and P2 barrages run in this
 * order). [sx] / [sy] are the signs of the quadrant's x / y offsets from the centre.
 */
enum class Quadrant(val sx: Int, val sy: Int) {
    SW(-1, -1), NW(-1, 1), NE(1, 1), SE(1, -1);

    /** The next quadrant clockwise. */
    val next: Quadrant get() = entries[(ordinal + 1) % entries.size]

    /** Tile in this quadrant: inside the octagon and at least 2 tiles off both centre lines (off the 3-wide cross). */
    fun contains(x: Int, y: Int): Boolean =
        ArenaGeometry.inOctagon(x, y) && sx * (x - ArenaGeometry.CX) >= 2 && sy * (y - ArenaGeometry.CY) >= 2

    companion object {
        @JvmStatic
        fun of(x: Int, y: Int): Quadrant? = entries.firstOrNull { it.contains(x, y) }

        @JvmStatic
        fun byName(name: String): Quadrant = valueOf(name.trim().uppercase())
    }
}
