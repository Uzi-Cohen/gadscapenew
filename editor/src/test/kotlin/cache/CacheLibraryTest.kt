package cache

import com.displee.cache.CacheLibrary
import com.displee.compress.CompressionType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * The forked cache library and the editor's id allocation, on small caches made here: a "build 241" one (archives
 * 0-24, reference tables with flag 0x4 like Jagex's, protocol 7) and a "rev 211" one (archives 0-21, protocol 6, no
 * flags). See ReferenceCachesTest for the same checks on real caches.
 */
class CacheLibraryTest {

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

    private fun cache241() = newCache(File(tmp, "c241"), 25, 7, true)
    private fun cache211() = newCache(File(tmp, "c211"), 22, 6, false)

    @Test
    fun detectsTheRevision() {
        CacheIO.use(cache241().path) { assertEquals(CacheIO.REV_241, CacheIO.revision(it)); assertEquals(22, CacheIO.keyframeArchive(it)) }
        CacheIO.use(cache211().path) { assertEquals(CacheIO.REV_211, CacheIO.revision(it)); assertEquals(0, CacheIO.keyframeArchive(it)) }
    }

    @Test
    fun writesSizesAndReadsThemBack() {
        val dir = cache241()
        val data = HashMap<Int, ByteArray>()
        CacheIO.use(dir.path) { lib ->
            val ids = IdAllocator(lib, IdKind.MODEL)
            repeat(3) { val id = ids.next(); data[id] = Random(id).nextBytes(1000 + id % 7); lib.put(CacheIO.MODELS, id, 0, data[id]!!) }
            lib.index(CacheIO.MODELS).update()
        }
        // the highest free ids the 241 client can fetch (16-bit JS5 group ids), from the top down
        assertEquals(setOf(65534, 65533, 65532), data.keys)
        val lib = CacheIO.openReadOnly(dir.path)
        try {
            for ((id, b) in data) assertArrayEquals(b, lib.data(CacheIO.MODELS, id, 0), "model $id")
            val index = lib.index(CacheIO.MODELS)
            assertTrue(index.hasFlag4())
            for (a in index.archives()) {
                assertEquals(data[a.id]!!.size, a.flag4Value2, "uncompressed size of $a")
                assertTrue(a.flag4Value1 > 0, "compressed size of $a")
            }
            val r = CacheCheck.groups(lib, CacheCheck.tables(lib))
            assertTrue(r.ok, r.toString())
            assertEquals(r.tables, r.tablesSame)
        } finally { lib.close() }
    }

    @Test
    fun protocol6TableBecomes7ForBigIds() {
        val dir = cache211()
        CacheIO.use(dir.path) { lib ->
            assertEquals(6, lib.index(CacheIO.MODELS).version)
            lib.put(CacheIO.MODELS, 70000, 0, byteArrayOf(1, 2, 3))
            lib.index(CacheIO.MODELS).update()
        }
        CacheIO.use(dir.path) { lib ->
            assertEquals(7, lib.index(CacheIO.MODELS).version)
            assertArrayEquals(byteArrayOf(1, 2, 3), lib.data(CacheIO.MODELS, 70000, 0))
            assertTrue(CacheCheck.groups(lib, CacheCheck.tables(lib)).ok)
        }
    }

    @Test
    fun readOnlyCacheCantBeWritten() {
        val dir = cache241()
        val lib = CacheIO.openReadOnly(dir.path)
        try {
            lib.put(CacheIO.MODELS, 5, 0, byteArrayOf(1))
            // the library reports a failed write as an IllegalStateException ("Unable to write data ...")
            assertThrows(Exception::class.java) { lib.index(CacheIO.MODELS).update() }
        } finally { lib.close() }
        CacheIO.use(dir.path) { assertTrue(it.index(CacheIO.MODELS).archiveIds().isEmpty()) }
    }

    @Test
    fun newContentGoesInGadScapesRanges241() {
        val dir = cache241()
        CacheIO.use(dir.path) { lib ->
            // ids the base game uses near the ranges are never handed out; taken ids in the range are skipped
            lib.put(CacheIO.FRAMES, 4478, 0, byteArrayOf(0, 1))
            lib.put(CacheIO.FRAMES, 30000, 0, byteArrayOf(0, 1))
            lib.index(CacheIO.FRAMES).update()
            assertEquals(30001, IdAllocator(lib, IdKind.FRAME_SET).next())
            assertEquals(20000, IdAllocator(lib, IdKind.KEYFRAME_SET).next())
            // models: the highest free id up to 65534, never above (the client fetches groups with 16-bit ids)
            lib.put(CacheIO.MODELS, 65534, 0, byteArrayOf(1))
            lib.put(CacheIO.MODELS, 65535, 0, byteArrayOf(1))
            lib.put(CacheIO.MODELS, 62050, 0, byteArrayOf(1))
            lib.index(CacheIO.MODELS).update()
            val models = IdAllocator(lib, IdKind.MODEL, alsoTaken = listOf(65532))
            assertEquals(listOf(65533, 65531, 65530), List(3) { models.next() })
            assertEquals(65535, CacheIO.maxModelId(lib))
            assertEquals(30000, IdAllocator(lib, IdKind.FRAMEMAP).next())
            assertEquals(20000, IdAllocator(lib, IdKind.SPRITE).next())
            assertEquals(47000, IdAllocator(lib, IdKind.SCRIPT).next())
        }
        // no configs archive groups here: config kinds start at the bottom of their range too
        CacheIO.use(dir.path) { lib ->
            assertEquals(40000, IdAllocator(lib, IdKind.SEQ).next())
            // objects: 65534 down to 64000 (the game protocol carries object ids in 16 bits)
            val locs = IdAllocator(lib, IdKind.LOC)
            assertEquals(listOf(65534, 65533), List(2) { locs.next() })
            val full = IdAllocator(lib, IdKind.LOC, alsoTaken = (64001..65534).toList())
            assertEquals(64000, full.next())
            assertThrows(IllegalArgumentException::class.java) { full.next() } // never below 64000 (the 241 base's objects)
            val ids = IdAllocator(lib, IdKind.FRAME_SET, alsoTaken = (30001..32766).toList())
            assertEquals(32767, ids.next())
            assertThrows(IllegalArgumentException::class.java) { ids.next() } // never 32768 and up: the client crashes
        }
    }

    @Test
    fun rev211KeepsTwoByteModelIds() {
        val dir = cache211()
        CacheIO.use(dir.path) { lib ->
            for (i in 0 until 120) lib.put(CacheIO.MODELS, i, 0, byteArrayOf(1))
            lib.put(CacheIO.MODELS, 150, 0, byteArrayOf(1))
            lib.index(CacheIO.MODELS).update()
            // the old rule: the first run of 50 free ids (120-149 is only 30), never above 65535
            assertEquals(151, IdAllocator(lib, IdKind.MODEL).next())
            assertEquals(30000, IdAllocator(lib, IdKind.FRAME_SET).next())
            assertEquals(65535, CacheIO.maxModelId(lib))
            // nothing free but 65535 itself: "none" in 16-bit model fields, never handed out
            val ids = IdAllocator(lib, IdKind.MODEL, alsoTaken = (120..149).toList() + (151..65534).toList())
            assertThrows(IllegalArgumentException::class.java) { ids.next() }
            // objects keep the old rule in 211 too, capped at 65534
            assertEquals(0, IdAllocator(lib, IdKind.LOC).next())
            assertEquals(65485, IdAllocator(lib, IdKind.LOC, alsoTaken = (0..65484).toList()).next())
            assertThrows(IllegalArgumentException::class.java) { IdAllocator(lib, IdKind.LOC, alsoTaken = (0..65534).toList()).next() }
        }
    }

    @Test
    fun brokenArchiveIsAnErrorNotAMissingArchive() {
        val dir = cache241()
        // cut the reference table of archive 2 down to 3 bytes (its idx255 entry holds the length)
        java.io.RandomAccessFile(File(dir, "main_file_cache.idx255"), "rw").use { f ->
            f.seek(2L * 6); f.write(byteArrayOf(0, 0, 3))
        }
        val e = assertThrows(IllegalStateException::class.java) { CacheIO.open(dir.path) }
        assertTrue(e.message!!.contains("2"), e.message)
    }

    @Test
    fun tablePastTheEndOfTheDataFileIsAnError() {
        val dir = cache241()
        CacheIO.use(dir.path) { lib ->
            lib.put(CacheIO.CONFIGS, 10, 1, byteArrayOf(1, 2, 3)); lib.index(CacheIO.CONFIGS).update()
        }
        // archive 2's table now starts past the end of main_file_cache.dat2 (a truncated or half-copied cache): the
        // library loads such an archive empty (protocol 0, no groups), and a save into it would lose every group
        val sectors = File(dir, "main_file_cache.dat2").length() / 520
        java.io.RandomAccessFile(File(dir, "main_file_cache.idx255"), "rw").use { f ->
            val at = sectors + 10
            f.seek(2L * 6 + 3); f.write(byteArrayOf((at shr 16).toByte(), (at shr 8).toByte(), at.toByte()))
        }
        val lib = CacheLibrary(dir.path, readOnly = true)
        try {
            assertTrue(lib.exists(CacheIO.CONFIGS) && lib.index(CacheIO.CONFIGS).version == 0, "the library loads it empty")
            assertEquals(listOf(CacheIO.CONFIGS), CacheIO.unreadableArchives(lib))
        } finally { lib.close() }
        val e = assertThrows(IllegalStateException::class.java) { CacheIO.open(dir.path) }
        assertTrue(e.message!!.contains("archive(s) 2 "), e.message)
        assertThrows(IllegalStateException::class.java) { CacheIO.openReadOnly(dir.path) }
        // an archive with no table at all (idx255 entry 0) is not an error
        java.io.RandomAccessFile(File(dir, "main_file_cache.idx255"), "rw").use { f -> f.seek(2L * 6); f.write(ByteArray(6)) }
        CacheIO.use(dir.path) { assertTrue(it.index(CacheIO.CONFIGS).archiveIds().isEmpty()) }
    }

    @Test
    fun revisionOfAFolderWithoutCache() {
        assertEquals(null, CacheIO.revisionOf(File(tmp, "nothing")))
        assertFalse(File(tmp, "nothing").exists())
        assertThrows(IOException::class.java) { CacheLibrary(File(tmp, "nothing").path) }
    }
}
