package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.raid.StaticTile

/**
 * The Smorks' cart shadow (spec §4.3 [INF]): Bork's stomps, gong barrage and beats "can be avoided by hiding behind the
 * Smorks' cart" [DOC]. The 9 cart locs 26209 do not block projectiles (loc blockrange=no), so the rule is scripted:
 * a tile is safe when the line from Bork to it passes through the cart's 3x3.
 *
 * - [Mode.BRESENHAM] (spec, default): the Bresenham line (E18 `Lines.bresenham`, passed in as [line]) from Bork's
 *   middle tile (SW + size / 2, as the engine's `getMiddleLocation`) to the tile; any tile of it after the start
 *   inside the cart → shadowed.
 * - [Mode.SEGMENT]: the exact segment from the centre of his 4x4 to the tile's centre crosses the cart square
 *   (symmetric; a little wider than the Bresenham shadow).
 *
 * Once the Smorks are dead there is no shadow at all ("Bork's stomps will cover the entire arena" [DOC]); the brain
 * stops asking. Pure; unit tested.
 */
class CartShadow(
    private val mode: Mode,
    /** Packed Bresenham tiles (`x shl 16 or y`) from (x0, y0) to (x1, y1), both included; see [Bridge.line]. */
    private val line: (Int, Int, Int, Int) -> IntArray,
    private val cartSw: StaticTile = BorkArena.CART_SW,
    private val cartSize: Int = BorkArena.CART_SIZE,
) {

    enum class Mode {
        BRESENHAM, SEGMENT;

        companion object {
            fun of(name: String): Mode = if (name.equals("segment", ignoreCase = true)) SEGMENT else BRESENHAM
        }
    }

    /** True if [tile] is hidden from Bork (SW [borkSw], [borkSize]) behind the cart. */
    fun isShadowed(borkSw: StaticTile, borkSize: Int, tile: StaticTile): Boolean = when (mode) {
        Mode.BRESENHAM -> bresenham(borkSw, borkSize, tile)
        Mode.SEGMENT -> segment(borkSw, borkSize, tile)
    }

    /** The shadowed tiles among [tiles]. */
    fun shadowed(borkSw: StaticTile, borkSize: Int, tiles: Collection<StaticTile>): Set<StaticTile> =
        tiles.filterTo(HashSet()) { isShadowed(borkSw, borkSize, it) }

    private fun inCart(x: Int, y: Int): Boolean =
        x >= cartSw.x && x < cartSw.x + cartSize && y >= cartSw.y && y < cartSw.y + cartSize

    private fun bresenham(borkSw: StaticTile, borkSize: Int, tile: StaticTile): Boolean {
        if (inCart(tile.x, tile.y)) return false
        val sx = borkSw.x + borkSize / 2
        val sy = borkSw.y + borkSize / 2
        val ray = line(sx, sy, tile.x, tile.y)
        for (i in 1 until ray.size) {
            val t = ray[i]
            if (inCart(t ushr 16, t and 0xFFFF)) return true
        }
        return false
    }

    /** Liang-Barsky: does the open segment between the two centres overlap the cart square with positive length? */
    private fun segment(borkSw: StaticTile, borkSize: Int, tile: StaticTile): Boolean {
        if (inCart(tile.x, tile.y)) return false
        val x0 = borkSw.x + borkSize / 2.0
        val y0 = borkSw.y + borkSize / 2.0
        val x1 = tile.x + 0.5
        val y1 = tile.y + 0.5
        val minX = cartSw.x.toDouble()
        val maxX = (cartSw.x + cartSize).toDouble()
        val minY = cartSw.y.toDouble()
        val maxY = (cartSw.y + cartSize).toDouble()
        val dx = x1 - x0
        val dy = y1 - y0
        var t0 = 0.0
        var t1 = 1.0
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(x0 - minX, maxX - x0, y0 - minY, maxY - y0)
        for (i in 0 until 4) {
            if (p[i] == 0.0) {
                if (q[i] < 0) return false
                continue
            }
            val r = q[i] / p[i]
            if (p[i] < 0) {
                if (r > t1) return false
                if (r > t0) t0 = r
            } else {
                if (r < t0) return false
                if (r < t1) t1 = r
            }
        }
        return t1 - t0 > EPSILON
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}
