package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.raid.StaticTile
import kotlin.math.abs
import kotlin.math.max

/**
 * Static geometry of the Bork arena (region 16727, plane 0) [CACHE map, `raids4/bork-smorks/arena_map.txt`]:
 *
 * - the box x 4184..4198, y 5591..5607 (15 x 17) without its 4 corner tiles, ringed by the invisible wall loc 85;
 * - the Smorks' cart in the exact middle: 9 × loc 26209 at x 4190..4192, y 5598..5600 (block walking, not projectiles);
 * - 242 walkable tiles (the flood fill from the entry tile (4186,5599) finds the same 242).
 *
 * All coordinates are static; the room translates them through the raid's `TfaInstance`. Pure; unit tested.
 */
object BorkArena {

    const val MIN_X = 4184
    const val MAX_X = 4198
    const val MIN_Y = 5591
    const val MAX_Y = 5607

    /** The cart's 3x3 (the Smorks' footprint): SW (4190,5598), centre (4191,5599). */
    @JvmField
    val CART_SW = StaticTile(4190, 5598, 0)
    const val CART_SIZE = 3

    /** Bork 16640 and the Smorks 16641 sizes [CACHE npc config]. */
    const val BORK_SIZE = 4
    const val SMORKS_SIZE = 3

    /** Inside the box (walkable floor or the cart). */
    @JvmStatic
    fun inBox(x: Int, y: Int): Boolean {
        if (x < MIN_X || x > MAX_X || y < MIN_Y || y > MAX_Y) return false
        val cornerX = x == MIN_X || x == MAX_X
        val cornerY = y == MIN_Y || y == MAX_Y
        return !(cornerX && cornerY)
    }

    @JvmStatic
    fun inBox(tile: StaticTile): Boolean = inBox(tile.x, tile.y)

    @JvmStatic
    fun isCart(x: Int, y: Int): Boolean =
        x >= CART_SW.x && x < CART_SW.x + CART_SIZE && y >= CART_SW.y && y < CART_SW.y + CART_SIZE

    @JvmStatic
    fun isWalkable(x: Int, y: Int): Boolean = inBox(x, y) && !isCart(x, y)

    @JvmStatic
    fun isWalkable(tile: StaticTile): Boolean = isWalkable(tile.x, tile.y)

    /** Every walkable tile, x-major (242 tiles). */
    @JvmField
    val WALKABLE: List<StaticTile> = buildList {
        for (x in MIN_X..MAX_X) for (y in MIN_Y..MAX_Y) if (isWalkable(x, y)) add(StaticTile(x, y, 0))
    }

    /**
     * Chebyshev distance from ([x], [y]) to the [size]x[size] footprint with south-west tile [sw]: 0 inside it, 1 on
     * the ring around it (diagonal corners included), and so on.
     */
    @JvmStatic
    fun distToFootprint(sw: StaticTile, size: Int, x: Int, y: Int): Int {
        val dx = max(0, max(sw.x - x, x - (sw.x + size - 1)))
        val dy = max(0, max(sw.y - y, y - (sw.y + size - 1)))
        return max(dx, dy)
    }

    @JvmStatic
    fun distToFootprint(sw: StaticTile, size: Int, tile: StaticTile): Int = distToFootprint(sw, size, tile.x, tile.y)

    /** Whether ([x], [y]) is in the footprint. */
    @JvmStatic
    fun inFootprint(sw: StaticTile, size: Int, x: Int, y: Int): Boolean =
        x >= sw.x && y >= sw.y && x < sw.x + size && y < sw.y + size

    /** Whether the [side]x[side] square centred on [centre] (odd side) shares a tile with the footprint. */
    @JvmStatic
    fun squareTouchesFootprint(centre: StaticTile, side: Int, sw: StaticTile, size: Int): Boolean {
        val half = side / 2
        return centre.x - half <= sw.x + size - 1 && centre.x + half >= sw.x &&
            centre.y - half <= sw.y + size - 1 && centre.y + half >= sw.y
    }

    /** Whether [tile] is in the [side]x[side] square centred on [centre] (odd side). */
    @JvmStatic
    fun inSquare(tile: StaticTile, centre: StaticTile, side: Int): Boolean {
        val half = side / 2
        return abs(tile.x - centre.x) <= half && abs(tile.y - centre.y) <= half
    }

    /** The walkable tiles of the [side]x[side] square centred on [centre] (odd side), x-major. */
    @JvmStatic
    fun walkableSquare(centre: StaticTile, side: Int): List<StaticTile> {
        val half = side / 2
        return buildList {
            for (x in centre.x - half..centre.x + half) for (y in centre.y - half..centre.y + half) {
                if (isWalkable(x, y)) add(StaticTile(x, y, centre.plane))
            }
        }
    }

    /** The engine's "middle" tile of a footprint (Zenyte `NPC.getMiddleLocation`: SW + size / 2). */
    @JvmStatic
    fun middle(sw: StaticTile, size: Int): StaticTile = StaticTile(sw.x + size / 2, sw.y + size / 2, sw.plane)

    /** Chebyshev distance between two tiles. */
    @JvmStatic
    fun chebyshev(a: StaticTile, b: StaticTile): Int = max(abs(a.x - b.x), abs(a.y - b.y))

    /** Whether a [size]x[size] footprint with SW [sw] fits on walkable floor (Bork's spawn and debug moves). */
    @JvmStatic
    fun footprintWalkable(sw: StaticTile, size: Int): Boolean {
        for (x in sw.x until sw.x + size) for (y in sw.y until sw.y + size) if (!isWalkable(x, y)) return false
        return true
    }
}
