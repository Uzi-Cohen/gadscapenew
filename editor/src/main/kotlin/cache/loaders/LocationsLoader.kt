package cache.loaders

import cache.MapStore
import cache.XteaManager
import cache.definitions.Location
import cache.definitions.LocationsDefinition
import com.displee.cache.CacheLibrary
import cache.utils.readUnsignedShortSmart
import cache.utils.writeUnsignedShortSmart
import cache.utils.readUnsignedIntSmartShortCompat
import cache.utils.writeUnsignedIntSmartShortCompat
import java.nio.ByteBuffer

/**
 * Objects (locs) of map squares, from either map layout (see [MapStore]): rev 211 group "l{x}_{y}", XTEA-encrypted
 * when Jagex made it, or file 1 of group regionId (build 241, never encrypted). The encoding is the same in both.
 */
class LocationsLoader(
    private val library: CacheLibrary,
    private val xtea: XteaManager,
    private val locationsDefinitionCache: HashMap<Int, LocationsDefinition> = HashMap()
) {
    /** Rev 211 squares whose objects group was read without the XTEA key: written back unencrypted. */
    private val unencryptedRegions = HashSet<Int>()

    /**
     * Squares whose objects group is there but couldn't be read (rev 211 without the right XTEA key). The view shows
     * them without objects; saving that would wipe the real ones, so they are only written once replaced (imported).
     */
    private val unreadableRegions = HashSet<Int>()

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

    @Synchronized private fun loadLocations(regionId: Int, keep: Boolean = true): LocationsDefinition? {
        // rev 211: the XTEA key first, then without (many RSPS caches, and the editor's own saves, aren't encrypted)
        val locs = MapStore.locs(library, regionId, xtea.getKeys(regionId))
        if (locs == null) {
            if (MapStore.hasLocs(library, regionId)) unreadableRegions.add(regionId)
            return null
        }
        if (!locs.encrypted && !MapStore.keyedByRegion(library)) unencryptedRegions.add(regionId)

        val locationsDefinition = LocationsDefinition(regionId)
        parseInto(locationsDefinition, locs.bytes)
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
                val height = position shr 12

                val attributes = buffer.get().toInt()
                val type = attributes shr 2
                val orientation = attributes and 0x3

                val location = Location(objId, type, orientation, localX, localY, height)
                // levels above 3 (build 241's underwater level) aren't shown; kept for writing back
                if (height > 3) locationsDefinition.hidden.add(location) else locationsDefinition.locations.add(location)

                positionOffset = buffer.readUnsignedShortSmart()
            }

            idOffset = buffer.readUnsignedIntSmartShortCompat()
        }

    }

    /** The objects file of [locationsDefinition] (same encoding in rev 211 and build 241). */
    fun encode(locationsDefinition: LocationsDefinition): ByteArray {
        // group locations by objectId
        val locMap = HashMap<Int, ArrayList<Location>>()
        for (l in locationsDefinition.locations + locationsDefinition.hidden) {
            if (locMap[l.objId] == null) {
                locMap[l.objId] = ArrayList()
            }
            locMap[l.objId]!!.add(l)
        }

        val outputBuffer = ByteBuffer.allocate(262144 + 8 * (locationsDefinition.locations.size + locationsDefinition.hidden.size))

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
        return out
    }

    /**
     * True when this square's objects group is in the cache but couldn't be read (rev 211 without a working XTEA key,
     * or a damaged file) and nothing replaced it (an import): the view shows it without objects, and [writeLocations]
     * doesn't save its objects (objects placed there are not kept), so a save can say so (utils.GadTools.exportLoaded).
     */
    @Synchronized fun objectsUnreadable(regionId: Int): Boolean =
        regionId in unreadableRegions && locationsDefinitionCache[regionId] == null

    /**
     * Writes the region's objects into [outputLibrary], in that cache's layout. Rev 211: encrypted with the square's
     * key unless it was read unencrypted; build 241: file 1 of group regionId, unencrypted. Returns false without
     * writing for a square whose objects couldn't be read ([objectsUnreadable]): writing the empty view would wipe them.
     */
    fun writeLocations(outputLibrary: CacheLibrary, locationsDefinition: LocationsDefinition): Boolean {
        val regionId = locationsDefinition.regionId
        if (objectsUnreadable(regionId)) {
            println("Objects of map square ${MapStore.describe(regionId)} not saved: the cache's objects file couldn't be read (missing or wrong XTEA key, or a damaged file), so it is left as it is")
            return false
        }
        val keys = if (MapStore.keyedByRegion(outputLibrary) || unencryptedRegions.contains(regionId)) null else xtea.getKeys(regionId)
        MapStore.putLocs(outputLibrary, regionId, encode(locationsDefinition), keys)
        return outputLibrary.index(5).update()
    }
}
