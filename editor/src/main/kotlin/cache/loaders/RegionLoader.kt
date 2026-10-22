package cache.loaders

import cache.MapStore
import cache.TerrainCodec
import cache.definitions.RegionDefinition
import com.displee.cache.CacheLibrary

/**
 * Terrain of map squares, from either map layout (rev 211 named "m{x}_{y}" groups, build 241 region-id groups: see
 * [MapStore]); the file format itself is [TerrainCodec].
 */
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
        val def = TerrainCodec.decode(regionId, bytes, wideFormat, false)
            ?: TerrainCodec.decode(regionId, bytes, !wideFormat, false) ?: return null
        def.calculateTerrain()
        return def
    }

    @Synchronized private fun loadRegion(regionId: Int, keep: Boolean = true): RegionDefinition? {
        val map = MapStore.terrain(cacheLibrary, regionId) ?: return null
        val keyed = MapStore.keyedByRegion(cacheLibrary)

        var regionDefinition = TerrainCodec.decode(regionId, map, true, keyed)
        if (regionDefinition != null) {
            wideFormat = true
        } else if (!keyed) {
            // build 241 terrain is always wide; older caches may use single-byte tiles
            regionDefinition = TerrainCodec.decode(regionId, map, false, false)
            if (regionDefinition != null) wideFormat = false
        }
        if (regionDefinition == null) {
            println("Region $regionId: map file could not be read in either format")
            return null
        }
        if (keyed) regionDefinition.environment = MapStore.environment(cacheLibrary, regionId).takeIf { it.isNotEmpty() }

        regionDefinition.calculateTerrain()
        if (keep) regionDefinitionCache[regionId] = regionDefinition
        return regionDefinition
    }

    fun findRegionForWorldCoordinates(x: Int, y: Int): RegionDefinition? {
        return get((x ushr 6) shl 8 or (y ushr 6))
    }

    /** The terrain file of [regionDefinition] in the format of [library]'s maps archive. */
    fun encode(library: CacheLibrary, regionDefinition: RegionDefinition): ByteArray =
        TerrainCodec.encode(regionDefinition, wideFormat, MapStore.keyedByRegion(library))

    /**
     * Writes the region's terrain into [outputLibrary], in that cache's layout: group "m{x}_{y}" (rev 211) or file 0 of
     * group regionId (build 241; a square new there also gets environment files 2-4, see [MapStore.putTerrain]).
     */
    fun writeRegion(outputLibrary: CacheLibrary, regionDefinition: RegionDefinition) {
        MapStore.putTerrain(outputLibrary, regionDefinition.regionId, encode(outputLibrary, regionDefinition),
            regionDefinition.environment)
        val status = outputLibrary.index(5).update()
        println(status)
    }
}
