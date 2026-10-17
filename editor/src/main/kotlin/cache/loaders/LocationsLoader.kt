package cache.loaders

import cache.XteaManager
import cache.definitions.Location
import cache.definitions.LocationsDefinition
import cache.definitions.RegionDefinition
import com.displee.cache.CacheLibrary
import cache.utils.readUnsignedShortSmart
import cache.utils.writeUnsignedShortSmart
import cache.utils.readUnsignedIntSmartShortCompat
import cache.utils.writeUnsignedIntSmartShortCompat
import java.nio.ByteBuffer

class LocationsLoader(
    private val library: CacheLibrary,
    private val xtea: XteaManager,
    private val locationsDefinitionCache: HashMap<Int, LocationsDefinition> = HashMap()
) {
    private val unencryptedRegions = HashSet<Int>()

    @Synchronized fun get(regionId: Int): LocationsDefinition? {
        if (locationsDefinitionCache[regionId] != null) {
            return locationsDefinitionCache[regionId]
        }
        return loadLocations(regionId)
    }

    /** Swap in a region's objects from elsewhere (import from another cache). */
    fun replace(regionId: Int, def: LocationsDefinition) { locationsDefinitionCache[regionId] = def }

    /** The edited copy if this region is loaded in the editor, else null. */
    fun cached(regionId: Int): LocationsDefinition? = locationsDefinitionCache[regionId]

    /** Read a region's objects without keeping them (for the world map). */
    fun peek(regionId: Int): LocationsDefinition? = locationsDefinitionCache[regionId] ?: loadLocations(regionId, false)

    private fun loadLocations(regionId: Int, keep: Boolean = true): LocationsDefinition? {
        val locationsDefinition = LocationsDefinition(regionId)

        val x = (regionId shr 8) and 0xFF
        val y = regionId and 0xFF
        // Try the XTEA key first; many RSPS caches store maps already decrypted, so fall back to no key.
        var landscape: ByteArray? = try {
            library.data(5, "l${x}_${y}", xtea.getKeys(regionId))
        } catch (e: Exception) {
            null
        }
        if (landscape == null) {
            try { library.index(5).unCache() } catch (e: Exception) {}
            landscape = try {
                library.data(5, "l${x}_${y}")
            } catch (e: Exception) {
                null
            }
            if (landscape != null) unencryptedRegions.add(regionId)
        }
        if (landscape == null) return null

        parseInto(locationsDefinition, landscape)
        if (keep) locationsDefinitionCache[regionId] = locationsDefinition
        return locationsDefinition
    }

    /** Objects from a raw, already-decrypted objects (l) file, e.g. a server's custom map .dat. */
    fun fromBytes(regionId: Int, bytes: ByteArray): LocationsDefinition {
        val d = LocationsDefinition(regionId)
        parseInto(d, bytes)
        return d
    }

    private fun parseInto(locationsDefinition: LocationsDefinition, landscape: ByteArray) {
        val buffer = ByteBuffer.wrap(landscape)

        var objId = -1
        var idOffset = buffer.readUnsignedIntSmartShortCompat()
        while (idOffset != 0) {
            objId += idOffset

            var position = 0
            var positionOffset = buffer.readUnsignedShortSmart()
            while (positionOffset != 0) {
                position += positionOffset - 1

                val localY = position and 0x3F
                val localX = position shr 6 and 0x3F
                val height = position shr 12 and 0x3

                val attributes = buffer.get().toInt()
                val type = attributes shr 2
                val orientation = attributes and 0x3

                locationsDefinition.locations.add(
                    Location(
                        objId,
                        type,
                        orientation,
                        localX,
                        localY,
                        height
                    )
                )

                positionOffset = buffer.readUnsignedShortSmart()
            }

            idOffset = buffer.readUnsignedIntSmartShortCompat()
        }

    }

    fun writeLocations(outputLibrary: CacheLibrary, locationsDefinition: LocationsDefinition): Boolean {
        val x = (locationsDefinition.regionId shr 8) and 0xFF
        val y = locationsDefinition.regionId and 0xFF

        // group locations by objectId
        val locMap = HashMap<Int, ArrayList<Location>>()
        for (l in locationsDefinition.locations) {
            if (locMap[l.objId] == null) {
                locMap[l.objId] = ArrayList()
            }
            locMap[l.objId]!!.add(l)
        }

        val outputBuffer = ByteBuffer.allocate(262144)

        var lastId = -1
        for (group in locMap.toSortedMap()) {
            val groupOffset = (group.key - lastId)
            outputBuffer.writeUnsignedIntSmartShortCompat(groupOffset)
            lastId = group.key

            // must sort by z then x or client crash!
            group.value.sortBy { (it.z shl 12) or (it.x shl 6) or it.y }

            var lastPos = 0
            for (l in group.value) {
                val position = ((l.z shl 12) or (l.x shl 6) or l.y)
                val positionOffset = position + 1 - lastPos
                outputBuffer.writeUnsignedShortSmart(positionOffset)

                val attributes = (l.type shl 2) or l.orientation
                outputBuffer.put(attributes.toByte())

                lastPos = position
                if (positionOffset == 0) {
                    lastPos = 0
                }
            }
            outputBuffer.put(0)
        }
        outputBuffer.put(0)

        outputBuffer.flip()
        val out = ByteArray(outputBuffer.limit())
        outputBuffer.get(out)

        if (unencryptedRegions.contains(locationsDefinition.regionId)) {
            outputLibrary.put(5, "l${x}_${y}", out)
        } else {
            outputLibrary.put(5, "l${x}_${y}", out, xtea.getKeys(locationsDefinition.regionId))
        }
        return outputLibrary.index(5).update()
    }
}