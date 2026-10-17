package cache.loaders

import cache.IndexType
import cache.definitions.RegionDefinition
import cache.definitions.RegionDefinition.Companion.X
import cache.definitions.RegionDefinition.Companion.Y
import cache.definitions.RegionDefinition.Companion.Z
import cache.utils.readUnsignedByte
import com.displee.cache.CacheLibrary
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class RegionLoader(
    private val cacheLibrary: CacheLibrary,
    private val regionDefinitionCache: HashMap<Int, RegionDefinition> = HashMap()
) {
    // Newer caches store tile opcodes and overlay ids as 2-byte values instead of 1.
    // Detected per map file; remembered so exports are written back in the same format.
    private var wideFormat = true

    @Synchronized fun get(regionId: Int): RegionDefinition? {
        if (regionDefinitionCache[regionId] != null) {
            return regionDefinitionCache[regionId]
        }

        return loadRegion(regionId)
    }

    /** Swap in a region from elsewhere (import from another cache). */
    fun replace(regionId: Int, def: RegionDefinition) { regionDefinitionCache[regionId] = def }

    /** The edited copy if this region is loaded in the editor, else null. */
    fun cached(regionId: Int): RegionDefinition? = regionDefinitionCache[regionId]

    /** Read a region without keeping it (for the world map). */
    fun peek(regionId: Int): RegionDefinition? = regionDefinitionCache[regionId] ?: loadRegion(regionId, false)

    /** Terrain from a raw terrain (m) file, e.g. a server's custom map .dat. Tries both tile formats. */
    fun fromBytes(regionId: Int, bytes: ByteArray): RegionDefinition? {
        val def = parse(regionId, bytes, wideFormat) ?: parse(regionId, bytes, !wideFormat) ?: return null
        def.calculateTerrain()
        return def
    }

    private fun loadRegion(regionId: Int, keep: Boolean = true): RegionDefinition? {
        val regionX = (regionId shr 8) and 0xFF
        val regionY = regionId and 0xFF
        val map = cacheLibrary.data(IndexType.MAPS.id, "m${regionX}_${regionY}") ?: return null

        var regionDefinition = parse(regionId, map, true)
        if (regionDefinition != null) {
            wideFormat = true
        } else {
            regionDefinition = parse(regionId, map, false)
            if (regionDefinition != null) wideFormat = false
        }
        if (regionDefinition == null) {
            println("Region $regionId: map file could not be read in either format")
            return null
        }

        regionDefinition.calculateTerrain()
        if (keep) regionDefinitionCache[regionId] = regionDefinition
        return regionDefinition
    }

    private fun parse(regionId: Int, map: ByteArray, wide: Boolean): RegionDefinition? {
        val regionDefinition = RegionDefinition(regionId)
        val inputStream = ByteBuffer.wrap(map)
        try {
            for (z in 0 until Z) {
                for (x in 0 until X) {
                    for (y in 0 until Y) {
                        val tile = RegionDefinition.Tile()
                        regionDefinition.tiles[z][x][y] = tile
                        while (true) {
                            val attribute: Int = if (wide) inputStream.short.toInt() and 0xFFFF else inputStream.readUnsignedByte()
                            if (attribute == 0) {
                                break
                            } else if (attribute == 1) {
                                val height: Int = inputStream.readUnsignedByte()
                                tile.cacheHeight = height
                                tile.height = height
                                break
                            } else if (attribute <= 49) {
                                tile.attrOpcode = attribute
                                tile.overlayId = if (wide) inputStream.short.toInt() and 0xFFFF else inputStream.get().toInt() and 0xFF
                                tile.overlayPath = ((attribute - 2) / 4).toByte()
                                tile.overlayRotation = (attribute - 2 and 3).toByte()
                            } else if (attribute <= 81) {
                                tile.settings = (attribute - 49).toByte()
                            } else {
                                tile.underlayId = attribute - 81
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        // some private-server clients leave a byte of padding at the end (Roat Pkz does)
        return if (inputStream.remaining() <= 2) regionDefinition else null
    }

    fun findRegionForWorldCoordinates(x: Int, y: Int): RegionDefinition? {
        return get((x ushr 6) shl 8 or (y ushr 6))
    }

    fun writeRegion(outputLibrary: CacheLibrary, regionDefinition: RegionDefinition) {
        val outputStream = ByteArrayOutputStream()

        for (z in 0 until Z) {
            for (x in 0 until X) {
                for (y in 0 until Y) {
                    val tile: RegionDefinition.Tile = regionDefinition.tiles[z][x][y]!!
                    fun op(v: Int) {
                        if (wideFormat) outputStream.write(byteArrayOf((v shr 8).toByte(), v.toByte()))
                        else outputStream.write(byteArrayOf(v.toByte()))
                    }
                    if (tile.attrOpcode > 0) {
                        op(tile.attrOpcode)
                        op(tile.overlayId)
                    }

                    if (tile.settings > 0) {
                        op(tile.settings + 49)
                    }

                    if ((tile.underlayId) > 0) {
                        op((tile.underlayId) + 81)
                    }

                    val cacheHeight = tile.cacheHeight
                    if (cacheHeight != null) {
                        op(1)
                        // write the stored value: upper planes are relative to the plane below,
                        // so tile.height (absolute) would stack every floor on export
                        outputStream.write(byteArrayOf(cacheHeight.coerceIn(0, 255).toByte()))
                    } else {
                        op(0)
                    }
                }
            }
        }

        outputLibrary.put(5, "m${regionDefinition.regionX}_${regionDefinition.regionY}", outputStream.toByteArray())
        val status = outputLibrary.index(5).update()
        println(status)
    }
}