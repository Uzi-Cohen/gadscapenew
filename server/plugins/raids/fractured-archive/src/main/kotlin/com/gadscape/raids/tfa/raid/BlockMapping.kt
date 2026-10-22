package com.gadscape.raids.tfa.raid

/**
 * The static block a raid copies (spec §2.4): map squares x 63-65, y 86-90, i.e. chunks x 504-527 and y 688-727,
 * static tiles x 4032-4223 and y 5504-5823. Every room, the nexus, the Ocellus arena and the vault are inside it.
 */
object TfaBlock {
    const val STATIC_CHUNK_X = 504
    const val STATIC_CHUNK_Y = 688
    const val WIDTH_CHUNKS = 24
    const val HEIGHT_CHUNKS = 40
    const val STATIC_BASE_X = STATIC_CHUNK_X shl 3 // 4032
    const val STATIC_BASE_Y = STATIC_CHUNK_Y shl 3 // 5504
    const val WIDTH_TILES = WIDTH_CHUNKS shl 3
    const val HEIGHT_TILES = HEIGHT_CHUNKS shl 3

    fun containsStatic(x: Int, y: Int): Boolean =
        x >= STATIC_BASE_X && x < STATIC_BASE_X + WIDTH_TILES && y >= STATIC_BASE_Y && y < STATIC_BASE_Y + HEIGHT_TILES

    /** True if [regionId] is one of the 15 static map squares of the block. */
    fun containsRegion(regionId: Int): Boolean {
        val rx = regionId shr 8
        val ry = regionId and 0xff
        return rx in (STATIC_CHUNK_X shr 3) until ((STATIC_CHUNK_X + WIDTH_CHUNKS) shr 3) &&
            ry in (STATIC_CHUNK_Y shr 3) until ((STATIC_CHUNK_Y + HEIGHT_CHUNKS) shr 3)
    }

    /** SW static chunk of a map square. */
    fun regionChunkX(regionId: Int): Int = (regionId shr 8) shl 3
    fun regionChunkY(regionId: Int): Int = (regionId and 0xff) shl 3
}

/**
 * The coordinate maths of one raid block whose static SW corner (4032, 5504) sits at instance tile ([baseX], [baseY])
 * (spec §2.4: `translate(x, y, p) = (x - 4032 + base.x, y - 5504 + base.y, planeMap(region, p))`).
 *
 * `planeMap` is the identity except where a region is given a plane offset: the Alliance-camp nexus is copied to
 * instance plane 2 (see `NexusController`), so the nexus region gets offset 2 after the Guardian falls and every
 * static nexus coordinate (written on plane 0) lands on the camp.
 *
 * Pure: unit-tested in `BlockMappingTest`.
 */
class BlockMapping(val baseX: Int, val baseY: Int) {

    private val planeOffsets = HashMap<Int, Int>()

    fun setPlaneOffset(staticRegion: Int, offset: Int) {
        if (offset == 0) planeOffsets.remove(staticRegion) else planeOffsets[staticRegion] = offset
    }

    fun planeOffset(staticRegion: Int): Int = planeOffsets[staticRegion] ?: 0

    fun instanceX(staticX: Int): Int = staticX - TfaBlock.STATIC_BASE_X + baseX
    fun instanceY(staticY: Int): Int = staticY - TfaBlock.STATIC_BASE_Y + baseY

    fun instancePlane(staticX: Int, staticY: Int, staticPlane: Int): Int =
        (staticPlane + planeOffset(regionOf(staticX, staticY))).coerceIn(0, 3)

    /** True if the instance tile lies inside the block. */
    fun containsInstance(x: Int, y: Int): Boolean =
        x >= baseX && x < baseX + TfaBlock.WIDTH_TILES && y >= baseY && y < baseY + TfaBlock.HEIGHT_TILES

    /** Static tile of an instance tile, or null outside the block. */
    fun toStatic(x: Int, y: Int, plane: Int): StaticTile? {
        if (!containsInstance(x, y)) return null
        val sx = x - baseX + TfaBlock.STATIC_BASE_X
        val sy = y - baseY + TfaBlock.STATIC_BASE_Y
        val offset = planeOffset(regionOf(sx, sy))
        val sp = if (plane >= offset) plane - offset else plane
        return StaticTile(sx, sy, sp)
    }

    /** Instance chunk x / y of the SW chunk of static map square [staticRegion]. */
    fun instanceChunkX(staticRegion: Int): Int = (baseX shr 3) + TfaBlock.regionChunkX(staticRegion) - TfaBlock.STATIC_CHUNK_X
    fun instanceChunkY(staticRegion: Int): Int = (baseY shr 3) + TfaBlock.regionChunkY(staticRegion) - TfaBlock.STATIC_CHUNK_Y

    /** Instance map squares (region ids) overlapped by the 8x8 chunks of [staticRegion]. */
    fun instanceRegionsOf(staticRegion: Int): Set<Int> {
        val cx = instanceChunkX(staticRegion)
        val cy = instanceChunkY(staticRegion)
        val out = LinkedHashSet<Int>()
        for (x in cx until cx + 8) for (y in cy until cy + 8) out += ((x shr 3) shl 8) or (y shr 3)
        return out
    }

    companion object {
        fun regionOf(x: Int, y: Int): Int = ((x shr 6) shl 8) or (y shr 6)
    }
}
