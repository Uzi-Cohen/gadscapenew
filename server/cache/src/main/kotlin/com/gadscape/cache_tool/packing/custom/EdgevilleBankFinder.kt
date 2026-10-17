package com.gadscape.cache_tool.packing.custom

import com.zenyte.CacheManager
import mgi.tools.jagcached.ArchiveType
import mgi.types.config.ObjectDefinitions
import net.runelite.cache.definitions.loaders.LocationsLoader

/**
 * Diagnostic: dumps every "bank"-named object location in the restored Edgeville
 * region, so banker NPC spawns can be aligned exactly with the real counter tiles.
 * Run via `./gradlew findEdgevilleBank`.
 */
object EdgevilleBankFinder {

    private const val EDGEVILLE_REGION = 12342

    @JvmStatic
    fun main(args: Array<String>) {
        CacheManager.loadDetached("cache/data/cache", true)

        val regionX = EDGEVILLE_REGION shr 8
        val regionY = EDGEVILLE_REGION and 255

        val maps = CacheManager.getCache().getArchive(ArchiveType.MAPS)
        val lGroup = maps.findGroupByName("l${regionX}_${regionY}")
            ?: error("l${regionX}_${regionY} not found in served cache")

        val locations = LocationsLoader().load(regionX, regionY, lGroup.files[0].data.buffer)

        for (loc in locations.locations) {
            val worldX = regionX * 64 + loc.position.x
            val worldY = regionY * 64 + loc.position.y
            if (worldX !in 3086..3103 || worldY !in 3485..3502) continue
            val def = ObjectDefinitions.get(loc.id)
            val name = def?.name ?: "?"
            println("id=${loc.id} name=\"$name\" x=$worldX y=$worldY z=${loc.position.z} type=${loc.type} orientation=${loc.orientation}")
        }
    }
}
