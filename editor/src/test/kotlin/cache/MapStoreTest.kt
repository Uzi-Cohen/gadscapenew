package cache

import cache.definitions.Location
import cache.definitions.LocationsDefinition
import cache.definitions.RegionDefinition
import cache.loaders.LocationsLoader
import cache.loaders.RegionLoader
import com.displee.cache.CacheLibrary
import com.displee.compress.CompressionType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Map squares in both layouts ([MapStore]) and the terrain format ([TerrainCodec]) on small caches made here: a
 * "build 241" one (25 archives, maps keyed by region id) and a "rev 211" one (22 archives, named map groups).
 * Real caches: editor-work/maps/MapHarness (NOTES.md there) reads every square of the stock 241, OpenRS2 2746,
 * merged GadScape and the user's 211 caches and re-encodes them byte for byte.
 */
class MapStoreTest {

    @TempDir
    lateinit var tmp: File

    private fun newCache(dir: File, archives: Int, protocol: Int, sizes: Boolean, namedMaps: Boolean): File {
        dir.mkdirs()
        File(dir, "main_file_cache.dat2").createNewFile()
        File(dir, "main_file_cache.idx255").createNewFile()
        val lib = CacheLibrary(dir.path)
        repeat(archives) { lib.createIndex(CompressionType.GZIP, protocol, 0, namedMaps && it == CacheIO.MAPS, false, sizes, false) }
        lib.close()
        return dir
    }

    private fun cache241(name: String = "c241") = newCache(File(tmp, name), 25, 7, true, false)
    private fun cache211(name: String = "c211") = newCache(File(tmp, name), 22, 6, false, true)

    private fun keys(vararg squares: Pair<Int, IntArray>): XteaManager {
        val dir = File(tmp, "keys${squares.size}").apply { mkdirs() }
        File(dir, "xteas.json").writeText("﻿[" + squares.joinToString(",") { (id, k) ->
            "{\"mapsquare\": $id, \"key\": [${k.joinToString(",")}]}" } + "]")
        return XteaManager(dir.path)
    }

    /** A square with a recognisable pattern: heights, an overlay, underlays, settings. */
    private fun square(regionId: Int, seed: Int = 1): RegionDefinition {
        val d = RegionDefinition(regionId)
        for (z in 0 until 4) for (x in 0 until 64) for (y in 0 until 64) {
            val t = RegionDefinition.Tile()
            if (z == 0) t.cacheHeight = (x + y + seed) % 50
            if ((x + y) % 7 == 0) { t.attrOpcode = 2 + (x % 4); t.overlayId = 300 + seed }
            if (z == 0) t.underlayId = 1 + (x * y + seed) % 90
            if (x == y) t.settings = 1
            d.tiles[z][x][y] = t
        }
        return d
    }

    private fun sameTiles(a: RegionDefinition, b: RegionDefinition) {
        for (z in 0 until 4) for (x in 0 until 64) for (y in 0 until 64) {
            val p = a.tiles[z][x][y]!!; val q = b.tiles[z][x][y]!!
            assertEquals(listOf(p.cacheHeight, p.attrOpcode, p.overlayId, p.underlayId, p.settings.toInt()),
                listOf(q.cacheHeight, q.attrOpcode, q.overlayId, q.underlayId, q.settings.toInt()), "tile $z $x $y")
        }
    }

    @Test
    fun layoutFollowsTheMapsArchive() {
        CacheIO.use(cache241().path) { assertEquals(MapStore.Layout.REGION, MapStore.layout(it)) }
        CacheIO.use(cache211().path) { assertEquals(MapStore.Layout.NAMED, MapStore.layout(it)) }
    }

    @Test
    fun terrainFormatBothRevisions() {
        val d = square(MapStore.regionId(50, 50))
        val t211 = TerrainCodec.encode(d, true, false)
        val t241 = TerrainCodec.encode(d, true, true)
        assertEquals(t211.size + 1, t241.size, "build 241 adds the flags byte")
        assertEquals(0, t241.last().toInt())
        sameTiles(d, TerrainCodec.decode(d.regionId, t211, true, false)!!)
        sameTiles(d, TerrainCodec.decode(d.regionId, t241, true, true)!!)
        assertArrayEquals(t241, TerrainCodec.forLayout(t211, true))
        assertArrayEquals(t211, TerrainCodec.forLayout(t241, false))
        assertArrayEquals(t211, TerrainCodec.levels(t241))
        // a raw file with one byte of padding (some 317-format clients leave one) still reads
        assertNotNull(TerrainCodec.decode(d.regionId, t211 + byteArrayOf(5), true, false))

        // the underwater level (flags bit 0): kept raw, written back as read, left out of rev 211
        val level = ByteArray(64 * 64 * 2) // every tile just "end of tile"
        val withWater = t211 + byteArrayOf(1) + level
        val w = TerrainCodec.decode(d.regionId, withWater, true, true)!!
        assertEquals(1, w.terrainFlags)
        assertArrayEquals(level, w.underwater)
        assertArrayEquals(withWater, TerrainCodec.encode(w, true, true))
        assertArrayEquals(t211, TerrainCodec.encode(w, true, false))
        // same from a raw file (not known to be build 241)
        assertArrayEquals(level, TerrainCodec.decode(d.regionId, withWater, true, false)!!.underwater)
    }

    @Test
    fun build241SquaresAreRegionGroupsWithEnvironment() {
        val dir = cache241()
        val near = MapStore.regionId(50, 50)
        val far = MapStore.regionId(10, 10)
        val deep = MapStore.regionId(120, 230)
        CacheIO.use(dir.path) { lib ->
            for ((id, mark) in listOf(near to 1, far to 2, deep to 3)) {
                lib.put(CacheIO.MAPS, id, 0, TerrainCodec.encode(square(id), true, true))
                lib.put(CacheIO.MAPS, id, 1, byteArrayOf(0))
                lib.put(CacheIO.MAPS, id, 2, byteArrayOf(mark.toByte(), 9))
                lib.put(CacheIO.MAPS, id, 3, byteArrayOf(0, 0))
                lib.put(CacheIO.MAPS, id, 4, byteArrayOf(0))
            }
            lib.index(CacheIO.MAPS).update()
        }
        val xtea = keys()
        val surface = MapStore.regionId(51, 50)
        val under = MapStore.regionId(40, 145)
        CacheIO.use(dir.path) { lib ->
            assertEquals(setOf(near, far, deep), MapStore.squareIds(lib))
            val regions = RegionLoader(lib)
            val locs = LocationsLoader(lib, xtea)
            // edit an existing square: terrain and objects change, its environment doesn't
            val n = regions.get(near)!!
            assertArrayEquals(byteArrayOf(1, 9), n.environment!![2])
            n.tiles[0][1][1]!!.cacheHeight = 77
            locs.writeLocations(lib, LocationsDefinition(near, arrayListOf(Location(4000, 10, 1, 1, 1, 0))))
            regions.writeRegion(lib, n)
            // new squares: objects first then terrain, as the editor exports
            locs.writeLocations(lib, LocationsDefinition(surface, arrayListOf(Location(70001, 22, 0, 5, 6, 0))))
            regions.writeRegion(lib, square(surface, 5))
            regions.writeRegion(lib, square(under, 6)) // terrain only
        }
        CacheIO.use(dir.path) { lib ->
            assertEquals(MapStore.Layout.REGION, MapStore.layout(lib))
            assertEquals(setOf(near, far, deep, surface, under), MapStore.squareIds(lib))
            val regions = RegionLoader(lib)
            val locs = LocationsLoader(lib, xtea)
            assertEquals(77, regions.get(near)!!.tiles[0][1][1]!!.cacheHeight)
            assertEquals(listOf(4000), locs.get(near)!!.locations.map { it.objId })
            assertArrayEquals(byteArrayOf(1, 9), MapStore.environment(lib, near)[2])
            for (id in listOf(surface, under)) {
                assertEquals(listOf(0, 1, 2, 3, 4), lib.index(CacheIO.MAPS).archive(id)!!.fileIds().toList(), "files of $id")
            }
            // nearest square of the same kind: 50,50 for a surface square; 120,230 (underground) over the nearer 50,50
            assertArrayEquals(byteArrayOf(1, 9), MapStore.environment(lib, surface)[2])
            assertArrayEquals(byteArrayOf(3, 9), MapStore.environment(lib, under)[2])
            assertEquals(listOf(70001), locs.get(surface)!!.locations.map { it.objId })
            assertTrue(locs.get(under)!!.locations.isEmpty())
            sameTiles(square(surface, 5), regions.get(surface)!!)
        }
    }

    @Test
    fun squareBroughtFromAnother241CacheKeepsItsEnvironment() {
        val dir = cache241()
        val id = MapStore.regionId(30, 30)
        CacheIO.use(dir.path) { lib ->
            val d = square(id).apply { environment = mapOf(2 to byteArrayOf(42), 3 to byteArrayOf(0, 0), 4 to byteArrayOf(0)) }
            RegionLoader(lib).writeRegion(lib, d)
            assertArrayEquals(byteArrayOf(42), MapStore.environment(lib, id)[2])
        }
        // with nothing to copy from, a new square gets the default surface environment (228 bytes)
        val other = MapStore.regionId(31, 30)
        CacheIO.use(cache241("c241b").path) { lib ->
            RegionLoader(lib).writeRegion(lib, square(other))
            assertEquals(228, MapStore.environment(lib, other)[2]!!.size)
        }
    }

    @Test
    fun worldAreaGroupIsNotASquare() {
        CacheIO.use(cache241().path) { lib ->
            lib.put(CacheIO.MAPS, MapStore.WORLD_AREA_GROUP, 0, byteArrayOf(1, 2, 3))
            lib.index(CacheIO.MAPS).update()
            assertTrue(MapStore.squareIds(lib).isEmpty())
            assertNull(RegionLoader(lib).get(MapStore.WORLD_AREA_GROUP))
            assertThrows(IllegalArgumentException::class.java) {
                MapStore.putTerrain(lib, MapStore.WORLD_AREA_GROUP, TerrainCodec.encode(square(1), true, true))
            }
        }
    }

    @Test
    fun rev211SquaresAreNamedGroupsAndKeepTheirEncryption() {
        val dir = cache211()
        val enc = MapStore.regionId(48, 60)
        val plain = MapStore.regionId(49, 60)
        val key = intArrayOf(11, 22, 33, 44)
        CacheIO.use(dir.path) { lib ->
            val objects = LocationsLoader(lib, keys()).encode(LocationsDefinition(0, arrayListOf(Location(1276, 10, 0, 3, 3, 0))))
            for (id in listOf(enc, plain)) lib.put(CacheIO.MAPS, MapStore.terrainName(id), TerrainCodec.encode(square(id), true, false))
            lib.put(CacheIO.MAPS, MapStore.locsName(enc), objects, key)
            lib.put(CacheIO.MAPS, MapStore.locsName(plain), objects)
            lib.index(CacheIO.MAPS).update()
        }
        val xtea = keys(enc to key, plain to key)
        CacheIO.use(dir.path) { lib ->
            assertEquals(setOf(enc, plain), MapStore.squareIds(lib))
            assertTrue(MapStore.locs(lib, enc, key)!!.encrypted)
            assertFalse(MapStore.locs(lib, plain, key)!!.encrypted)
            val regions = RegionLoader(lib)
            val locs = LocationsLoader(lib, xtea)
            for (id in listOf(enc, plain)) {
                locs.get(id)!!.locations.add(Location(1277, 10, 0, 4, 4, 0))
                locs.writeLocations(lib, locs.get(id)!!)
                regions.writeRegion(lib, regions.get(id)!!)
            }
            // a build 241 square brought in (flags + underwater): stored without them here
            val d = square(MapStore.regionId(50, 60)).apply { terrainFlags = 1; underwater = ByteArray(8192) }
            regions.writeRegion(lib, d)
        }
        CacheIO.use(dir.path) { lib ->
            assertEquals(MapStore.Layout.NAMED, MapStore.layout(lib))
            assertTrue(MapStore.locs(lib, enc, key)!!.encrypted, "still encrypted")
            assertFalse(MapStore.locs(lib, plain, key)!!.encrypted, "still unencrypted")
            assertEquals(listOf(1276, 1277), LocationsLoader(lib, xtea).get(enc)!!.locations.map { it.objId }.sorted())
            val t = MapStore.terrain(lib, MapStore.regionId(50, 60))!!
            assertEquals(TerrainCodec.levelsEnd(t), t.size, "no build 241 trailer in a rev 211 cache")
            assertTrue(MapStore.environment(lib, enc).isEmpty())
        }
    }

    @Test
    fun rev211GroupsAreReplacedHoweverTheyWereStored() {
        // cache-out often holds an unencrypted save of a square the input cache has encrypted (and the other way
        // round); the library reads an old group with the key being put and takes noise for its length
        val dir = cache211()
        val plain = MapStore.regionId(48, 60)
        val enc = MapStore.regionId(49, 60)
        val key = intArrayOf(11, 22, 33, 44)
        val before = byteArrayOf(0x0F, 0x01, 0x28, 0x00, 0x00) // object 14 at 0,0 level 0, type 10
        val after = byteArrayOf(0x10, 0x02, 0x29, 0x00, 0x00) // object 15 at 0,1, type 10 rotation 1
        CacheIO.use(dir.path) { lib ->
            lib.put(CacheIO.MAPS, MapStore.locsName(plain), before)
            lib.put(CacheIO.MAPS, MapStore.locsName(enc), before, key)
            lib.index(CacheIO.MAPS).update()
        }
        CacheIO.use(dir.path) { lib ->
            MapStore.putLocs(lib, plain, after, key) // now encrypted
            MapStore.putLocs(lib, enc, after, null) // now plain
            lib.index(CacheIO.MAPS).update()
        }
        CacheIO.use(dir.path) { lib ->
            val p = MapStore.locs(lib, plain, key)!!
            assertTrue(p.encrypted)
            assertArrayEquals(after, p.bytes)
            val e = MapStore.locs(lib, enc, key)!!
            assertFalse(e.encrypted)
            assertArrayEquals(after, e.bytes)
            assertNull(MapStore.locs(lib, plain, null), "encrypted, no key")
            assertNull(MapStore.locs(lib, plain, intArrayOf(1, 2, 3, 4)), "encrypted, wrong key")
            assertEquals(2, lib.index(CacheIO.MAPS).archiveIds().size)
        }
    }

    @Test
    fun objectsThatCantBeReadAreNotOverwritten() {
        val dir = cache211()
        val id = MapStore.regionId(48, 60)
        val key = intArrayOf(1, 2, 3, 4)
        val objects = byteArrayOf(0x0F, 0x01, 0x28, 0x00, 0x00) // object 14 at 0,0 level 0, type 10
        assertTrue(MapStore.isLocsFile(objects))
        CacheIO.use(dir.path) { lib ->
            lib.put(CacheIO.MAPS, MapStore.terrainName(id), TerrainCodec.encode(square(id), true, false))
            lib.put(CacheIO.MAPS, MapStore.locsName(id), objects, key)
            lib.index(CacheIO.MAPS).update()
        }
        CacheIO.use(dir.path) { lib ->
            val locs = LocationsLoader(lib, keys()) // no key for this square
            assertNull(locs.get(id))
            assertTrue(locs.objectsUnreadable(id), "reported, so a save can say its objects weren't saved")
            assertFalse(locs.writeLocations(lib, LocationsDefinition(id)))
            // objects imported from elsewhere replace it: written
            locs.replace(id, LocationsDefinition(id, arrayListOf(Location(15, 10, 0, 1, 1, 0))))
            assertFalse(locs.objectsUnreadable(id))
        }
        CacheIO.use(dir.path) { lib -> assertArrayEquals(objects, MapStore.locs(lib, id, key)!!.bytes) }
        assertFalse(MapStore.isLocsFile(byteArrayOf(0x0F, 0x41, 0x00)))
    }

    @Test
    fun rev211ObjectsPaddedWithZerosRead() {
        // the user's 211 cache: square 47,100 is a plain gzip group of 4,134 objects (9,047 bytes) followed by 93,353
        // zero bytes (an old packer stored its whole 100 KB buffer); the client and server stop at the final 0
        val dir = cache211()
        val id = MapStore.regionId(47, 100)
        val key = intArrayOf(9, 8, 7, 6) // xteas.json has a key for it, but the group isn't encrypted
        val placed = ArrayList<Location>()
        for (i in 0 until 400) placed.add(Location(1000 + (i % 37) * 3, 10, i and 3, (i * 7) % 64, (i * 13) % 64, i % 4))
        var objects = ByteArray(0)
        CacheIO.use(dir.path) { lib ->
            objects = LocationsLoader(lib, keys()).encode(LocationsDefinition(id, placed))
            val padded = objects.copyOf(102_400)
            assertTrue(MapStore.isLocsFile(padded))
            assertEquals(objects.size, MapStore.locsEnd(padded))
            assertEquals(objects.size, MapStore.locsEnd(objects))
            assertFalse(MapStore.isLocsFile(objects + byteArrayOf(0, 0, 5)), "anything but zeros after the final 0")
            lib.put(CacheIO.MAPS, MapStore.terrainName(id), TerrainCodec.encode(square(id), true, false))
            lib.put(CacheIO.MAPS, MapStore.locsName(id), padded)
            lib.index(CacheIO.MAPS).update()
        }
        val xtea = keys(id to key)
        CacheIO.use(dir.path) { lib ->
            for (k in listOf(key, null)) {
                val l = MapStore.locs(lib, id, k)!!
                assertFalse(l.encrypted, "plain group, key ${k?.toList()}")
                assertArrayEquals(objects, l.bytes, "read without the padding")
            }
            val locs = LocationsLoader(lib, xtea)
            val d = locs.get(id)!!
            assertEquals(placed.size, d.locations.size)
            assertFalse(locs.objectsUnreadable(id))
            d.locations.add(Location(1277, 10, 0, 2, 2, 0))
            assertTrue(locs.writeLocations(lib, d))
        }
        CacheIO.use(dir.path) { lib ->
            val l = MapStore.locs(lib, id, key)!!
            assertFalse(l.encrypted, "saved unencrypted, as it was read")
            assertEquals(placed.size + 1, LocationsLoader(lib, xtea).fromBytes(id, l.bytes).locations.size)
            assertEquals(MapStore.locsEnd(l.bytes), l.bytes.size, "saved as the exact list")
        }
    }

    @Test
    fun existingSquareTakesTheEnvironmentItIsImportedWith() {
        val dir = cache241()
        val id = MapStore.regionId(40, 40)
        val own = mapOf(2 to byteArrayOf(1, 1), 3 to byteArrayOf(0, 0), 4 to byteArrayOf(0))
        val imported = mapOf(2 to byteArrayOf(2, 2), 3 to byteArrayOf(0, 1), 4 to byteArrayOf(3))
        CacheIO.use(dir.path) { lib ->
            lib.put(CacheIO.MAPS, id, 0, TerrainCodec.encode(square(id), true, true))
            lib.put(CacheIO.MAPS, id, 1, byteArrayOf(0))
            for ((f, b) in own) lib.put(CacheIO.MAPS, id, f, b)
            lib.index(CacheIO.MAPS).update()
        }
        fun env(lib: CacheLibrary) = MapStore.environment(lib, id).mapValues { it.value.toList() }
        CacheIO.use(dir.path) { lib ->
            // read from this cache and saved: same environment, nothing to replace
            val d = RegionLoader(lib).get(id)!!
            assertNull(MapStore.putTerrain(lib, id, TerrainCodec.encode(d, true, true), d.environment))
            // a square without its own environment (rev 211, map files) or with an incomplete one keeps the cache's
            assertNull(MapStore.putTerrain(lib, id, TerrainCodec.encode(d, true, true), null))
            assertNull(MapStore.putTerrain(lib, id, TerrainCodec.encode(d, true, true), mapOf(2 to byteArrayOf(9))))
            lib.index(CacheIO.MAPS).update()
            assertEquals(own.mapValues { it.value.toList() }, env(lib))
            // imported from another build 241 cache: its lighting comes along
            val other = square(id, 3).apply { environment = imported }
            RegionLoader(lib).writeRegion(lib, other)
        }
        CacheIO.use(dir.path) { lib ->
            assertEquals(imported.mapValues { it.value.toList() }, env(lib))
            assertEquals(listOf(0, 1, 2, 3, 4), lib.index(CacheIO.MAPS).archive(id)!!.fileIds().toList())
            sameTiles(square(id, 3), RegionLoader(lib).get(id)!!)
        }
    }

    @Test
    fun xteasFileFormats() {
        val k = keys(MapStore.regionId(48, 60) to intArrayOf(5, 6, 7, 8))
        assertArrayEquals(intArrayOf(5, 6, 7, 8), k.getKeys(MapStore.regionId(48, 60)))
        val named = File(tmp, "named").apply { mkdirs() }
        File(named, "xteas.json").writeText("[{\"name\": \"l48_61\", \"key\": [1,2,3,4]}, {\"region\": 12850, \"keys\": [4,3,2,1]}]")
        val n = XteaManager(named.path)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), n.getKeys(MapStore.regionId(48, 61)))
        assertArrayEquals(intArrayOf(4, 3, 2, 1), n.getKeys(12850))
        // no xteas.json: fine next to a build 241 cache, an error for anything else (rev 211 needs its keys)
        val folder241 = File(tmp, "map241").apply { mkdirs() }
        newCache(File(folder241, "cache"), 25, 7, true, false)
        assertFalse(XteaManager(folder241.path).present)
        val folder211 = File(tmp, "map211").apply { mkdirs() }
        newCache(File(folder211, "cache"), 22, 6, false, true)
        assertThrows(java.io.FileNotFoundException::class.java) { XteaManager(folder211.path) }
    }
}
