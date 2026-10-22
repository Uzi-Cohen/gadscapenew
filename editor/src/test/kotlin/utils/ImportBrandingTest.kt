package utils

import cache.CacheIO
import com.displee.cache.CacheLibrary
import com.displee.compress.CompressionType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Copying content between caches (items with their models, animations) into build 241 and rev 211 caches, with the
 * converter's id policy, and the cache rebrand, on small caches made here (like CacheLibraryTest). The old server
 * names in this file are put together from pieces, so the source stays free of them (see [noOldNamesInTheEditor]).
 */
class ImportBrandingTest {

    @TempDir
    lateinit var tmp: File

    private fun newCache(dir: File, archives: Int, protocol: Int, sizes: Boolean): File {
        dir.mkdirs()
        File(dir, "main_file_cache.dat2").createNewFile()
        File(dir, "main_file_cache.idx255").createNewFile()
        val lib = CacheLibrary(dir.path)
        repeat(archives) { lib.createIndex(CompressionType.GZIP, protocol, 0, false, false, sizes, false) }
        lib.close()
        return dir
    }

    private fun cache241(name: String = "c241") = newCache(File(tmp, name), 25, 7, true)
    private fun cache211(name: String = "c211") = newCache(File(tmp, name), 22, 6, false)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ---------------- the old names, from pieces ----------------
    private val nr = "Ne" + "ar-Re" + "ality"
    private val os = "OS" + "NR"
    private val rp = "Ro" + "at P" + "kz"
    private val rs = "Rune" + "Spawn"

    /** An FF FE model of 0 vertices and 0 faces without the 241 face-depth flag (23-byte header only). */
    private fun oldFeModel(): ByteArray = ByteArray(23).also { it[21] = -1; it[22] = -2 }

    // ---------------- ModelTail ----------------

    @Test
    fun modelTailAddsTheFlagOnce() {
        val old = oldFeModel()
        assertEquals(ModelTail.State.NEEDS_TAIL, ModelTail.check(old).state)
        val fixed = ModelTail.forBuild241(old)
        assertEquals(24, fixed.size)
        assertEquals(0, fixed[0].toInt())
        assertEquals(ModelTail.State.OK, ModelTail.check(fixed).state)
        assertSame(fixed, ModelTail.forBuild241(fixed), "a model that has it is left alone")
        // old-format models are the same in both formats; rev 211 caches take everything as it is
        val other = bytes(1, 2, 3, 4)
        assertSame(other, ModelTail.forBuild241(other))
        assertSame(old, ModelTail.forRevision(old, ConfigRev.R211))
        // FF FD: no field and no flag -> 2 bytes
        val fd = ByteArray(26).also { it[24] = -1; it[25] = -3 }
        assertEquals(2, ModelTail.check(fd).insert)
        assertEquals(ModelTail.State.OK, ModelTail.check(ModelTail.forBuild241(fd)).state)
    }

    // ---------------- items ----------------

    private fun putItem(lib: CacheLibrary, it: ItemFull) { lib.put(2, 10, it.id, it.encode(ConfigRev.of(lib))); lib.index(2).update() }

    private fun whip(id: Int, model: Int) = ItemFull(id).apply {
        name = "Abyssal whip"; inventoryModel = model; maleModel0 = model; femaleModel0 = model; wearPos1 = 3; cost = 120001
    }

    @Test
    fun itemsFromA211CacheBecome241ItemsAndModelsGetTheTail() {
        val src = cache211("src211")
        CacheIO.use(src.path) { lib -> putItem(lib, whip(4151, 5412)); lib.put(7, 5412, 0, oldFeModel()); lib.index(7).update() }
        val dst = cache241()
        CacheIO.use(dst.path) { lib -> putItem(lib, ItemFull(0).apply { name = "Dwarf remains" }) }
        val other = CacheIO.openReadOnly(src.path)
        try {
            CacheIO.use(dst.path) { lib ->
                val r = ItemCopy.copyInto(lib, ItemCopy.CacheSource(other), listOf(4151), keepIds = true, server = null)
                assertEquals(listOf(4151), r.newIds, r.message)
                // written as a 241 item: model opcode 44 with a 4-byte id, never the 211 opcode 1
                val raw = lib.data(2, 10, 4151)!!
                assertEquals(44, raw[0].toInt())
                val back = ItemFull.decodeAs(4151, raw, ConfigRev.R241)
                assertTrue(back.readOk)
                assertEquals(5412, back.inventoryModel)
                // the model kept its id (free here) and got the face-depth flag
                assertEquals(ModelTail.State.OK, ModelTail.check(lib.data(7, 5412, 0)!!).state)
                assertTrue(r.message.contains("rev 211"), r.message)
                // the same again: already here, nothing new
                val again = ItemCopy.copyInto(lib, ItemCopy.CacheSource(other), listOf(4151), keepIds = true, server = null)
                assertEquals(emptyList<Int>(), again.newIds)
                assertEquals(listOf(4151), again.alreadyHere)
            }
            // another server's items (ids not kept): after the last item, models in GadScape's range
            CacheIO.use(dst.path) { lib ->
                val r = ItemCopy.copyInto(lib, ItemCopy.CacheSource(other), listOf(4151), keepIds = false, server = null)
                assertEquals(listOf(4152), r.newIds, r.message)
                assertEquals(5412, ItemStore.read(lib, 4152)!!.inventoryModel, "the same model at the same id is reused")
            }
        } finally { other.close() }
        // nothing was written into the source
        CacheIO.use(src.path) { lib -> assertArrayEquals(oldFeModel(), lib.data(7, 5412, 0)) }
    }

    @Test
    fun aDifferentItemOnTheIdKeepsItAndModelsMoveToTheReservedRange() {
        val src = cache241("src241")
        val live = whip(4151, 70000)
        CacheIO.use(src.path) { lib -> putItem(lib, live); lib.put(7, 70000, 0, bytes(9, 9, 9)); lib.index(7).update() }
        val dst = cache241()
        CacheIO.use(dst.path) { lib ->
            putItem(lib, ItemFull(4151).apply { name = "GadScape thing"; inventoryModel = 70000 })
            lib.put(7, 70000, 0, bytes(1, 2, 3)); lib.index(7).update()
        }
        val other = CacheIO.openReadOnly(src.path)
        try {
            CacheIO.use(dst.path) { lib ->
                val r = ItemCopy.copyInto(lib, ItemCopy.CacheSource(other), listOf(4151), keepIds = true, server = null)
                assertEquals(listOf(4152), r.newIds, r.message)
                assertEquals("GadScape thing", ItemStore.read(lib, 4151)!!.name, "never written over")
                val copied = ItemStore.read(lib, 4152)!!
                // new models go to the highest free id the 241 client can fetch (65534 down)
                assertEquals(65534, copied.inventoryModel, "model 70000 is taken by another model here")
                assertArrayEquals(bytes(9, 9, 9), lib.data(7, 65534, 0))
                assertArrayEquals(bytes(1, 2, 3), lib.data(7, 70000, 0))
            }
        } finally { other.close() }
    }

    @Test
    fun liveItemsKeepTheir241SettingsIn241AndLoseThemIn211() {
        // a live 241 item (4-byte model id; live models are never above 65535, the 241 client can't fetch those) and
        // setting 15 (not tradeable)
        val liveItem = whip(30000, 60001).apply { notTradeable = true }.encode(ConfigRev.R241)
        val read = LiveOsrs.read(30000, liveItem)
        assertTrue(read.item.notTradeable)
        val source = object : ItemCopy.Source {
            override fun item(id: Int) = if (id == 30000) read.item else null
            override fun model(id: Int) = if (id == 60001) bytes(7, 7) else null
            override fun format() = ConfigRev.R241
        }
        CacheIO.use(cache241().path) { lib ->
            val r = ItemCopy.copyInto(lib, source, listOf(30000), keepIds = true, server = null)
            assertEquals(listOf(30000), r.newIds, r.message)
            assertArrayEquals(liveItem, lib.data(2, 10, 30000), "the live bytes, unchanged")
            assertArrayEquals(bytes(7, 7), lib.data(7, 60001, 0), "live model id kept")
        }
        CacheIO.use(cache211().path) { lib ->
            val r = ItemCopy.copyInto(lib, source, listOf(30000), keepIds = true, server = null)
            val it = ItemFull.decodeAs(30000, lib.data(2, 10, 30000)!!, ConfigRev.R211)
            assertTrue(it.readOk)
            assertTrue(it.inventoryModel <= 65535, "211 model ids are 2 bytes")
            assertFalse(it.notTradeable)
            assertTrue(r.message.contains("left out"), r.message)
        }
    }

    /** One item ([id]) using models [inv] (inventory), [male] and [female], in a source cache with those models. */
    private fun sourceWith(id: Int, inv: Int, male: Int, female: Int, models: Map<Int, ByteArray>): File {
        val src = cache241("src-$id-$inv")
        CacheIO.use(src.path) { lib ->
            putItem(lib, ItemFull(id).apply { name = "Live thing"; inventoryModel = inv; maleModel0 = male; femaleModel0 = female; wearPos1 = 3 })
            for ((m, b) in models) lib.put(7, m, 0, b)
            lib.index(7).update()
        }
        return src
    }

    @Test
    fun aModelKeptAtItsIdIsNeverHandedOutAgainInTheSameCopy() {
        // review T1: rev 211, models 0-99 taken, so new ids start at 100; inventory model 101 is free and kept, the
        // male and female models (5 and 6) are taken by other models and get new ids: 100, then 102 (never 101)
        val src = sourceWith(4151, 101, 5, 6, mapOf(101 to bytes(1, 0, 1), 5 to bytes(5, 5, 5), 6 to bytes(6, 6, 6)))
        val dst = cache211()
        CacheIO.use(dst.path) { lib ->
            putItem(lib, ItemFull(0).apply { name = "Dwarf remains" })
            for (m in 0 until 100) lib.put(7, m, 0, bytes(0, m, 0))
            lib.index(7).update()
        }
        // build 241: new ids from 65534 down; inventory model 65534 is free and kept, so the male model gets 65533
        val src241 = sourceWith(4151, 65534, 5, 6, mapOf(65534 to bytes(1, 0, 1), 5 to bytes(5, 5, 5), 6 to bytes(6, 6, 6)))
        val dst241 = cache241("dst241")
        CacheIO.use(dst241.path) { lib -> for (m in 0 until 100) lib.put(7, m, 0, bytes(0, m, 0)); lib.index(7).update() }
        for ((from, to) in listOf(src to dst, src241 to dst241)) {
            val other = CacheIO.openReadOnly(from.path)
            try {
                CacheIO.use(to.path) { lib ->
                    val r = ItemCopy.copyInto(lib, ItemCopy.CacheSource(other), listOf(4151), keepIds = true, server = null)
                    assertEquals(listOf(4151), r.newIds, r.message)
                    val c = ItemStore.read(lib, 4151)!!
                    val ids = listOf(c.inventoryModel, c.maleModel0, c.femaleModel0)
                    assertEquals(3, ids.distinct().size, "three different models: $ids")
                    assertArrayEquals(bytes(1, 0, 1), lib.data(7, c.inventoryModel, 0), "the kept model is still there")
                    assertArrayEquals(bytes(5, 5, 5), lib.data(7, c.maleModel0, 0))
                    assertArrayEquals(bytes(6, 6, 6), lib.data(7, c.femaleModel0, 0))
                    for (m in 0 until 100) assertArrayEquals(bytes(0, m, 0), lib.data(7, m, 0), "model $m is untouched")
                    if (!CacheIO.is241(lib)) assertEquals(listOf(101, 100, 102), ids)
                    else assertEquals(listOf(65534, 65533, 65532), ids)
                }
            } finally { other.close() }
        }
    }

    @Test
    fun a211CachesOwnCopyOfALiveModelIsUsedAsItIs() {
        // GadScape's 211 cache holds most live models without the build 241 face-depth ending: the same model
        val live = ModelTail.forBuild241(oldFeModel())
        val src = sourceWith(30000, 500, 500, 500, mapOf(500 to live))
        val dst = cache211()
        CacheIO.use(dst.path) { lib -> lib.put(7, 500, 0, oldFeModel()); lib.index(7).update() }
        val dst241 = cache241("dst241")
        CacheIO.use(dst241.path) { lib -> lib.put(7, 500, 0, oldFeModel()); lib.index(7).update() }
        val other = CacheIO.openReadOnly(src.path)
        try {
            for (keep in listOf(true, false)) CacheIO.use(dst.path) { lib ->
                val r = ItemCopy.copyInto(lib, ItemCopy.CacheSource(other), listOf(30000), keepIds = keep, server = null)
                assertTrue(r.message.contains("with 0 new model(s)"), r.message)
                assertEquals(500, ItemStore.read(lib, r.newIds.single())!!.inventoryModel)
                assertArrayEquals(oldFeModel(), lib.data(7, 500, 0), "the 211 copy stays as it is")
                assertEquals(1, lib.index(7).archiveIds().size)
            }
            // a build 241 cache can't use a model without the ending (the 241 client reads past it): a new copy
            CacheIO.use(dst241.path) { lib ->
                val r = ItemCopy.copyInto(lib, ItemCopy.CacheSource(other), listOf(30000), keepIds = true, server = null)
                val m = ItemStore.read(lib, r.newIds.single())!!.inventoryModel
                assertNotEquals(500, m)
                assertArrayEquals(live, lib.data(7, m, 0))
                assertArrayEquals(oldFeModel(), lib.data(7, 500, 0))
            }
        } finally { other.close() }
    }

    // ---------------- animations ----------------

    // 2 frames of frame set 5 (files 1 and 2), delays 3 and 4, then the end (same layout in 211 and 241)
    private val seq = bytes(1, 0, 2, 0, 3, 0, 4, 0, 1, 0, 2, 0, 5, 0, 5, 0)
    private fun frame(skeleton: Int, v: Int) = bytes(skeleton shr 8, skeleton, 0, v)

    private fun importInto(lib: CacheLibrary, keepIds: Boolean) = CacheEdits.importInto(lib, mapOf(808 to seq),
        mapOf(5 to mapOf(1 to frame(7, 1), 2 to frame(7, 2))), mapOf(7 to bytes(0, 1, 2)), emptyMap(), ConfigRev.R241, null, keepIds)

    @Test
    fun liveAnimationsKeepFreeIdsAndNeverReplaceFrameSets() {
        CacheIO.use(cache241().path) { lib ->
            val r = importInto(lib, keepIds = true)
            assertEquals(listOf(808), r.newSeqIds, r.message)
            assertArrayEquals(seq, lib.data(2, 12, 808))
            assertArrayEquals(frame(7, 1), lib.data(0, 5, 1))
            assertArrayEquals(bytes(0, 1, 2), lib.data(1, 7, 0))
            // again: already here, nothing new
            val again = importInto(lib, keepIds = true)
            assertEquals(listOf(808), again.newSeqIds)
            assertTrue(again.message.contains("Already here"), again.message)
            assertEquals(1, lib.index(0).archiveIds().size)
        }
        // the cache has its own (different) frame set 5 and animation 808: both stay, the import gets new ids
        CacheIO.use(cache241("other").path) { lib ->
            lib.put(0, 5, 0, frame(3, 9)); lib.put(2, 12, 808, bytes(0)); lib.index(0).update(); lib.index(2).update()
            val r = importInto(lib, keepIds = true)
            val id = r.newSeqIds.single()
            assertTrue(id in 40000..49999, "animation $id")
            assertArrayEquals(frame(3, 9), lib.data(0, 5, 0), "the base frame set is untouched")
            assertArrayEquals(bytes(0), lib.data(2, 12, 808))
            val s = SeqDef.decode(id, lib.data(2, 12, id)!!, ConfigRev.R241)
            assertTrue(s.frameIds.all { (it ushr 16) in 30000..32767 }, s.frameIds.joinToString { (it ushr 16).toString() })
        }
        // another server's animations: everything new goes to the reserved ranges
        CacheIO.use(cache241("custom").path) { lib ->
            val id = importInto(lib, keepIds = false).newSeqIds.single()
            assertTrue(id in 40000..49999)
            assertNull(lib.data(2, 12, 808))
            assertFalse(lib.index(0).archiveIds().contains(5))
        }
    }

    // a skeletal animation (build 241 layout): keyframe set 6 file 0, range 0-10; its keyframes use skeleton 7
    private val skeletalSeq = bytes(13, 0, 6, 0, 0, 15, 0, 0, 0, 10, 0)
    private val keyframes = bytes(1, 0, 7, 0, 0, 0, 10, 42)

    private fun importBoth(lib: CacheLibrary, keepIds: Boolean) = CacheEdits.importInto(lib, mapOf(808 to seq, 900 to skeletalSeq),
        mapOf(5 to mapOf(1 to frame(7, 1), 2 to frame(7, 2))), mapOf(7 to bytes(0, 1, 2)), mapOf(6 to mapOf(0 to keyframes)),
        ConfigRev.R241, null, keepIds)

    private fun groups(lib: CacheLibrary, archive: Int) = if (lib.exists(archive)) lib.index(archive).archiveIds().sorted() else emptyList()

    @Test
    fun importingTheSameAnimationsAgainReusesTheCopiesItMade() {
        // review T4: the cache has its own skeleton 7, frame set 5 and animations 808 / 900, so the import makes
        // copies in GadScape's ranges; importing the same again must use those, not make more
        for ((name, dir) in listOf("211" to cache211(), "241" to cache241())) for (keep in listOf(true, false)) {
            val d = File(tmp, "$name-$keep"); dir.copyRecursively(d)
            CacheIO.use(d.path) { lib ->
                val keyArchive = Keyframes.archive(lib)
                lib.put(1, 7, 0, bytes(9, 9, 9)); lib.put(0, 5, 0, frame(3, 9)); lib.put(keyArchive, 6, 0, bytes(1, 0, 3, 0, 0, 0, 5))
                // someone's own frame set in the range: never taken for a copy
                lib.put(0, 30000, 0, frame(3, 8))
                lib.put(2, 12, 808, bytes(0)); lib.put(2, 12, 900, bytes(2, 0, 1, 0))
                lib.index(0).update(); lib.index(1).update(); lib.index(2).update(); lib.index(keyArchive).update()
                val first = importBoth(lib, keep)
                val after = Triple(groups(lib, 0), groups(lib, 1), groups(lib, keyArchive))
                val s1 = first.newSeqIds.map { SeqDef.decode(it, lib.data(2, 12, it)!!, CacheIO.revision(lib)) }
                assertTrue(s1.all { it.readOk }, first.message)
                val set = s1[0].frameIds.map { it ushr 16 }.distinct().single()
                assertTrue(set in 30001..32767, "frame set $set ($name, keep $keep): ${first.message}")
                val key = s1[1].skeletalModel ushr 16
                assertTrue(key in 20000..29999, "keyframe set $key")
                val skel = lib.data(0, set, 1)!!.let { ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255) }
                assertTrue(skel in 30000..32767, "skeleton $skel")
                assertArrayEquals(bytes(0, 1, 2), lib.data(1, skel, 0))
                assertEquals(skel, Keyframes.skeletonOf(lib.data(keyArchive, key, 0)!!))

                val again = importBoth(lib, keep)
                assertEquals(after, Triple(groups(lib, 0), groups(lib, 1), groups(lib, keyArchive)), "nothing new: ${again.message}")
                assertTrue(again.message.contains("used again"), again.message)
                val s2 = again.newSeqIds.map { SeqDef.decode(it, lib.data(2, 12, it)!!, CacheIO.revision(lib)) }
                assertEquals(s1.map { it.frameIds.toList() }, s2.map { it.frameIds.toList() })
                assertEquals(s1.map { it.skeletalModel }, s2.map { it.skeletalModel })
                // live content (keep ids): the animations themselves are the earlier copies too
                if (keep) assertEquals(first.newSeqIds, again.newSeqIds, again.message)
                else assertTrue(again.newSeqIds.none { it in first.newSeqIds })
                // the cache's own content is untouched
                assertArrayEquals(bytes(9, 9, 9), lib.data(1, 7, 0)); assertArrayEquals(frame(3, 9), lib.data(0, 5, 0))
                assertArrayEquals(frame(3, 8), lib.data(0, 30000, 0)); assertArrayEquals(bytes(0), lib.data(2, 12, 808))
            }
        }
    }

    @Test
    fun animationsInANewerLayoutThan241AreRefused() {
        assertEquals(242, CacheEdits.bundleFormat("# x\nformat 242\nseq 1\n"))
        CacheIO.use(cache241().path) { lib ->
            val e = runCatching {
                CacheEdits.importInto(lib, mapOf(808 to seq), emptyMap(), emptyMap(), emptyMap(), 242, null, keepIds = true)
            }.exceptionOrNull()
            assertTrue(e is IllegalArgumentException && e.message!!.contains("build 242"), e.toString())
            assertNull(lib.data(2, 12, 808))
        }
        // live content: only from a cache of build 241 or older, and the import says when the game has moved on
        val list = """[
            {"id": 2740, "game": "oldschool", "environment": "live", "language": "en", "builds": [{"major": 241, "minor": null}]},
            {"id": 2746, "game": "oldschool", "environment": "live", "language": "en", "builds": [{"major": 241, "minor": null}]},
            {"id": 2700, "game": "oldschool", "environment": "live", "language": "en", "builds": [{"major": 240, "minor": null}]},
            {"id": 2760, "game": "oldschool", "environment": "live", "language": "en", "builds": [{"major": 242, "minor": null}]},
            {"id": 2770, "game": "oldschool", "environment": "live", "language": "en", "builds": []},
            {"id": 2780, "game": "oldschool", "environment": "beta", "language": "en", "builds": [{"major": 241, "minor": null}]},
            {"id": 2790, "game": "runescape", "environment": "live", "language": "en", "builds": [{"major": 941, "minor": null}]}
        ]"""
        val l = LiveOsrs.pickLatest(list)
        assertEquals(2746, l.cacheId); assertEquals(241, l.build); assertEquals(242, l.newestBuild)
        assertEquals(2700, LiveOsrs.pickLatest(list, 240).cacheId)
        val cat = LiveOsrs.Catalog(l.cacheId, l.build, mapOf(1 to ItemFull(1).apply { name = "Thing" }.encode(ConfigRev.R241), 2 to bytes(255, 255)), l.newestBuild)
        assertEquals(1, cat.entries.size); assertEquals(1, cat.unreadable)
        assertTrue(cat.describe().contains("build 242") && cat.describe().contains("1 item(s) can't be read"), cat.describe())
        // a newer cache is refused before anything is downloaded or written
        val newer = LiveOsrs.Catalog(2760, 242, emptyMap())
        assertTrue(runCatching { LiveAnims.bundle(newer, listOf(1), File(tmp, "b.zip")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { LiveOsrs.Source(newer, null) }.exceptionOrNull() is IllegalArgumentException)
    }

    // ---------------- rebrand ----------------

    private fun z(s: String) = s.toByteArray(Charsets.ISO_8859_1) + 0
    private val trailer = bytes(0, 21, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    @Test
    fun rebrandSwapsOldNamesInStringsOnly() {
        for (dir in listOf(cache241(), cache211())) {
            CacheIO.use(dir.path) { lib ->
                val rev = ConfigRev.of(lib)
                lib.put(2, 10, 32080, ItemFull(32080).apply { name = "$os Mystery Box"; inventoryModel = 1 }.encode(rev))
                lib.put(2, 10, 1351, ItemFull(1351).apply { name = "Seth Groats"; inventoryModel = 2 }.encode(rev))
                lib.put(2, 34, 10327, bytes(249, 1, 1, 0, 0, 7) + z("find 10 Snowmen around $nr"))
                lib.put(3, 5103, 5, bytes(3, 0, 1) + z("$rp Blackjack") + bytes(9))
                lib.put(3, 5101, 39, bytes(3) + z("$rs Credit Packages") + bytes(1))
                // a script: empty name, a string push, then the trailer
                lib.put(12, 10504, 0, bytes(0, 0, 3) + z("$nr Teleports") + trailer)
                // not inside a 0-terminated string: left alone
                lib.put(12, 9, 0, bytes(1) + nr.toByteArray() + bytes(1, 2, 3))
                lib.put(2, 10, 19492, ItemFull(19492).apply { name = "Zenyte bracelet"; inventoryModel = 3 }.encode(rev))
                // an NPC and an object (name only: the same bytes in both formats)
                lib.put(2, 9, 3000, bytes(2) + z("$nr Guide") + bytes(0))
                lib.put(2, 6, 40000, bytes(2) + z("$rs portal") + bytes(0))
                lib.index(2).update(); lib.index(3).update(); lib.index(12).update()
            }
            val hits = CacheRebrand.scan(dir) {}
            assertEquals(setOf("2/10/32080", "2/34/10327", "3/5103/5", "3/5101/39", "12/10504/0", "2/9/3000", "2/6/40000"),
                hits.map { "${it.index}/${it.archive}/${it.file}" }.toSet(), hits.joinToString("\n"))
            assertEquals(7, CacheRebrand.apply(dir, hits))
            assertEquals(emptyList<CacheRebrand.Hit>(), CacheRebrand.scan(dir) {})
            CacheIO.use(dir.path) { lib ->
                val rev = ConfigRev.of(lib)
                assertEquals("GadScape Mystery Box", ItemFull.decodeAs(32080, lib.data(2, 10, 32080)!!, rev).name)
                assertEquals("Seth Groats", ItemFull.decodeAs(1351, lib.data(2, 10, 1351)!!, rev).name)
                assertArrayEquals(bytes(249, 1, 1, 0, 0, 7) + z("find 10 Snowmen around GadScape"), lib.data(2, 34, 10327))
                assertArrayEquals(bytes(3, 0, 1) + z("GadScape Blackjack") + bytes(9), lib.data(3, 5103, 5))
                assertArrayEquals(bytes(3) + z("GadScape Credit Packages") + bytes(1), lib.data(3, 5101, 39))
                assertArrayEquals(bytes(0, 0, 3) + z("GadScape Teleports") + trailer, lib.data(12, 10504, 0), "the trailer is unchanged")
                assertArrayEquals(bytes(1) + nr.toByteArray() + bytes(1, 2, 3), lib.data(12, 9, 0))
                assertEquals("GadScape Guide", NpcDefs.decodeAs(3000, lib.data(2, 9, 3000)!!, rev).name)
                assertEquals("GadScape portal", ObjDefs.decode(40000, lib.data(2, 6, 40000)!!, rev).name)
            }
        }
    }

    @Test
    fun rebrandCasesAndAddresses() {
        fun s(t: String) = String(CacheRebrand.swap(z(t)) ?: z(t), Charsets.ISO_8859_1).trimEnd('\u0000')
        assertEquals("Visit gadscape.com now", s("Visit www.${nr.lowercase()}.org now"))
        assertEquals("GADSCAPE", s(nr.uppercase()))
        assertEquals("gadscape", s(nr.lowercase().replace("-", "")))
        assertEquals("GadScape Mystery Box", s("$os Mystery Box"))
        assertEquals("PR$os", s("PR$os"), "only as a word of its own")
        assertEquals("GadScape and GadScape", s("$rp and $rs"))
        assertNull(CacheRebrand.swap(z("Nothing to see")))
    }

    // ---------------- the editor's own text ----------------

    @Test
    fun noOldNamesInTheEditor() {
        val re = Regex("ne" + "ar[-_ .]?re" + "ality|ro" + "at ?p" + "kz|o" + "snr|ru" + "ne ?sp" + "awn", RegexOption.IGNORE_CASE)
        val roots = listOf(File("src"), File("README.md"), File("AI-BRIDGE.md"), File("build.gradle.kts")).filter { it.exists() }
        if (roots.isEmpty()) return // not run from the editor folder
        val hits = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "md", "kts", "fxml", "css", "txt", "json") }.toList() }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (re.containsMatchIn(l)) "${f.path}:${i + 1}: ${l.trim()}" else null } }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun cachesStayWhatTheyAre() {
        // sanity: the generated caches are recognised as the right formats (the rest of the tests rely on it)
        CacheIO.use(cache241("x241").path) { assertEquals(CacheIO.REV_241, CacheIO.revision(it)) }
        CacheIO.use(cache211("x211").path) { assertNotEquals(CacheIO.REV_241, CacheIO.revision(it)) }
    }
}
