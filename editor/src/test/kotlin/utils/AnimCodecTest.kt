package utils

import cache.CacheIO
import com.displee.cache.CacheLibrary
import com.displee.compress.CompressionType
import controllers.InterfaceCodec
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The two sequence layouts (rev 211 / build 241), the conversion between them and the interface model part, on
 * hand-made entries. The real caches are checked by ConfigRoundTripTest (-Pgadscape.test.caches=...) and by the
 * harness in editor-work/codecs-anim-ui (every sequence and interface of the stock 241, GadScape 241 and 211 caches,
 * plus the real 241 client's decoder on everything written).
 */
class AnimCodecTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // 2 frames (delays 3, 4; frames 0x0005_0001 and 0x0005_0002), priority 7, then the skeletal settings
    private val frames = intArrayOf(1, 0, 2, 0, 3, 0, 4, 0, 1, 0, 2, 0, 5, 0, 5)
    // rev 211 skeletal: 14 = keyframes 0x0100_0002, 15 = skeletal sounds (1: frame 0, sound 300 loops 1 location 2),
    // 16 = range 0..40, 17 = masks {3, 9}
    private val seq211 = bytes(*frames, 5, 7, 14, 1, 0, 0, 2, 15, 0, 1, 0, 0, 0x01, 0x2c, 0x12, 16, 0, 0, 0, 40, 17, 2, 3, 9, 0)
    // the same in build 241: 13 = keyframes, 14 = sounds (frame, id, weight 100, loops, location, retain), 15 = range
    private val seq241 = bytes(*frames, 5, 7, 13, 1, 0, 0, 2, 14, 0, 1, 0, 0, 0x01, 0x2c, 100, 1, 2, 0, 15, 0, 0, 0, 40, 17, 2, 3, 9, 0)
    // frame animations: 211 keeps a sound per frame (13: 2, frame 0 sound 300, frame 1 none), 241 a list (14)
    private val frames211 = bytes(*frames, 13, 2, 0x01, 0x2c, 0x12, 0, 0, 0, 0)
    private val frames241 = bytes(*frames, 14, 0, 1, 0, 0, 0x01, 0x2c, 100, 1, 2, 0, 0)

    @Test
    fun readsBothLayouts() {
        for ((b, rev) in listOf(seq211 to 211, seq241 to 241)) {
            val s = SeqDef.decode(1, b, rev)
            assertTrue(s.readOk, "rev $rev")
            assertArrayEquals(intArrayOf(0x50001, 0x50002), s.frameIds)
            assertArrayEquals(intArrayOf(3, 4), s.delays)
            assertEquals(7, s.priority)
            assertEquals(0x01000002, s.skeletalModel)
            assertEquals(0 to 40, s.skeletalStart to s.skeletalEnd)
            assertTrue(s.skeletalMasks!![3] && s.skeletalMasks!![9])
            assertArrayEquals(b, s.encode(s.frameIds, s.delays), "unchanged = same bytes (rev $rev)")
        }
        // the other layout doesn't read them the same
        assertFalse(SeqDef.decode(1, seq241, 211).let { it.readOk && it.skeletalModel == 0x01000002 })
        assertFalse(SeqDef.decode(1, seq211, 241).let { it.readOk && it.skeletalModel == 0x01000002 })
    }

    @Test
    fun convertsBothWays() {
        assertArrayEquals(seq241, SeqConv.convert(1, seq211, 211, 241).bytes)
        val down = SeqConv.convert(1, seq241, 241, 211)
        assertEquals(emptyList<String>(), down.dropped)
        assertArrayEquals(seq211, down.bytes)
        assertArrayEquals(frames241, SeqConv.convert(1, frames211, 211, 241).bytes)
        // 211 keeps one sound per frame: frame 1's is empty
        assertArrayEquals(frames211, SeqConv.convert(1, frames241, 241, 211).bytes)
        assertEquals(1, SeqDef.decode(1, frames241, 241).soundCount)
        // what 211 can't hold is reported
        val extra = bytes(*frames, 16, 5, 18, 'a'.code, 0, 19, 0)
        assertEquals(listOf("vertical offset", "sounds across world views"), SeqConv.convert(1, extra, 241, 211).dropped)
        assertArrayEquals(bytes(*frames, 0), SeqConv.convert(1, extra, 241, 211).bytes)
    }

    @Test
    fun writesTheKeyframeSetWhereEachLayoutHasIt() {
        val s241 = SeqDef.decode(1, seq241, 241)
        val e241 = SeqDef.decode(1, s241.encode(s241.frameIds, s241.delays, null, (20000 shl 16) or 0), 241)
        assertEquals(20000 shl 16, e241.skeletalModel)
        val s211 = SeqDef.decode(1, seq211, 211)
        val e211 = SeqDef.decode(1, s211.encode(s211.frameIds, s211.delays, null, (20001 shl 16) or 3), 211)
        assertEquals((20001 shl 16) or 3, e211.skeletalModel)
        assertEquals(1, e211.soundCount)
    }

    @Test
    fun setsASkeletalRangeWhereEachLayoutHasIt() {
        // the entries above without their range: it goes back where the opcode order has it, the rest byte for byte
        val no241 = bytes(*frames, 5, 7, 13, 1, 0, 0, 2, 14, 0, 1, 0, 0, 0x01, 0x2c, 100, 1, 2, 0, 17, 2, 3, 9, 0)
        val s241 = SeqDef.decode(1, no241, 241)
        assertTrue(s241.readOk); assertEquals(0, s241.skeletalEnd - s241.skeletalStart)
        assertArrayEquals(seq241, s241.withRange(0, 40))
        val no211 = bytes(*frames, 5, 7, 14, 1, 0, 0, 2, 15, 0, 1, 0, 0, 0x01, 0x2c, 0x12, 17, 2, 3, 9, 0)
        assertArrayEquals(seq211, SeqDef.decode(1, no211, 211).withRange(0, 40))
        // an old range is replaced
        val again = SeqDef.decode(1, SeqDef.decode(1, seq241, 241).withRange(2, 99), 241)
        assertTrue(again.readOk); assertEquals(2 to 99, again.skeletalStart to again.skeletalEnd)
        // a keyframe set's header: version, skeleton, start, end
        assertArrayEquals(intArrayOf(0, 120), Keyframes.headerRange(bytes(1, 0, 9, 0, 0, 0, 120, 0, 0, 0)))
        assertNull(Keyframes.headerRange(bytes(1, 0, 9, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals("3, 10174-10181, 10230", Keyframes.idList(listOf(10230, 3) + (10174..10181)))
        assertEquals("1, 3 and 3 more", Keyframes.idList(listOf(1, 3, 5, 6, 8), max = 2))
    }

    @TempDir
    lateinit var tmp: File

    /** An empty build 241 cache (archives 0-24, like CacheLibraryTest's). */
    private fun cache241(name: String): File {
        val dir = File(tmp, name).apply { mkdirs() }
        File(dir, "main_file_cache.dat2").createNewFile()
        File(dir, "main_file_cache.idx255").createNewFile()
        val lib = CacheLibrary(dir.path)
        repeat(25) { lib.createIndex(CompressionType.GZIP, 7, 0, false, false, true, false) }
        lib.close()
        return dir
    }

    @Test
    fun givesSkeletalAnimationsWithoutALengthTheirKeyframesRange() {
        val dir = cache241("lengths")
        // 5 and 6: skeletal without a range (keyframes 7:0 hold 0..120, 8:0 hold none); 7: has its range
        fun noRange(k: Int) = bytes(1, 0, 0, 13, 0, k, 0, 0, 0)
        val withRange = bytes(1, 0, 0, 13, 0, 7, 0, 0, 15, 0, 0, 0, 120, 0)
        CacheIO.use(dir.path) { lib ->
            assertEquals(241, CacheIO.revision(lib))
            lib.put(2, 12, 5, noRange(7)); lib.put(2, 12, 6, noRange(8)); lib.put(2, 12, 7, withRange)
            lib.put(22, 7, 0, bytes(1, 0, 9, 0, 0, 0, 120, 0, 0, 0)); lib.put(22, 8, 0, bytes(1, 0, 9, 0, 0, 0, 0, 0, 0, 0))
            lib.index(2).update(); lib.index(22).update()
            assertEquals(setOf(5, 6), Keyframes.lengthless(lib).keys)
            val note = Keyframes.fixLengths(lib)
            assertTrue(note.startsWith("Gave 1 skeletal animation(s) the length their keyframes hold (5)") &&
                    note.contains("1 skeletal animation(s) still have no length, so the game plays them for 0 cycles (6)"), note)
        }
        CacheIO.use(dir.path) { lib ->
            assertArrayEquals(withRange, lib.data(2, 12, 5))
            assertArrayEquals(noRange(8), lib.data(2, 12, 6))
            assertEquals(setOf(6), Keyframes.lengthless(lib).keys)
            assertEquals("", Keyframes.fixLengths(lib, listOf(5, 7)))
        }
    }

    @Test
    fun anAnimationThatCantBeReadIsntImportedWhenItsFrameSetsWouldMove() {
        // animation 50: one frame (set 100, file 1), then a setting neither layout has (99)
        val unreadable = bytes(1, 0, 1, 0, 5, 0, 1, 0, 100, 99, 0)
        assertFalse(SeqDef.decode(50, unreadable, 241).readOk)
        val frame = bytes(0, 9, 1, 2, 3)
        fun import(lib: CacheLibrary, seq: ByteArray, frames: Map<Int, Map<Int, ByteArray>>, keepIds: Boolean) =
            CacheEdits.importInto(lib, mapOf(50 to seq), frames, emptyMap(), emptyMap(), 241, null, keepIds)
        // the cache has another frame set 100, so the bundle's would move (to 30000): stopped, nothing of it written
        val taken = cache241("taken")
        CacheIO.use(taken.path) { lib ->
            lib.put(0, 100, 1, bytes(0, 9, 7, 7, 7)); lib.index(0).update()
            val e = assertThrows(IllegalStateException::class.java) { import(lib, unreadable, mapOf(100 to mapOf(1 to frame)), true) }
            assertTrue(e.message!!.contains("frame set(s) 100 would need new numbers") && e.message!!.contains("Nothing of the bundle was written"), e.message)
        }
        CacheIO.use(taken.path) { lib ->
            assertEquals(listOf(100), lib.index(0).archiveIds().toList())
            assertTrue(lib.index(2).archive(12)?.fileIds()?.isEmpty() ?: true)
        }
        // another server's content gets GadScape's numbers: stopped as well
        CacheIO.use(cache241("other").path) { lib ->
            assertThrows(IllegalStateException::class.java) { import(lib, unreadable, mapOf(100 to mapOf(1 to frame)), false) }
            assertTrue(lib.index(0).archiveIds().isEmpty())
        }
        // live content whose frame set is free here keeps its number: copied as it is
        CacheIO.use(cache241("free").path) { lib ->
            val r = import(lib, unreadable, mapOf(100 to mapOf(1 to frame)), true)
            assertEquals(listOf(50), r.newSeqIds)
            assertArrayEquals(unreadable, lib.data(2, 12, 50)); assertArrayEquals(frame, lib.data(0, 100, 1))
        }
        // a readable one is moved with its frame sets; a set planned for one number is never given to another
        CacheIO.use(cache241("readable").path) { lib ->
            lib.put(0, 100, 1, bytes(0, 9, 7, 7, 7)); lib.index(0).update()
            // frames 100:1 and 30000:1
            val seq = bytes(1, 0, 2, 0, 5, 0, 5, 0, 1, 0, 1, 0, 100, 0x75, 0x30, 0)
            val other = bytes(0, 9, 4, 4, 4)
            val r = import(lib, seq, linkedMapOf(100 to mapOf(1 to frame), 30000 to mapOf(1 to other)), true)
            val s = SeqDef.decode(r.newSeqIds.single(), lib.data(2, 12, r.newSeqIds.single())!!, 241)
            val (a, b) = s.frameIds.map { it ushr 16 }
            assertNotEquals(100, a); assertNotEquals(a, b)
            assertArrayEquals(frame, lib.data(0, a, 1)); assertArrayEquals(other, lib.data(0, b, 1))
            assertArrayEquals(bytes(0, 9, 7, 7, 7), lib.data(0, 100, 1))
        }
    }

    @Test
    fun bundleFormatLine() {
        assertEquals(211, CacheEdits.bundleFormat(null))
        assertEquals(211, CacheEdits.bundleFormat("# Uzi's Map Editor animation bundle\nseq 5\n"))
        assertEquals(241, CacheEdits.bundleFormat("# Uzi's Map Editor animation bundle\nformat 241\nseq 5\n"))
    }

    /** An IF3 model part (type 6) with model [model] in [rev]'s width, width mode [wMode] and height mode [hMode]. */
    private fun modelPart(rev: Int, model: Int, wMode: Int, hMode: Int, extra: Int = 0): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = o.write(v and 255)
        fun u16(v: Int) { u8(v shr 8); u8(v) }
        u8(255); u8(6); u16(0); u16(10); u16(20); u16(32); u16(32); u8(wMode); u8(hMode); u8(0); u8(0); u16(0xFFFF); u8(0)
        if (rev >= 241) { u16(model ushr 16); u16(model and 0xFFFF) } else u16(model)
        u16(0); u16(0); u16(0); u16(0); u16(0); u16(800); u16(0xFFFF); u8(0); u16(0)
        if (wMode != 0) u16(0)
        if (hMode != 0) u16(0)
        repeat(extra) { u8(0) }
        u8(0); u8(0); u8(0) // access mask
        u8(0) // op base ""
        u8(0) // ops
        u8(0); u8(0); u8(0) // drag
        u8(0) // target verb ""
        repeat(18) { u8(0) }
        repeat(3) { u8(0) }
        return o.toByteArray()
    }

    @Test
    fun interfaceModelParts() {
        val p241 = InterfaceCodec.decode(1, 2, modelPart(241, 123456, 0, 0), 241)
        assertTrue(p241.editable); assertEquals(123456, p241.model); assertEquals(10 to 20, p241.x to p241.y)
        val p211 = InterfaceCodec.decode(1, 2, modelPart(211, 4151, 0, 0), 211)
        assertTrue(p211.editable); assertEquals(4151, p211.model)
        // both size modes set: the client reads both overrides
        assertTrue(InterfaceCodec.decode(1, 2, modelPart(241, 7, 1, 1), 241).editable)
        // one mode set and both overrides written (the cache tool's quirk): the client reads one, 2 bytes are left
        assertTrue(InterfaceCodec.decode(1, 2, modelPart(241, 7, 1, 0, extra = 2), 241).editable)
        // a 241 part read as 211 isn't understood
        assertFalse(InterfaceCodec.decode(1, 2, modelPart(241, 123456, 0, 0), 211).editable)
    }
}
