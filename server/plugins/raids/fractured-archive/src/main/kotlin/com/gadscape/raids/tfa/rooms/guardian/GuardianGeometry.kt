package com.gadscape.raids.tfa.rooms.guardian

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/** A map tile on one plane (instance or static coordinates; the helpers do not care which). */
data class Tile(val x: Int, val y: Int) {
    override fun toString(): String = "($x,$y)"
}

/**
 * Pure tile geometry of the Archive Guardian fight (no world state, unit-tested). The boss is a size-5 npc: its
 * position is its south-west tile, its centre is SW + 2.
 */
object GuardianGeometry {

    const val BOSS_SIZE = 5

    @JvmStatic
    fun chebyshev(ax: Int, ay: Int, bx: Int, by: Int): Int = max(abs(ax - bx), abs(ay - by))

    /** Centre tile of an odd-sized footprint at [swX], [swY]. */
    @JvmStatic
    fun centre(swX: Int, swY: Int, size: Int = BOSS_SIZE): Tile = Tile(swX + size / 2, swY + size / 2)

    /** True if ([x], [y]) is under the [size] x [size] footprint whose south-west tile is ([swX], [swY]). */
    @JvmStatic
    fun under(swX: Int, swY: Int, size: Int, x: Int, y: Int): Boolean =
        x >= swX && x < swX + size && y >= swY && y < swY + size

    /**
     * Tendril spawn tile (spec §4.1, wiki "always spawn on the side of you opposite the boss"): the player's tile plus
     * (sign(px − cx), sign(py − cy)) from the boss centre. A player in line with the centre on one axis is offset on
     * the other axis only; a player on the centre tile gets their own tile.
     */
    @JvmStatic
    fun tendrilTile(px: Int, py: Int, cx: Int, cy: Int): Tile = Tile(px + (px - cx).sign, py + (py - cy).sign)

    /** The 3x3 a tendril on ([tx], [ty]) swipes: true for tiles within Chebyshev distance 1. */
    @JvmStatic
    fun inSwipe(tx: Int, ty: Int, x: Int, y: Int): Boolean = chebyshev(tx, ty, x, y) <= 1

    /**
     * The laser line (spec §4.1): a Bresenham ray from ([fromX], [fromY]) through ([throughX], [throughY]),
     * [length] tiles long (Chebyshev), 1 tile wide, starting with the origin. The ray passes exactly through the
     * aimed tile. If the aimed tile is the origin, the ray goes south.
     */
    @JvmStatic
    fun ray(fromX: Int, fromY: Int, throughX: Int, throughY: Int, length: Int): List<Tile> {
        var dx = throughX - fromX
        var dy = throughY - fromY
        if (dx == 0 && dy == 0) dy = -1
        val steps = max(abs(dx), abs(dy))
        // Extend the aim vector by a whole multiple, so the lattice point (from + aim) stays on the line.
        val multiple = (length + steps - 1) / steps
        val line = bresenham(fromX, fromY, fromX + dx * multiple, fromY + dy * multiple)
        return line.filter { chebyshev(fromX, fromY, it.x, it.y) <= length }
    }

    /** Integer Bresenham line from ([x0], [y0]) to ([x1], [y1]) inclusive (the engine's E18 line helper). */
    @JvmStatic
    fun bresenham(x0: Int, y0: Int, x1: Int, y1: Int): List<Tile> = Bridge.line(x0, y0, x1, y1)

    /** Tiles at Chebyshev distance [minDist]..[maxDist] from ([cx], [cy]), in a fixed scan order. */
    @JvmStatic
    fun ring(cx: Int, cy: Int, minDist: Int, maxDist: Int): List<Tile> {
        val out = ArrayList<Tile>()
        for (x in cx - maxDist..cx + maxDist) for (y in cy - maxDist..cy + maxDist) {
            val d = chebyshev(cx, cy, x, y)
            if (d in minDist..maxDist) out += Tile(x, y)
        }
        return out
    }

    /**
     * Mushroom candidate tiles (spec §4.1): Chebyshev [minDist]..[maxDist] from the boss centre, not under the boss,
     * walkable ([walkable]) and not taken ([taken]).
     */
    @JvmStatic
    fun mushroomCandidates(
        swX: Int, swY: Int, minDist: Int, maxDist: Int,
        walkable: (Tile) -> Boolean, taken: (Tile) -> Boolean,
    ): List<Tile> {
        val c = centre(swX, swY)
        return ring(c.x, c.y, minDist, maxDist).filter { t ->
            !under(swX, swY, BOSS_SIZE, t.x, t.y) && !taken(t) && walkable(t)
        }
    }

    /** True if the [size] footprint at ([swX], [swY]) lies inside the box `[minX, minY, maxX, maxY]`. */
    @JvmStatic
    fun inBox(swX: Int, swY: Int, size: Int, box: List<Int>): Boolean =
        swX >= box[0] && swY >= box[1] && swX + size - 1 <= box[2] && swY + size - 1 <= box[3]

    /**
     * The next south-west tile for a flying boss that keeps its target within [range] tiles of its centre (spec §4.1
     * movement rule): one step toward the target, never leaving [box]. Null when the target is already in range or
     * no step stays inside the box.
     */
    @JvmStatic
    fun followStep(swX: Int, swY: Int, targetX: Int, targetY: Int, range: Int, box: List<Int>): Tile? {
        val c = centre(swX, swY)
        if (chebyshev(c.x, c.y, targetX, targetY) <= range) return null
        val sx = (targetX - c.x).sign
        val sy = (targetY - c.y).sign
        val options = listOf(Tile(swX + sx, swY + sy), Tile(swX + sx, swY), Tile(swX, swY + sy))
        return options.firstOrNull { (it.x != swX || it.y != swY) && inBox(it.x, it.y, BOSS_SIZE, box) }
    }

    /**
     * The 7x2 strip behind the boss for the optional tail swipe (spec §4.1 [HOUSE]): behind = opposite the
     * dominant axis of its facing toward ([towardX], [towardY]).
     */
    @JvmStatic
    fun tailStrip(swX: Int, swY: Int, towardX: Int, towardY: Int): List<Tile> {
        val c = centre(swX, swY)
        val fx = towardX - c.x
        val fy = towardY - c.y
        val out = ArrayList<Tile>()
        if (abs(fx) >= abs(fy) && fx != 0) {
            val xs = if (fx > 0) (swX - 2..swX - 1) else (swX + BOSS_SIZE..swX + BOSS_SIZE + 1)
            for (x in xs) for (y in swY - 1..swY + BOSS_SIZE) out += Tile(x, y)
        } else {
            val ys = if (fy >= 0) (swY - 2..swY - 1) else (swY + BOSS_SIZE..swY + BOSS_SIZE + 1)
            for (y in ys) for (x in swX - 1..swX + BOSS_SIZE) out += Tile(x, y)
        }
        return out
    }

    /**
     * True if ([x], [y]) is in front of the boss centre toward the target and within [width] tiles of the line from
     * the centre to the target (the optional ranged cone, spec §4.1 [HOUSE]).
     */
    @JvmStatic
    fun inCone(cx: Int, cy: Int, tx: Int, ty: Int, x: Int, y: Int, width: Int): Boolean {
        val ax = (tx - cx).toDouble()
        val ay = (ty - cy).toDouble()
        val bx = (x - cx).toDouble()
        val by = (y - cy).toDouble()
        val len = kotlin.math.sqrt(ax * ax + ay * ay)
        if (len == 0.0) return false
        val along = (ax * bx + ay * by) / len
        if (along <= 0.0) return false
        val perpendicular = abs(ax * by - ay * bx) / len
        return perpendicular <= width + 1e-9
    }
}
