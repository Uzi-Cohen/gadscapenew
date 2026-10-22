package utils

import cache.definitions.LocationsDefinition
import cache.definitions.RegionDefinition
import cache.loaders.LocationsLoader
import cache.loaders.RegionLoader
import com.displee.cache.CacheLibrary
import models.RegionAccess
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Another cache opened for reading maps from, in either format:
 *  - new (main_file_cache.dat2, OSRS style, optional xteas.json)
 *  - old (main_file_cache.dat, 317-style private-server clients)
 * [access] gives its regions; nothing is ever written to it.
 */
class OtherCache private constructor(
    val folder: File,
    val oldStyle: Boolean,
    private val regionFn: (Int) -> RegionDefinition?,
    private val locsFn: (Int) -> LocationsDefinition?,
    /** Regions it has, when the format lists them (old style); null = unknown (new style: look them up). */
    val regionIds: Set<Int>?,
    /** Raw ground / object files, for comparing with another cache. */
    val rawTerrain: (Int) -> ByteArray?,
    val rawObjects: (Int) -> ByteArray?,
    private val closer: () -> Unit,
    val describe: String
) : AutoCloseable {

    private val r = ConcurrentHashMap<Int, java.util.Optional<RegionDefinition>>()
    private val l = ConcurrentHashMap<Int, java.util.Optional<LocationsDefinition>>()

    val access: RegionAccess = object : RegionAccess {
        override fun region(regionId: Int): RegionDefinition? = r.computeIfAbsent(regionId) {
            java.util.Optional.ofNullable(try { regionFn(regionId) } catch (e: Exception) { null }) }.orElse(null)
        override fun locations(regionId: Int): LocationsDefinition? = l.computeIfAbsent(regionId) {
            java.util.Optional.ofNullable(try { locsFn(regionId) } catch (e: Exception) { null }) }.orElse(null)
    }

    override fun close() = try { closer() } catch (e: Exception) {}

    companion object {
        private fun find(dir: File, name: String) = dir.walkTopDown().maxDepth(6)
            .filter { it.isFile && it.name.equals(name, true) }.sortedBy { it.path.length }.firstOrNull()

        /** Is there a cache of either kind in (or under) this folder? */
        fun exists(dir: File) = dir.isDirectory && (find(dir, "main_file_cache.dat2") != null || find(dir, "main_file_cache.dat") != null)

        /**
         * Open the cache in [dir]. [myRegions] / [myLocations] are only used for their file readers
         * (old-style map files are parsed with them).
         */
        fun open(dir: File, myRegions: RegionLoader, myLocations: LocationsLoader): OtherCache {
            val newDat = find(dir, "main_file_cache.dat2")
            val oldDat = find(dir, "main_file_cache.dat")
            if (newDat != null) {
                val keysDir = listOfNotNull(newDat.parentFile, newDat.parentFile.parentFile).map { File(it, "xteas.json") }
                    .firstOrNull { it.isFile }?.parentFile ?: GadTools.noKeysDir()
                val lib = CacheLibrary(newDat.parent)
                val xtea = cache.XteaManager(keysDir.path)
                val regions = RegionLoader(lib)
                val locs = LocationsLoader(lib, xtea)
                return OtherCache(newDat.parentFile, false,
                    { regions.peek(it) }, { locs.peek(it) }, null,
                    { id -> lib.data(5, "m${id shr 8}_${id and 0xFF}") },
                    { id -> lib.data(5, "l${id shr 8}_${id and 0xFF}", xtea.getKeys(id)) ?: lib.data(5, "l${id shr 8}_${id and 0xFF}") },
                    { lib.close() }, "new-style cache")
            }
            if (oldDat != null) {
                val c = cache.Cache317Maps(oldDat.parentFile) { id, b -> myRegions.fromBytes(id, b) != null }
                return OtherCache(oldDat.parentFile, true,
                    { id -> c.terrain(id)?.let { myRegions.fromBytes(id, it) } },
                    { id -> c.objects(id)?.let { myLocations.fromBytes(id, it) } },
                    c.mapIndex.keys.toSet(),
                    { c.terrain(it) }, { c.objects(it) },
                    { c.close() }, "old-style cache, ${c.mapIndex.size} regions")
            }
            throw IllegalArgumentException("No main_file_cache.dat2 or main_file_cache.dat in ${dir.path}")
        }
    }
}
