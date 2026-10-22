package com.gadscape.raids.tfa.rooms.ezekiel

import com.zenyte.game.util.Direction

/** A tile in static cache coordinates (spec §0.3), plane implied (the Ezekiel floor is on plane 0). */
data class Tile(val x: Int, val y: Int) {
    fun offset(dx: Int, dy: Int) = Tile(x + dx, y + dy)
    override fun toString(): String = "($x,$y)"
}

/** An inclusive tile rectangle in static coordinates. */
data class Rect(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
    init {
        require(minX <= maxX && minY <= maxY) { "empty rect $minX,$minY..$maxX,$maxY" }
    }

    operator fun contains(t: Tile): Boolean = t.x in minX..maxX && t.y in minY..maxY
    fun contains(x: Int, y: Int): Boolean = x in minX..maxX && y in minY..maxY
    val width: Int get() = maxX - minX + 1
    val height: Int get() = maxY - minY + 1

    /** Every tile, row by row from the south-west. */
    fun tiles(): List<Tile> {
        val out = ArrayList<Tile>(width * height)
        for (y in minY..maxY) for (x in minX..maxX) out += Tile(x, y)
        return out
    }

    /** The intersection with [other], or null. */
    fun intersect(other: Rect): Rect? {
        val ax = maxOf(minX, other.minX)
        val ay = maxOf(minY, other.minY)
        val bx = minOf(maxX, other.maxX)
        val by = minOf(maxY, other.maxY)
        return if (ax > bx || ay > by) null else Rect(ax, ay, bx, by)
    }

    companion object {
        /** The square footprint of a [size]-tile entity whose south-west tile is ([x], [y]). */
        @JvmStatic
        fun footprint(x: Int, y: Int, size: Int) = Rect(x, y, x + size - 1, y + size - 1)
    }
}

/**
 * The Ezekiel arena (spec §4.2 "Arena", `raids4/ezekiel/arena_grid.txt`): a 9x9 floor at x 4059-4067, y 5531-5539
 * (static region 16214, plane 0), ringed by blocking corpses and a 17x17 `inviswall_blockrange`; players only reach
 * it through the transporter. The floor is a 3x3 grid of 3x3 cells: cell (cx, cy) has SW (4059 + 3cx, 5531 + 3cy).
 * Ezekiel and the Animated Swords are size 3, so a cell or a lane is exactly one of them wide.
 */
object EzekielArena {

    const val SIZE = 3
    const val FLOOR_MIN_X = 4059
    const val FLOOR_MIN_Y = 5531
    const val FLOOR_MAX_X = 4067
    const val FLOOR_MAX_Y = 5539

    /** The fight floor [CACHE map, flood fill from 4063,5535]. */
    @JvmField
    val FLOOR = Rect(FLOOR_MIN_X, FLOOR_MIN_Y, FLOOR_MAX_X, FLOOR_MAX_Y)

    /** Guthix emblem 62861 at the arena centre. */
    @JvmField
    val CENTRE = Tile(4063, 5535)

    /** Lane origins (x of vertical lanes, y of horizontal lanes) of the three cell columns / rows. */
    @JvmField
    val LANES_X = intArrayOf(4059, 4062, 4065)

    @JvmField
    val LANES_Y = intArrayOf(5531, 5534, 5537)

    /** SW tile of cell ([cx], [cy]) (0..2 each). */
    @JvmStatic
    fun cellSw(cx: Int, cy: Int): Tile = Tile(FLOOR_MIN_X + SIZE * cx, FLOOR_MIN_Y + SIZE * cy)

    @JvmStatic
    fun cell(cx: Int, cy: Int): Rect = Rect.footprint(FLOOR_MIN_X + SIZE * cx, FLOOR_MIN_Y + SIZE * cy, SIZE)

    /** The cell holding [t], or null when [t] is off the floor. */
    @JvmStatic
    fun cellOf(t: Tile): Pair<Int, Int>? =
        if (t !in FLOOR) null else Pair((t.x - FLOOR_MIN_X) / SIZE, (t.y - FLOOR_MIN_Y) / SIZE)

    /** Chebyshev distance between two tiles. */
    @JvmStatic
    fun chebyshev(a: Tile, b: Tile): Int = maxOf(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))

    /** True when [t] lies inside the [size]-tile footprint with SW [sw]. */
    @JvmStatic
    fun under(sw: Tile, size: Int, t: Tile): Boolean = t.x in sw.x until sw.x + size && t.y in sw.y until sw.y + size

    /** A client facing angle (0 S, 512 W, 1024 N, 1536 E; [Direction] values) for a step ([dx], [dy]). */
    @JvmStatic
    fun angleOf(dx: Int, dy: Int): Int = directionOf(dx, dy)?.direction ?: Direction.SOUTH.direction

    /** The compass [Direction] of a step, or null for (0, 0). */
    @JvmStatic
    fun directionOf(dx: Int, dy: Int): Direction? {
        val sx = Integer.signum(dx)
        val sy = Integer.signum(dy)
        return Direction.values.firstOrNull { it.offsetX == sx && it.offsetY == sy }
    }
}

/**
 * The melee cleave (spec §4.2 Cleave; wiki Strategies "cleave almost entirely around him, leaving a safespot directly
 * behind him, opposite to the tank"): every player on the 16-tile ring around his 3x3 (the 5x5 minus the 3x3) is hit,
 * except the 3 ring tiles opposite the tank. When the tank stands off his centre lines (diagonal from his centre), the
 * excluded set is the opposite corner block: the corner ring tile and its two ring neighbours.
 */
object Cleave {

    /** The 16 ring tiles around a size-3 entity at SW [sw]. */
    @JvmStatic
    fun ring(sw: Tile): List<Tile> {
        val out = ArrayList<Tile>(16)
        for (y in sw.y - 1..sw.y + EzekielArena.SIZE) for (x in sw.x - 1..sw.x + EzekielArena.SIZE) {
            if (x in sw.x until sw.x + EzekielArena.SIZE && y in sw.y until sw.y + EzekielArena.SIZE) continue
            out += Tile(x, y)
        }
        return out
    }

    /** The swing direction from his centre toward [tank]: each component -1, 0 or 1 (both 0 if under him). */
    @JvmStatic
    fun swingDirection(sw: Tile, tank: Tile): Pair<Int, Int> {
        val cx = sw.x + 1
        val cy = sw.y + 1
        return Pair(Integer.signum(tank.x - cx), Integer.signum(tank.y - cy))
    }

    /**
     * The 3 ring tiles the cleave spares for a swing in direction ([sx], [sy]) from SW [sw]: the middle 3 of the
     * opposite side for a straight swing, the opposite corner block for a diagonal one, none for (0, 0).
     */
    @JvmStatic
    fun excluded(sw: Tile, sx: Int, sy: Int): Set<Tile> {
        if (sx == 0 && sy == 0) return emptySet()
        val ox = -sx
        val oy = -sy
        val lo = -1
        val hi = EzekielArena.SIZE // ring coordinates relative to sw: -1..3
        return if (ox != 0 && oy != 0) {
            // Opposite corner and its two ring neighbours.
            val cx = if (ox < 0) lo else hi
            val cy = if (oy < 0) lo else hi
            val nx = if (ox < 0) lo + 1 else hi - 1
            val ny = if (oy < 0) lo + 1 else hi - 1
            setOf(sw.offset(cx, cy), sw.offset(nx, cy), sw.offset(cx, ny))
        } else if (ox != 0) {
            val x = if (ox < 0) lo else hi
            (0 until EzekielArena.SIZE).map { sw.offset(x, it) }.toSet()
        } else {
            val y = if (oy < 0) lo else hi
            (0 until EzekielArena.SIZE).map { sw.offset(it, y) }.toSet()
        }
    }

    /** The ring tiles the cleave hits for a swing ([sx], [sy]) from [sw]. */
    @JvmStatic
    fun hitTiles(sw: Tile, sx: Int, sy: Int): Set<Tile> {
        val spared = excluded(sw, sx, sy)
        return ring(sw).filterTo(LinkedHashSet()) { it !in spared }
    }
}

/**
 * The cross ("star") lightning (spec §4.2 Cross geometry [HOUSE], dbtable 137 `tfa_ezekiel_star` stripped): his 3x3
 * plus four arms [armWidth] wide (centred on his middle lane) and [armLength] long in N/E/S/W, clipped to the floor.
 * Players leave it by moving 2 tiles sideways from the middle of an arm ("move at least two tiles").
 */
object Star {

    @JvmStatic
    @JvmOverloads
    fun tiles(sw: Tile, armWidth: Int, armLength: Int, floor: Rect = EzekielArena.FLOOR): Set<Tile> {
        val out = LinkedHashSet<Tile>()
        val core = Rect.footprint(sw.x, sw.y, EzekielArena.SIZE)
        // Arms are centred on his middle row / column.
        val half = (armWidth - 1) / 2
        val midX = sw.x + 1
        val midY = sw.y + 1
        val arms = ArrayList<Rect>(4)
        if (armWidth > 0 && armLength > 0) {
            arms += Rect(midX - half, core.maxY + 1, midX - half + armWidth - 1, core.maxY + armLength)      // N
            arms += Rect(midX - half, core.minY - armLength, midX - half + armWidth - 1, core.minY - 1)      // S
            arms += Rect(core.maxX + 1, midY - half, core.maxX + armLength, midY - half + armWidth - 1)      // E
            arms += Rect(core.minX - armLength, midY - half, core.minX - 1, midY - half + armWidth - 1)      // W
        }
        for (r in listOf(core) + arms) {
            val clipped = r.intersect(floor) ?: continue
            out += clipped.tiles()
        }
        return out
    }
}
