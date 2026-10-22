package com.gadscape.game.world

import com.zenyte.game.util.ProjectileUtils
import com.zenyte.game.world.Position
import com.zenyte.game.world.entity.Entity

/**
 * Line and line-of-sight helpers (spec §2.7 E18; Guardian laser, Bork cart shadow, Xarth cover and heal blast).
 *
 * Tiles are packed as `x shl 16 or y` longs-free ints ([tile], [tileX], [tileY]) so the rays allocate one array.
 */
object Lines {

    @JvmStatic
    fun tile(x: Int, y: Int): Int = (x shl 16) or (y and 0xFFFF)

    @JvmStatic
    fun tileX(tile: Int): Int = tile ushr 16

    @JvmStatic
    fun tileY(tile: Int): Int = tile and 0xFFFF

    /**
     * The tiles of the Bresenham line from (x0, y0) to (x1, y1), both ends included, in order from the start.
     * Every step moves one tile on the major axis; the minor axis steps when the error passes half a tile.
     */
    @JvmStatic
    fun bresenham(x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        val dx = Math.abs(x1 - x0)
        val dy = Math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        val out = IntArray(maxOf(dx, dy) + 1)
        var x = x0
        var y = y0
        var err = dx - dy
        var i = 0
        while (true) {
            out[i++] = tile(x, y)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 > -dy) {
                err -= dy
                x += sx
            }
            if (e2 < dx) {
                err += dx
                y += sy
            }
        }
        return if (i == out.size) out else out.copyOf(i)
    }

    /**
     * The first tile on the Bresenham line from (x0, y0) to (x1, y1) for which [blocked] is true, skipping the start
     * tile when [skipStart] (the caster's own tile), or null when the line is clear. E.g. the Xarth pillar that covers
     * a player from his blast.
     */
    @JvmStatic
    @JvmOverloads
    fun firstBlocked(x0: Int, y0: Int, x1: Int, y1: Int, skipStart: Boolean = true, blocked: (x: Int, y: Int) -> Boolean): Int? {
        val ray = bresenham(x0, y0, x1, y1)
        for (i in (if (skipStart) 1 else 0) until ray.size) {
            val t = ray[i]
            if (blocked(tileX(t), tileY(t))) return t
        }
        return null
    }

    /** Whether any tile of the line (start excluded when [skipStart]) is [blocked]. */
    @JvmStatic
    @JvmOverloads
    fun isBlocked(x0: Int, y0: Int, x1: Int, y1: Int, skipStart: Boolean = true, blocked: (x: Int, y: Int) -> Boolean): Boolean =
        firstBlocked(x0, y0, x1, y1, skipStart, blocked) != null

    /** Whether (x, y) lies in the [size]×[size] footprint with south-west tile (swX, swY). */
    @JvmStatic
    fun inFootprint(x: Int, y: Int, swX: Int, swY: Int, size: Int): Boolean =
        x >= swX && y >= swY && x < swX + size && y < swY + size

    /**
     * The engine's projectile line-of-sight test between two positions (the map's projectile clipping, like an NPC
     * shooting at a player): true when something on the map blocks it. Wrapper of
     * [ProjectileUtils.isProjectileClipped] with explicit footprint sizes.
     */
    @JvmStatic
    @JvmOverloads
    fun isClipped(from: Position, to: Position, fromSize: Int = 1, toSize: Int = 1, closeProximity: Boolean = false): Boolean =
        ProjectileUtils.isProjectileClipped(null, null, from, to, fromSize, toSize, closeProximity, false)

    /** [isClipped] between two entities, using their footprints and positions. */
    @JvmStatic
    fun isClipped(from: Entity, to: Entity): Boolean =
        ProjectileUtils.isProjectileClipped(from, to, from.location, to.location, from.size, to.size, false, false)
}
