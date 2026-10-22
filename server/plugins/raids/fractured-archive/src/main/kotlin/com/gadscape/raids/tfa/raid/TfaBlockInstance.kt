package com.gadscape.raids.tfa.raid

import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.region.dynamicregion.AllocatedArea
import com.zenyte.game.world.region.dynamicregion.MapBuilder
import org.slf4j.LoggerFactory

/**
 * WU-12 implementation of [TfaInstance] (spec §2.4): one `MapBuilder.findEmptyChunk(24, 40)` block per raid that keeps
 * the static layout, so every spec coordinate works through [translate]. Regions are copied lazily:
 *
 * | Region | When | Planes |
 * |---|---|---|
 * | 16471 nexus | raid created | variant plane `nexus.variantPlanes[0]` (1) → instance plane 0 |
 * | 16471 nexus | Guardian death + 9 t | variant plane `nexus.variantPlanes[1]` (3) → instance plane [CAMP_PLANE] (2) |
 * | committed side room | side committed | all planes as-is |
 * | 16472 Ocellus | NEXUS_FINAL | all planes as-is (players on plane 1) |
 * | 16474 vault | Ocellus death | all planes as-is (players on plane 2) |
 *
 * Copying only adds chunks that were empty before (a later copy never lands on a loaded chunk), so the dynamic
 * regions reload exactly the new chunks. Players whose client already built the map need a rebuild afterwards
 * ([TfaRaid.rebuildMaps]).
 */
class TfaBlockInstance private constructor(val area: AllocatedArea) : TfaInstance {

    val mapping = BlockMapping(area.chunkX shl 3, area.chunkY shl 3)

    /** "region:from>to" keys already copied. */
    private val copies = LinkedHashSet<String>()

    var released = false
        private set

    override fun translate(x: Int, y: Int, plane: Int): Location =
        Location(mapping.instanceX(x), mapping.instanceY(y), mapping.instancePlane(x, y, plane))

    override fun toStatic(location: Location): StaticTile? = mapping.toStatic(location.x, location.y, location.plane)

    override fun contains(location: Location): Boolean = mapping.containsInstance(location.x, location.y)

    fun isCopied(staticRegion: Int, fromPlane: Int = -1, toPlane: Int = -1): Boolean =
        key(staticRegion, fromPlane, toPlane) in copies

    /** Copies every plane of [staticRegion] as-is (rooms, Ocellus, vault). Idempotent. */
    fun copyRegion(staticRegion: Int) {
        val key = key(staticRegion, -1, -1)
        if (released || !copies.add(key)) return
        require(TfaBlock.containsRegion(staticRegion)) { "region $staticRegion is outside the raid block" }
        MapBuilder.copyAllPlanesMap(
            area, TfaBlock.regionChunkX(staticRegion), TfaBlock.regionChunkY(staticRegion),
            mapping.instanceChunkX(staticRegion), mapping.instanceChunkY(staticRegion), 8, 8,
        )
        load(staticRegion)
        log.debug("Fractured Archive: copied region {} (all planes) to {}", staticRegion, describe())
    }

    /** Copies one cache plane of [staticRegion] onto instance plane [toPlane] (the nexus variants). Idempotent. */
    fun copyRegionPlane(staticRegion: Int, fromPlane: Int, toPlane: Int) {
        val key = key(staticRegion, fromPlane, toPlane)
        if (released || !copies.add(key)) return
        require(TfaBlock.containsRegion(staticRegion)) { "region $staticRegion is outside the raid block" }
        MapBuilder.copySquare(
            area, 8, TfaBlock.regionChunkX(staticRegion), TfaBlock.regionChunkY(staticRegion), fromPlane,
            mapping.instanceChunkX(staticRegion), mapping.instanceChunkY(staticRegion), toPlane, 0,
        )
        load(staticRegion)
        log.debug("Fractured Archive: copied region {} plane {} -> {} at {}", staticRegion, fromPlane, toPlane, describe())
    }

    /** Loads the touched dynamic regions now, so collision and map locs exist before NPCs spawn there. */
    private fun load(staticRegion: Int) {
        for (id in mapping.instanceRegionsOf(staticRegion)) {
            try {
                World.getRegion(id, true)
            } catch (e: Exception) {
                log.error("Fractured Archive: loading instance region {} failed", id, e)
            }
        }
    }

    /** Frees the map space (NPCs, locs and ground items in it are removed by `MapBuilder.destroy`). */
    fun release() {
        if (released) return
        released = true
        try {
            MapBuilder.destroy(area)
        } catch (e: Exception) {
            log.error("Fractured Archive: releasing {} failed", describe(), e)
        }
    }

    fun copiedRegions(): List<String> = copies.toList()

    fun describe(): String = "block at (${mapping.baseX},${mapping.baseY}) regions ${area.minRegionX},${area.minRegionY} " +
        "${area.widthInRegions}x${area.heightInRegions}"

    private fun key(region: Int, from: Int, to: Int) = "$region:$from>$to"

    companion object {
        private val log = LoggerFactory.getLogger(TfaBlockInstance::class.java)

        /** Instance plane of the Alliance-camp nexus variant (empty until the Guardian falls; see class doc). */
        const val CAMP_PLANE = 2

        /** Allocates a 24 x 40-chunk block. Throws `OutOfSpaceException` when the map is full. */
        @JvmStatic
        fun allocate(): TfaBlockInstance = TfaBlockInstance(MapBuilder.findEmptyChunk(TfaBlock.WIDTH_CHUNKS, TfaBlock.HEIGHT_CHUNKS))
    }
}
