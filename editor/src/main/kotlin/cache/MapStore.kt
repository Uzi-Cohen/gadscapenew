package cache

import cache.definitions.RegionDefinition
import com.displee.cache.CacheLibrary
import com.displee.compress.CompressionType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.TreeSet
import java.util.zip.GZIPInputStream

/**
 * Map squares (64 x 64 tile regions) in the maps archive (5), in both layouts the editor works with. Everything that
 * reads or writes map files goes through here, so the rest of the editor only deals in region ids
 * (`regionId = (x shl 8) or y`, x and y being the square's coordinates = world tile / 64).
 *
 *  - [Layout.NAMED], rev 211 (GadScape's old cache, any OSRS cache before rev 237): two named groups per square,
 *    "m{x}_{y}" (terrain, file 0) and "l{x}_{y}" (objects, file 0). Jagex encrypts the objects group with the square's
 *    XTEA key (xteas.json); the editor has always saved its own edits unencrypted.
 *  - [Layout.REGION], build 241 (rev 237 and later): one unnamed, unencrypted group per square with the region id as
 *    its id. File 0 = terrain, 1 = objects, 2-4 = environment ("e" lighting, fog, sky; "t"; "w"). Group 98,199
 *    ([WORLD_AREA_GROUP]) holds world-area definitions, not a square. Terrain ends with one flags byte more (see
 *    [TerrainCodec]).
 *
 * The layout comes from the maps archive itself (a named table = [Layout.NAMED]), like the server's
 * mgi.tools.jagcached.MapFiles; an empty archive follows the cache's revision ([CacheIO.revision]).
 *
 * New squares written into a [Layout.REGION] cache get an (empty) objects file and environment files 2-4: the
 * environment the square came with (imported from another build 241 cache), else the one of the nearest square of the
 * same kind (surface, instances, underground), else Jagex's default surface lighting ([environmentFor]). A square
 * that is already there keeps its files 2-4 unless the square written over it brings a different environment of its
 * own (imported from another build 241 cache): then that replaces them, like its terrain and objects.
 */
object MapStore {
    const val TERRAIN_FILE = 0
    const val LOCS_FILE = 1
    /** Environment files of a build 241 square: 2 = lighting, fog and sky ("e"), 3 = "t", 4 = "w". */
    val ENVIRONMENT_FILES = 2..4
    /** Group (98 shl 8) or 199 of a region-keyed maps archive holds world-area definitions, not a map square. */
    const val WORLD_AREA_GROUP = (98 shl 8) or 199

    private val MAPS = CacheIO.MAPS

    enum class Layout {
        /** Rev 211: groups "m{x}_{y}" and "l{x}_{y}", objects XTEA-encrypted by Jagex. */
        NAMED,
        /** Build 241: one group per square, id = region id, files 0 terrain, 1 objects, 2-4 environment, no XTEA. */
        REGION
    }

    /** An objects (locs) file and whether it was stored encrypted (only ever true in a [Layout.NAMED] cache). */
    class Locs(val bytes: ByteArray, val encrypted: Boolean)

    fun regionId(x: Int, y: Int): Int = ((x and 0xFF) shl 8) or (y and 0xFF)
    fun terrainName(regionId: Int): String = "m${(regionId shr 8) and 0xFF}_${regionId and 0xFF}"
    fun locsName(regionId: Int): String = "l${(regionId shr 8) and 0xFF}_${regionId and 0xFF}"

    /** "50,53 (12853)" for messages. */
    fun describe(regionId: Int): String = "${(regionId shr 8) and 0xFF},${regionId and 0xFF} ($regionId)"

    /** Which layout the maps archive of [lib] uses (see the class comment). */
    fun layout(lib: CacheLibrary): Layout {
        if (lib.is317()) return Layout.NAMED
        if (lib.exists(MAPS)) {
            val maps = lib.index(MAPS)
            if (maps.isNamed()) return Layout.NAMED
            if (maps.archiveIds().isNotEmpty()) return Layout.REGION
        }
        return if (CacheIO.is241(lib)) Layout.REGION else Layout.NAMED
    }

    fun keyedByRegion(lib: CacheLibrary): Boolean = layout(lib) == Layout.REGION

    // ------------------------------------------------------------------ reading

    /** Whether [lib] has terrain for this square, without reading any map data. */
    fun hasSquare(lib: CacheLibrary, regionId: Int): Boolean {
        if (!lib.exists(MAPS)) return false
        val maps = lib.index(MAPS)
        return if (keyedByRegion(lib)) regionId != WORLD_AREA_GROUP && maps.contains(regionId)
        else maps.contains(terrainName(regionId))
    }

    /** Whether [lib] has an objects file (group) for this square, without reading any map data. */
    fun hasLocs(lib: CacheLibrary, regionId: Int): Boolean {
        if (!lib.exists(MAPS)) return false
        return if (keyedByRegion(lib)) hasSquare(lib, regionId) else lib.index(MAPS).contains(locsName(regionId))
    }

    /** Every square [lib] has terrain for, sorted. Reads only the reference table. */
    fun squareIds(lib: CacheLibrary): Set<Int> {
        val out = TreeSet<Int>()
        if (!lib.exists(MAPS)) return out
        val maps = lib.index(MAPS)
        if (keyedByRegion(lib)) {
            for (id in maps.archiveIds()) if (id != WORLD_AREA_GROUP && id in 0..0xFFFF) out.add(id)
        } else {
            val names = HashSet<Int>()
            for (a in maps.archives()) names.add(a.hashName)
            for (x in 0 until 256) for (y in 0 until 256) {
                val id = regionId(x, y)
                if (maps.toHash(terrainName(id)) in names) out.add(id)
            }
        }
        return out
    }

    /** The terrain file of a square as stored (build 241: with its flags byte), or null when there is none. */
    fun terrain(lib: CacheLibrary, regionId: Int): ByteArray? {
        if (!lib.exists(MAPS)) return null
        return try {
            if (keyedByRegion(lib)) {
                if (regionId == WORLD_AREA_GROUP) null else lib.data(MAPS, regionId, TERRAIN_FILE)
            } else lib.data(MAPS, terrainName(regionId))
        } catch (e: Exception) {
            null
        }?.takeIf { it.isNotEmpty() }
    }

    /**
     * The objects file of a square, or null when there is none or (rev 211) it can't be decrypted. [keys] is the
     * square's XTEA key, tried first (Jagex's squares); then the group is read unencrypted (squares the editor saved).
     * Rev 211 objects files padded with zeros after their final 0 ([isLocsFile]) are returned without the padding, so
     * the next save writes the exact list.
     */
    fun locs(lib: CacheLibrary, regionId: Int, keys: IntArray?): Locs? {
        if (!lib.exists(MAPS)) return null
        if (keyedByRegion(lib)) {
            if (regionId == WORLD_AREA_GROUP) return null
            val b = try { lib.data(MAPS, regionId, LOCS_FILE) } catch (e: Exception) { null } ?: return null
            return Locs(b, false)
        }
        val name = locsName(regionId)
        val maps = lib.index(MAPS)
        val id = maps.archiveId(name)
        if (id == -1) return null
        // a group put in this session is returned as put whatever key is asked for: report how it was put, so an
        // unencrypted group isn't taken for an encrypted one and saved encrypted
        val loaded = maps.archive(id, direct = true)
        if (loaded != null && (loaded.read || loaded.new) && loaded.containsData()) {
            val b = loaded.file(0)?.data?.let { objectsOf(it) }
            if (b != null) return Locs(b, loaded.xtea()?.any { it != 0 } == true)
        }
        // read here rather than through the cache library, which (1) also decrypts the group's 2-byte version trailer,
        // garbling every group whose length leaves 6 or 7 bytes after the last whole 8-byte block (about 1 in 4 of
        // Jagex's), and (2) given the wrong key, or no key for an encrypted group, takes noise for the decompressed
        // length and allocates that (up to 2 GB: OutOfMemoryError on editor-saved squares that have a key in
        // xteas.json). A read only counts if the bytes are a whole objects file: an uncompressed group decrypted with
        // the wrong key (or read plain while encrypted) comes back as noise without any error.
        val sector = try { maps.readArchiveSector(id)?.data } catch (e: Exception) { null } ?: return null
        if (keys != null && keys.any { it != 0 }) {
            val b = try { openContainer(lib, id, sector, keys) } catch (e: Exception) { null }
            objectsOf(b)?.let { return Locs(it, true) }
        }
        val b = try { openContainer(lib, id, sector, null) } catch (e: Exception) { null }
        return objectsOf(b)?.let { Locs(it, false) }
    }

    /** [b] up to the end of its objects list (zero padding after it dropped), or null if it isn't an objects file. */
    private fun objectsOf(b: ByteArray?): ByteArray? {
        if (b == null) return null
        val end = locsEnd(b)
        return when {
            end < 0 -> null
            end == b.size -> b
            else -> b.copyOf(end)
        }
    }

    /** Far above any real map file (Jagex's are tens of KB); a larger length read from a container is noise. */
    private const val MAX_MAP_FILE = 8 shl 20

    /**
     * The data of single-file group [groupId] from its container [sector] (compression byte, length, [uncompressed
     * length,] data, optional 2-byte version), XTEA-decrypted with [keys] from byte 5 to the end of the container (not
     * the version), as the client does. Null when the lengths are out of range (wrong key, or none for an encrypted
     * group). Uncompressed and gzip (every map group) are read here; the rare bzip2 / LZMA container goes to the
     * library once its length is known to be sane.
     */
    private fun openContainer(lib: CacheLibrary, groupId: Int, sector: ByteArray, keys: IntArray?): ByteArray? {
        if (sector.size < 5) return null
        val type = sector[0].toInt() and 0xFF
        val length = ByteBuffer.wrap(sector, 1, 4).int
        if (type > 3 || length < 0 || length > MAX_MAP_FILE) return null
        val end = 5 + (if (type == 0) 0 else 4) + length
        if (end > sector.size) return null
        val b = sector.copyOf(end)
        if (keys != null) xteaDecipher(b, 5, end, keys)
        if (type == 0) return b.copyOfRange(5, end)
        val size = ByteBuffer.wrap(b, 5, 4).int
        if (size < 0 || size > MAX_MAP_FILE) return null
        if (type != 2) return lib.data(MAPS, groupId, 0, keys)
        if (length < 2 || b[9] != 0x1F.toByte() || b[10] != 0x8B.toByte()) return null
        val out = GZIPInputStream(ByteArrayInputStream(b, 9, length)).use { it.readNBytes(size + 1) }
        return if (out.size == size) out else null
    }

    private fun xteaDecipher(b: ByteArray, start: Int, end: Int, key: IntArray) {
        val buf = ByteBuffer.wrap(b)
        val delta = -0x61c88647 // 0x9E3779B9
        for (block in 0 until (end - start) / 8) {
            val off = start + block * 8
            var v0 = buf.getInt(off)
            var v1 = buf.getInt(off + 4)
            var sum = delta * 32
            repeat(32) {
                v1 -= (((v0 shl 4) xor (v0 ushr 5)) + v0) xor (sum + key[(sum ushr 11) and 3])
                sum -= delta
                v0 -= (((v1 shl 4) xor (v1 ushr 5)) + v1) xor (sum + key[sum and 3])
            }
            buf.putInt(off, v0)
            buf.putInt(off + 4, v1)
        }
    }

    /**
     * True if [b] reads as an objects (locs) file: object id deltas (smart, 32767 continues), per object position deltas
     * (smart) each followed by a type/rotation byte, 0 ending each list, and after the final 0 nothing or only zero
     * bytes (an old packer stored some rev 211 groups with the rest of its 100 KB buffer: the client and server stop
     * at the final 0; square 47,100 of the user's 211 cache is 9,047 bytes of objects and 93,353 zeros).
     */
    fun isLocsFile(b: ByteArray): Boolean = locsEnd(b) >= 0

    /** Where the objects list of [b] ends (just after its final 0), or -1 if [b] isn't an objects file ([isLocsFile]). */
    fun locsEnd(b: ByteArray): Int {
        var p = 0
        fun smart(): Int {
            if (p >= b.size) throw IndexOutOfBoundsException()
            val v = b[p].toInt() and 0xFF
            if (v < 128) { p++; return v }
            if (p + 1 >= b.size) throw IndexOutOfBoundsException()
            val r = (((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)) - 32768
            p += 2
            return r
        }
        return try {
            while (true) {
                var delta = 0
                var part = smart()
                while (part == 32767) { delta += part; part = smart() }
                delta += part
                if (delta == 0) break
                var position = 0
                while (true) {
                    val pd = smart()
                    if (pd == 0) break
                    position += pd - 1
                    if (position > 0x7FFF) return -1
                    if (p >= b.size) return -1
                    p++ // type and rotation
                }
            }
            val end = p
            while (p < b.size) if (b[p++] != 0.toByte()) return -1
            end
        } catch (e: IndexOutOfBoundsException) {
            -1
        }
    }

    /** Environment files 2-4 of a build 241 square (empty map for a rev 211 cache or a square without them). */
    fun environment(lib: CacheLibrary, regionId: Int): Map<Int, ByteArray> {
        if (!keyedByRegion(lib) || regionId == WORLD_AREA_GROUP || !lib.exists(MAPS)) return emptyMap()
        val group = try { lib.index(MAPS).archive(regionId) } catch (e: Exception) { null } ?: return emptyMap()
        val out = LinkedHashMap<Int, ByteArray>()
        for (f in ENVIRONMENT_FILES) group.file(f)?.data?.let { out[f] = it }
        return out
    }

    /**
     * Drops the decompressed data the library keeps for one square (both groups in a rev 211 cache), so reading a
     * whole map doesn't hold it all in memory. Squares with unsaved changes are left alone.
     */
    fun release(lib: CacheLibrary, regionId: Int) {
        if (!lib.exists(MAPS)) return
        if (keyedByRegion(lib)) forget(lib, regionId)
        else {
            val maps = lib.index(MAPS)
            maps.archiveId(terrainName(regionId)).takeIf { it != -1 }?.let { forget(lib, it) }
            maps.archiveId(locsName(regionId)).takeIf { it != -1 }?.let { forget(lib, it) }
        }
    }

    private fun forget(lib: CacheLibrary, groupId: Int) {
        val a = lib.index(MAPS).archive(groupId, direct = true) ?: return
        if (!a.flagged() && !a.new) a.restore()
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes a square's terrain ([terrain] must already be in [lib]'s format: [TerrainCodec.encode] /
     * [TerrainCodec.forLayout]). In a build 241 cache a new square also gets an empty objects file and environment files
     * 2-4: [environment] when given (the square's own, from the cache it came from), else a neighbour's; an existing
     * square gets [environment] when it is complete and differs from the square's files 2-4. The caller runs
     * `lib.index(5).update()`.
     * @return the square the environment files were copied from ([regionId] = [environment]), -1 for the built-in
     * default, null when files 2-4 weren't changed
     */
    fun putTerrain(lib: CacheLibrary, regionId: Int, terrain: ByteArray, environment: Map<Int, ByteArray>? = null): Int? {
        if (keyedByRegion(lib)) {
            checkSquare(regionId)
            lib.put(MAPS, regionId, TERRAIN_FILE, terrain)
            return completeSquare(lib, regionId, environment)
        }
        replaceUnread(lib, terrainName(regionId))
        lib.put(MAPS, terrainName(regionId), terrain)
        return null
    }

    /**
     * Writes a square's objects file. [keys] encrypts it, rev 211 only (pass null to store it unencrypted, as the
     * editor does for squares it read unencrypted); build 241 never encrypts map files. The caller runs
     * `lib.index(5).update()`.
     */
    fun putLocs(lib: CacheLibrary, regionId: Int, locs: ByteArray, keys: IntArray? = null) {
        if (keyedByRegion(lib)) {
            checkSquare(regionId)
            lib.put(MAPS, regionId, LOCS_FILE, locs)
            completeSquare(lib, regionId, null)
            return
        }
        val k = keys?.takeIf { key -> key.any { it != 0 } }
        replaceUnread(lib, locsName(regionId))
        if (k != null) lib.put(MAPS, locsName(regionId), locs, k) else lib.put(MAPS, locsName(regionId), locs)
    }

    /**
     * Rev 211: lets a put replace the whole one-file group [name] without the library reading it first. The library
     * reads an existing group with the key being put; when the group isn't stored that way (cache-out holding an
     * unencrypted save of a square Jagex encrypted, an imported square there's no key for) it takes noise for the
     * length (see [locs]). Its only file is replaced by the put, so there is nothing in it to keep.
     */
    private fun replaceUnread(lib: CacheLibrary, name: String) {
        val maps = lib.index(MAPS)
        val id = maps.archiveId(name)
        if (id == -1) return
        val group = maps.archive(id, direct = true) ?: return
        if (group.read || group.new || group.flagged()) return
        if (!group.fileIds().contentEquals(intArrayOf(0))) return // not a map group's layout: let the library read it
        group.read = true
        if (group.compressionType == null) group.compressionType = CompressionType.GZIP
    }

    private fun checkSquare(regionId: Int) {
        require(regionId in 0..0xFFFF) { "Not a map square: $regionId" }
        require(regionId != WORLD_AREA_GROUP) { "Square 98,199 ($WORLD_AREA_GROUP) holds the world-area definitions in a build 241 cache; it can't hold a map" }
    }

    /**
     * Makes a build 241 square complete once it has terrain: an empty objects file when it has none, and environment
     * files 2-4 when they are missing. When [environment] has all of files 2-4 (the square was read from a build 241
     * cache) they replace the group's if they differ: a square imported from another build 241 cache brings its
     * lighting along like its terrain and objects. For a square read from the cache the editor saves into (cache-out
     * is that cache or a copy of it) they are the same bytes, so nothing changes. Returns where the environment came
     * from (see [putTerrain]).
     */
    private fun completeSquare(lib: CacheLibrary, regionId: Int, environment: Map<Int, ByteArray>?): Int? {
        val group = lib.index(MAPS).archive(regionId) ?: return null
        if (group.file(TERRAIN_FILE)?.data == null) return null
        if (group.file(LOCS_FILE)?.data == null) group.add(LOCS_FILE, byteArrayOf(0))
        val own = environment?.takeIf { e -> ENVIRONMENT_FILES.all { e[it] != null } }
        if (ENVIRONMENT_FILES.all { group.file(it)?.data != null }) {
            if (own == null || ENVIRONMENT_FILES.all { own.getValue(it).contentEquals(group.file(it)!!.data) }) return null
            for (f in ENVIRONMENT_FILES) group.add(f, own.getValue(f).clone())
            println("Map square ${describe(regionId)}: environment files 2-4 replaced with the ones of the cache it came from")
            return regionId
        }
        val (from, files) = if (own != null) Pair(regionId, own) else environmentFor(lib, regionId)
        for (f in ENVIRONMENT_FILES) if (group.file(f)?.data == null) group.add(f, files.getValue(f).clone())
        println("Map square ${describe(regionId)} is new in this cache: environment files 2-4 " + when (from) {
            regionId -> "from the cache it came from"
            -1 -> "set to the default surface lighting (no other square to copy)"
            else -> "copied from the nearest square, ${describe(from)}"
        })
        return from
    }

    /** 0 = surface (y 0-64), 1 = instances and minigames (65-139), 2 = underground (140-255): the world map's layers. */
    fun band(regionId: Int): Int {
        val y = regionId and 0xFF
        return when {
            y <= 64 -> 0
            y <= 139 -> 1
            else -> 2
        }
    }

    /**
     * Environment files 2-4 for a new square [regionId] of [lib]: the nearest square (same [band] first, then any)
     * that has all three, by distance between square coordinates (ties: lowest id); -1 and Jagex's most common
     * surface environment when there is none.
     */
    fun environmentFor(lib: CacheLibrary, regionId: Int): Pair<Int, Map<Int, ByteArray>> {
        val rx = (regionId shr 8) and 0xFF
        val ry = regionId and 0xFF
        val myBand = band(regionId)
        val candidates = (if (lib.exists(MAPS)) lib.index(MAPS).archiveIds().toList() else emptyList())
            .filter { it != regionId && it != WORLD_AREA_GROUP && it in 0..0xFFFF }
            .sortedWith(compareBy<Int>({ if (band(it) == myBand) 0 else 1 },
                { val dx = ((it shr 8) and 0xFF) - rx; val dy = (it and 0xFF) - ry; dx * dx + dy * dy }, { it }))
        for (id in candidates.take(64)) {
            val env = environment(lib, id)
            if (ENVIRONMENT_FILES.all { env[it] != null }) return Pair(id, env)
        }
        return Pair(-1, DEFAULT_ENVIRONMENT.mapValues { it.value.clone() })
    }

    /**
     * Files 2-4 of most build 241 surface squares (1,114 of 2,938 in the stock cache, Edgeville among them): file 2 is
     * the lighting/fog/sky definition, files 3 and 4 are all zeros in every square.
     */
    private val DEFAULT_ENVIRONMENT: Map<Int, ByteArray> by lazy {
        mapOf(
            2 to hex(
                "01ffffba76ffceffc4ffce0059011900003f8ccccd406000003f800000ff6eaaff0000010000000042000000410000000000000001391d4952" +
                "45b3b000410000003fc000003f8000003f8000003f8000003b8080813b8080813b8080813e9696973f1595963f8000004009999a4009999a00" +
                "0000003f8ccccd408000003e4ccccd3f00000042200000fffd9500ff01010100033c23d70a402000003f000000404000003f00000000000000" +
                "0000000000000000000000000000000000000000ffffffff00ffff00000000ffff000000003e8000000000000000000000000000007b5ebb01"),
            3 to byteArrayOf(0, 0),
            4 to byteArrayOf(0)
        )
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte() }

    // ------------------------------------------------------------------ comparing

    /**
     * Crc of each square's terrain and objects as the reference table lists them (no data read): `regionId ->
     * (terrain crc, objects crc)`. In a build 241 cache both are the crc of the whole group (files 0-4).
     */
    fun tableCrcs(lib: CacheLibrary): Map<Int, Pair<Int?, Int?>> {
        val out = HashMap<Int, Pair<Int?, Int?>>()
        if (!lib.exists(MAPS)) return out
        val maps = lib.index(MAPS)
        if (keyedByRegion(lib)) {
            for (a in maps.archives()) if (a.id != WORLD_AREA_GROUP && a.id in 0..0xFFFF) out[a.id] = Pair(a.crc, a.crc)
        } else {
            val byHash = HashMap<Int, Int>()
            for (a in maps.archives()) byHash[a.hashName] = a.crc
            for (x in 0 until 256) for (y in 0 until 256) {
                val id = regionId(x, y)
                val m = byHash[maps.toHash(terrainName(id))]
                val l = byHash[maps.toHash(locsName(id))]
                if (m != null || l != null) out[id] = Pair(m, l)
            }
        }
        return out
    }
}

/**
 * The terrain file of a map square: for each of 4 levels, 64 x 64 tiles of attribute opcodes (0 = end of tile, 1 =
 * height byte follows and ends the tile, 2-49 = overlay (id follows), 50-81 = settings, 82+ = underlay). OSRS caches
 * since rev ~209 (GadScape's 211 and build 241 alike) use 2-byte opcodes and overlay ids ("wide"); older caches use
 * single bytes.
 *
 * Build 241 adds one flags byte after level 3; bit 0 means a fifth, underwater level follows in the same encoding
 * (square 64,73 has one). [RegionDefinition] keeps both ([RegionDefinition.terrainFlags],
 * [RegionDefinition.underwater], raw) so they are written back unchanged; a rev 211 cache gets neither.
 */
object TerrainCodec {
    private const val Z = RegionDefinition.Z
    private const val SIZE = RegionDefinition.X * RegionDefinition.Y

    /**
     * Reads terrain. [flagsByte]: the bytes are from a build 241 (region-keyed) cache, so whatever follows level 3 is
     * the flags byte; otherwise one or two trailing bytes are taken as padding (some 317-format private-server clients
     * leave one), unless they are a valid build 241 underwater level.
     * @return null when the bytes don't read as terrain in this tile format
     */
    fun decode(regionId: Int, bytes: ByteArray, wide: Boolean, flagsByte: Boolean): RegionDefinition? {
        val def = RegionDefinition(regionId)
        val buf = ByteBuffer.wrap(bytes)
        try {
            for (z in 0 until Z) {
                for (x in 0 until RegionDefinition.X) for (y in 0 until RegionDefinition.Y) {
                    def.tiles[z][x][y] = readTile(buf, wide)
                }
            }
        } catch (e: Exception) {
            return null
        }
        val rest = buf.remaining()
        if (rest == 0) return def
        if (wide) {
            val flags = bytes[buf.position()].toInt() and 0xFF
            if (flags and 1 != 0 && rest > 1) {
                val start = buf.position() + 1
                val level = ByteBuffer.wrap(bytes, start, bytes.size - start)
                val exact = try {
                    repeat(SIZE) { readTile(level, true) }
                    !level.hasRemaining()
                } catch (e: Exception) { false }
                if (exact) {
                    def.terrainFlags = flags
                    def.underwater = bytes.copyOfRange(start, bytes.size)
                    return def
                }
            }
            if (flagsByte && rest == 1) {
                def.terrainFlags = flags and 1.inv()
                return def
            }
        }
        return if (rest <= 2) def else null
    }

    private fun readTile(buf: ByteBuffer, wide: Boolean): RegionDefinition.Tile {
        val tile = RegionDefinition.Tile()
        while (true) {
            val attribute = if (wide) buf.short.toInt() and 0xFFFF else buf.get().toInt() and 0xFF
            if (attribute == 0) {
                break
            } else if (attribute == 1) {
                val height = buf.get().toInt() and 0xFF
                tile.cacheHeight = height
                tile.height = height
                break
            } else if (attribute <= 49) {
                tile.attrOpcode = attribute
                tile.overlayId = if (wide) buf.short.toInt() and 0xFFFF else buf.get().toInt() and 0xFF
                tile.overlayPath = ((attribute - 2) / 4).toByte()
                tile.overlayRotation = (attribute - 2 and 3).toByte()
            } else if (attribute <= 81) {
                tile.settings = (attribute - 49).toByte()
            } else {
                tile.underlayId = attribute - 81
            }
        }
        return tile
    }

    /**
     * Writes terrain. [regionLayout] = for a build 241 cache: always wide, with the flags byte (and the underwater
     * level the square had). Otherwise [wide] picks the tile format and nothing follows level 3.
     */
    fun encode(def: RegionDefinition, wide: Boolean, regionLayout: Boolean): ByteArray {
        val w = wide || regionLayout
        val out = ByteArrayOutputStream(64 * 1024)
        fun op(v: Int) {
            if (w) { out.write(v shr 8); out.write(v) } else out.write(v)
        }
        for (z in 0 until Z) {
            for (x in 0 until RegionDefinition.X) {
                for (y in 0 until RegionDefinition.Y) {
                    val tile: RegionDefinition.Tile = def.tiles[z][x][y]!!
                    if (tile.attrOpcode > 0) {
                        op(tile.attrOpcode)
                        op(tile.overlayId)
                    }
                    if (tile.settings > 0) {
                        op(tile.settings + 49)
                    }
                    if (tile.underlayId > 0) {
                        op(tile.underlayId + 81)
                    }
                    val cacheHeight = tile.cacheHeight
                    if (cacheHeight != null) {
                        op(1)
                        // the stored value: upper planes are relative to the plane below, so tile.height (absolute)
                        // would stack every floor on export
                        out.write(cacheHeight.coerceIn(0, 255))
                    } else {
                        op(0)
                    }
                }
            }
        }
        if (regionLayout) {
            val underwater = def.underwater
            if (underwater != null) {
                out.write((def.terrainFlags or 1) and 0xFF)
                out.write(underwater)
            } else {
                out.write(def.terrainFlags and 1.inv() and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /**
     * Raw terrain from either layout made fit for [regionLayout] (build 241: flags byte added when missing; rev 211:
     * the build 241 trailer dropped). Bytes that don't read as wide terrain are returned unchanged.
     */
    fun forLayout(bytes: ByteArray, regionLayout: Boolean): ByteArray {
        val end = levelsEnd(bytes)
        if (end < 0) return bytes
        return if (regionLayout) {
            if (end == bytes.size) bytes.copyOf(bytes.size + 1) else bytes
        } else if (end < bytes.size) bytes.copyOf(end) else bytes
    }

    /** Terrain without anything after level 3 (for comparing squares across layouts); unchanged if unreadable. */
    fun levels(bytes: ByteArray): ByteArray {
        val end = levelsEnd(bytes)
        return if (end < 0 || end == bytes.size) bytes else bytes.copyOf(end)
    }

    /** Where level 3 of wide terrain ends, or -1 if the bytes don't read as wide terrain. */
    fun levelsEnd(bytes: ByteArray): Int {
        val buf = ByteBuffer.wrap(bytes)
        return try {
            repeat(Z * SIZE) { readTile(buf, true) }
            buf.position()
        } catch (e: Exception) {
            -1
        }
    }
}
