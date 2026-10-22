package cache.definitions

import cache.utils.HeightCalc

class RegionDefinition(
    val regionId: Int = 0,
    val tiles: Array<Array<Array<Tile?>>> = Array(Z) { Array(X) { arrayOfNulls<Tile>(Y) } }
) {
    val regionX: Int get() = (regionId shr 8) and 0xFF
    val regionY: Int get() = regionId and 0xFF

    val baseX: Int get() = regionId shr 8 and 0xFF shl 6 // local coords are in bottom 6 bits (64*64)
    val baseY: Int get() = (regionId and 0xFF) shl 6

    val tileSettings: Array<Array<IntArray>> = Array(Z) { Array(X) { IntArray(Y) } }
    val overlayIds: Array<Array<IntArray>> = Array(Z) { Array(X) { IntArray(Y) } }
    val overlayPaths: Array<Array<IntArray>> = Array(Z) { Array(X) { IntArray(Y) } }
    val overlayRotations: Array<Array<IntArray>> = Array(Z) { Array(X) { IntArray(Y) } }
    val underlayIds: Array<Array<IntArray>> = Array(Z) { Array(X) { IntArray(Y) } }

    /**
     * Build 241 terrain trailer (see cache.TerrainCodec): the flags byte after level 3 and, when its bit 0 is set, the
     * raw fifth "underwater" level. Not shown or edited; written back as read into build 241 caches, left out of rev 211.
     */
    var terrainFlags: Int = 0
    var underwater: ByteArray? = null

    /**
     * Environment files 2-4 (lighting, fog, sky) of the square in the build 241 cache it was read from, or null (rev
     * 211 caches have none). Used when the square is written into a build 241 cache that doesn't have it yet.
     */
    var environment: Map<Int, ByteArray>? = null

    fun calculateTerrain() {
        for (z in 0 until Z) {
            for (x in 0 until X) {
                for (y in 0 until Y) {
                    val tile: Tile = tiles[z][x][y]?: continue
                    if (tile.cacheHeight == null) {
                        if (z == 0) {
                            tile.height = -HeightCalc.calculate(baseX + x + 0xe3b7b, baseY + y + 0x87cce) * 8
                        } else {
                            tile.height = tiles[z-1][x][y]!!.height - 240
                        }
                    } else {
                        var height: Int = tile.cacheHeight!!
                        if (height == 1) {
                            height = 0
                        }

                        if (z == 0) {
                            tiles[0][x][y]!!.height = -height * 8
                        } else {
                            tiles[z][x][y]!!.height = (tiles[z-1][x][y]?.height ?: 0) - height * 8
                        }
                    }
                    overlayIds[z][x][y] = tile.overlayId
                    overlayPaths[z][x][y] = tile.overlayPath.toInt()
                    overlayRotations[z][x][y] = tile.overlayRotation.toInt()
                    tileSettings[z][x][y] = tile.settings.toInt()
                    underlayIds[z][x][y] = tile.underlayId
                }
            }
        }
    }

    data class Tile(
        var cacheHeight: Int? = null,
        var height: Int = 0,
        var attrOpcode: Int = 0,
        var settings: Byte = 0,
        var overlayId: Int = 0,
        var overlayPath: Byte = 0,
        var overlayRotation: Byte = 0,
        var underlayId: Int = 0
    )

    companion object {
        const val Z = 4
        const val X = 64
        const val Y = 64
    }
}

