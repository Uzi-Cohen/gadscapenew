package controllers.worldRenderer

import kotlin.math.cos
import kotlin.math.sin

object Constants {
    const val MAX_DISTANCE = 1000

    const val LOCAL_COORD_BITS = 7
    const val LOCAL_TILE_SIZE = 1 shl LOCAL_COORD_BITS // 128 - size of a tile in local coordinates
    const val LOCAL_HALF_TILE_SIZE = LOCAL_TILE_SIZE / 2

    private const val UNIT = Math.PI / 1024.0 // How much of the circle each unit of SINE/COSINE is
    val SINE =
        IntArray(2048) { i -> (65536.0 * sin(i * UNIT)).toInt() } // sine angles for each of the 2048 units, * 65536 and stored as an int
    val COSINE = IntArray(2048) { i -> (65536.0 * cos(i * UNIT)).toInt() } // cosine

    const val HOVER_HSL_ALPHA = 25
    const val SELECTED_HSL = 22222
    /** Red tint for tiles shown by the tile flag view. */
    const val FLAG_HSL = 7 shl 7 or 50
    /** Orange: tiles the game won't let players walk on. */
    const val BLOCK_HSL = (6 shl 10) or (7 shl 7) or 60
    /** Tile marked "blocked" with nothing solid on it. */
    const val MARK_HSL = (0 shl 10) or (7 shl 7) or 50
}
