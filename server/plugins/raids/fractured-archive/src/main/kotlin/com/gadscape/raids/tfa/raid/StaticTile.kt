package com.gadscape.raids.tfa.raid

/**
 * A tile in **static cache coordinates** (spec §0.3), as every coordinate in the spec is written.
 * Convert it with [TfaInstance.translate] before using it inside a raid instance.
 */
data class StaticTile(val x: Int, val y: Int, val plane: Int = 0) {

    /** Region id: `(x >> 6) << 8 | (y >> 6)`. */
    val regionId: Int get() = ((x shr 6) shl 8) or (y shr 6)

    fun offset(dx: Int, dy: Int, dPlane: Int = 0) = StaticTile(x + dx, y + dy, plane + dPlane)

    fun step(direction: Compass, tiles: Int = 1) = offset(direction.dx * tiles, direction.dy * tiles)

    override fun toString(): String = "($x,$y,$plane)"

    companion object {
        /** Packs a region id from map-square coordinates. */
        @JvmStatic
        fun regionId(squareX: Int, squareY: Int): Int = (squareX shl 8) or squareY
    }
}

/** The eight compass directions used by the spec for facings and lanes. */
enum class Compass(val dx: Int, val dy: Int) {
    N(0, 1), NE(1, 1), E(1, 0), SE(1, -1), S(0, -1), SW(-1, -1), W(-1, 0), NW(-1, 1);

    val opposite: Compass get() = entries[(ordinal + 4) % 8]
}
