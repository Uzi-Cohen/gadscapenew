package com.gadscape.cache_tool.packing.custom

import com.zenyte.CacheManager
import mgi.tools.jagcached.ArchiveType
import mgi.tools.jagcached.cache.Cache
import mgi.tools.parser.TypeParser
import net.runelite.cache.definitions.loaders.LocationsLoader
import net.runelite.cache.definitions.loaders.MapLoader
import net.runelite.cache.definitions.savers.LocationSaver
import net.runelite.cache.definitions.savers.MapSaver

/**
 * One-off tool: replaces the (custom, hand-edited) Edgeville region in this project's
 * served cache with the genuine vanilla Edgeville region pulled from a real revision-211
 * OSRS cache dump. The location ("l") group of this region is XTEA-encrypted in the
 * source cache, so the key (fetched from OpenRS2's key archive for this exact cache) is
 * required to decrypt it. Run via `./gradlew restoreEdgeville`.
 */
object EdgevilleRestore {

    private const val EDGEVILLE_REGION = 12342

    // From https://archive.openrs2.org/caches/runescape/1266/keys.json, entry "l48_54" / mapsquare 12342
    private val EDGEVILLE_XTEA = intArrayOf(1054042798, -1242553935, -2078944575, -890801845)

    @JvmStatic
    fun main(args: Array<String>) {
        val sourceCachePath = args.getOrNull(0)
            ?: error("Usage: restoreEdgeville <path to vanilla cache dir containing main_file_cache.dat2>")

        val targetCache = CacheManager.loadCacheFiles("cache/data/cache", false)
        val sourceCache = Cache.openCache(sourceCachePath, true)

        try {
            val regionX = EDGEVILLE_REGION shr 8
            val regionY = EDGEVILLE_REGION and 255

            val maps = sourceCache.getArchive(ArchiveType.MAPS)
            val mGroup = maps.findGroupByName("m${regionX}_${regionY}")
                ?: error("m${regionX}_${regionY} not found in source cache")
            val lGroup = maps.findGroupByName("l${regionX}_${regionY}", EDGEVILLE_XTEA)
                ?: error("l${regionX}_${regionY} not found in source cache")

            val mapData = MapSaver().save(MapLoader().load(regionX, regionY, mGroup.files[0].data.buffer))
            val landData = LocationSaver().save(LocationsLoader().load(regionX, regionY, lGroup.files[0].data.buffer))

            TypeParser.packMap(EDGEVILLE_REGION, landData, mapData)
            println("Restored vanilla region $EDGEVILLE_REGION (${regionX}_$regionY) into the served cache.")
        } finally {
            sourceCache.close()
            targetCache.close()
        }
    }
}
