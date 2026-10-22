package utils

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rev 211 / build 241 differences in NPCs, objects and identity kits that the round trips over real caches
 * (ConfigRoundTripTest) can't see, on hand-made entries: the NPC follower flags, the 211-only object settings 60/63,
 * the 5 chathead slots of kits and what an option "Hidden" does in each format.
 */
class ConfigCodecsTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun str(s: String) = (s.toByteArray(Charsets.ISO_8859_1).map { it.toInt() and 255 } + 0).toIntArray()

    @Test
    fun npcFollowerBecomes122And123() {
        // name "Pet", model 5 (211 opcode 1), follower (211 opcode 111)
        val n211 = bytes(2, *str("Pet"), 1, 1, 0, 5, 111, 0)
        val old = NpcDefs.decodeAs(1, n211, 211)
        assertTrue(old.readOk && old.follower && old.followerOpsLow)
        val n241 = ConfigConv.npc211to241(1, n211)
        assertArrayEquals(bytes(2, *str("Pet"), 61, 1, 0, 0, 0, 5, 122, 123, 0), n241)
        val d = NpcDefs.decodeAs(1, n241, 241)
        assertTrue(d.readOk && d.follower && d.followerOpsLow)
        assertArrayEquals(intArrayOf(5), d.models)
        // 241 opcode 111 is render priority, not the follower flag
        val prio = NpcDefs.decodeAs(1, bytes(111, 0), 241)
        assertTrue(prio.readOk && !prio.follower && !prio.followerOpsLow)
    }

    @Test
    fun objectSettings60And63() {
        // name "Rock", models (211 opcode 1: model 7 type 10), 63 (211 only, no data)
        val with63 = bytes(2, *str("Rock"), 1, 1, 0, 7, 10, 63, 0)
        val out = ConfigConv.loc211to241(1, with63)
        assertArrayEquals(bytes(2, *str("Rock"), 6, 1, 0, 0, 0, 7, 10, 0), out)
        assertTrue(ObjDefs.decodeWith(1, out, true, 241) != null)
        // 60 (211 only, u16) has no 241 equivalent: the 241 client would read its two bytes as settings
        val with60 = bytes(2, *str("Rock"), 60, 0, 9, 0)
        assertThrows(IllegalStateException::class.java) { ConfigConv.loc211to241(1, with60) }
        // and the 241 tables don't have either
        assertNull(ObjDefs.decodeWith(1, with60, true, 241))
    }

    @Test
    fun kitsHaveFiveChatheads() {
        assertTrue(KitCodec.decode(1, bytes(1, 0, 64, 0, 9, 0), 211).let { it.readOk && it.heads[4] == 9 })
        assertTrue(KitCodec.decode(1, bytes(1, 0, 74, 0, 0, 0, 9, 0), 241).let { it.readOk && it.heads[4] == 9 })
        // both clients index an array of 5 with opcode - 60 / - 70 and throw on the rest
        assertFalse(KitCodec.decode(1, bytes(1, 0, 65, 0, 9, 0), 211).readOk)
        assertFalse(KitCodec.decode(1, bytes(1, 0, 75, 0, 0, 0, 9, 0), 241).readOk)
        assertThrows(IllegalStateException::class.java) { ConfigConv.kit211to241(1, bytes(65, 0, 9, 0)) }
        assertArrayEquals(bytes(74, 0, 0, 0, 9, 0), ConfigConv.kit211to241(1, bytes(64, 0, 9, 0)))
    }

    @Test
    fun hiddenClearsIn211AndIsSkippedIn241() {
        val npc = bytes(2, *str("Man"), 30, *str("Talk-to"), 30, *str("Hidden"), 0)
        assertNull(NpcDefs.decodeAs(1, npc, 211).options[0])
        assertEquals("Talk-to", NpcDefs.decodeAs(1, npc, 241).options[0])
        val obj = bytes(2, *str("Door"), 30, *str("Open"), 30, *str("Hidden"), 0)
        assertNull(ObjDefs.decode(1, obj, 211).options[0])
        assertEquals("Open", ObjDefs.decode(1, obj, 241).options[0])
    }

    @Test
    fun appendedOptionsPerFormat() {
        // settings neither format knows (opcode 200), so the name and options can only be added at the end
        val raw = bytes(200, 1, 2, 3, 0)
        val d241 = ObjDefs.decode(1, raw, 241)
        assertFalse(d241.readOk)
        assertEquals(241, d241.rev)
        // 241: empty slots are left alone (a "Hidden" would be skipped by the game anyway)
        val b241 = d241.encodeAppended("Box", arrayOf("Open", null, null, null, null))
        assertArrayEquals(bytes(200, 1, 2, 3, 2, *str("Box"), 30, *str("Open"), 0), b241)
        val back = ObjDefs.decode(1, b241, 241)
        assertEquals("Box", back.name)
        assertEquals("Open", back.options[0])
        // removing an option the editor shows can't be done in 241: refused
        assertThrows(IllegalStateException::class.java) { back.encodeAppended("Box", arrayOfNulls(5)) }
        assertThrows(IllegalStateException::class.java) { back.encodeAppended("Box", arrayOf("Hidden", null, null, null, null)) }
        // the settings before the unknown one are shown, so an option set there can't be "removed" either
        val odd = ObjDefs.decode(1, bytes(2, *str("Odd"), 30, *str("Open"), 200, 1, 2, 3, 0), 241)
        assertFalse(odd.readOk)
        assertEquals(200, odd.unknownOp)
        assertEquals("Odd" to "Open", odd.name to odd.options[0])
        assertThrows(IllegalStateException::class.java) { odd.encodeAppended("Odd", arrayOfNulls(5)) }
        val replaced = ObjDefs.decode(1, odd.encodeAppended("Odd box", arrayOf("Search", null, null, null, null)), 241)
        assertEquals("Odd box" to "Search", replaced.name to replaced.options[0])
        // 211: every empty slot gets "Hidden", which clears it there
        val d211 = ObjDefs.decode(1, raw, 211)
        val b211 = d211.encodeAppended("Box", arrayOf("Open", null, null, null, null))
        assertArrayEquals(bytes(200, 1, 2, 3, 2, *str("Box"), 30, *str("Open"), 31, *str("Hidden"), 32, *str("Hidden"),
            33, *str("Hidden"), 34, *str("Hidden"), 0), b211)
        val back211 = ObjDefs.decode(1, b211, 211)
        assertEquals("Box", back211.name)
        assertEquals(listOf("Open", null, null, null, null), back211.options.toList())
    }
}
