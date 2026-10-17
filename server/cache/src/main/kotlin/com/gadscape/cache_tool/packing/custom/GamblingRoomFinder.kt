package com.gadscape.cache_tool.packing.custom

import com.zenyte.CacheManager
import mgi.tools.jagcached.ArchiveType
import mgi.types.config.ObjectDefinitions
import net.runelite.cache.definitions.loaders.LocationsLoader

/**
 * Diagnostic: dumps every object placed around the existing gambling zone teleport
 * destination (3366, 6939, plane 2) referenced by ::gambling/::dice/::fp in
 * GameCommands.java, so the real blackjack table/dice/mines cabinets already built
 * into the map can be identified and hooked up rather than rebuilt.
 * Run via `./gradlew findGamblingRoom`.
 */
object GamblingRoomFinder {

    private const val CENTER_X = 3366
    private const val CENTER_Y = 6939
    private const val CENTER_Z = 2
    private const val RADIUS = 40

    @JvmStatic
    fun main(args: Array<String>) {
        CacheManager.loadDetached("cache/data/cache", true)

        val minRegionX = (CENTER_X - RADIUS) shr 6
        val maxRegionX = (CENTER_X + RADIUS) shr 6
        val minRegionY = (CENTER_Y - RADIUS) shr 6
        val maxRegionY = (CENTER_Y + RADIUS) shr 6

        val maps = CacheManager.getCache().getArchive(ArchiveType.MAPS)

        for (regionX in minRegionX..maxRegionX) {
            for (regionY in minRegionY..maxRegionY) {
                val lGroup = maps.findGroupByName("l${regionX}_${regionY}") ?: continue
                val locations = try {
                    LocationsLoader().load(regionX, regionY, lGroup.files[0].data.buffer)
                } catch (e: Exception) {
                    println("Failed to load l${regionX}_${regionY}: ${e.message}")
                    continue
                }
                for (loc in locations.locations) {
                    val worldX = regionX * 64 + loc.position.x
                    val worldY = regionY * 64 + loc.position.y
                    if (worldX !in (CENTER_X - RADIUS)..(CENTER_X + RADIUS)) continue
                    if (worldY !in (CENTER_Y - RADIUS)..(CENTER_Y + RADIUS)) continue
                    if (loc.position.z != CENTER_Z) continue
                    val def = ObjectDefinitions.get(loc.id)
                    val name = def?.name ?: "?"
                    println("id=${loc.id} name=\"$name\" x=$worldX y=$worldY z=${loc.position.z} type=${loc.type} orientation=${loc.orientation}")
                }
            }
        }
    }
}
