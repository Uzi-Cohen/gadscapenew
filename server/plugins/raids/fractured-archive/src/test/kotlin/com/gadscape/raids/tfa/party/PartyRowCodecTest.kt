package com.gadscape.raids.tfa.party

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The row strings of scripts 10094 / 10053 / 10058 and the 975 names (spec §3.3). */
class PartyRowCodecTest {

    @Test
    fun boardAlwaysHas45Rows() {
        val empty = PartyRowCodec.boardRows(emptyList<String>()) { it }
        assertEquals(45, empty.size)
        assertTrue(empty.all { it.isEmpty() })
        val some = PartyRowCodec.boardRows(listOf("a", "b", "c")) { it }
        assertEquals(45, some.size)
        assertEquals(listOf("a", "b", "c"), some.take(3))
        assertTrue(some.drop(3).all { it.isEmpty() })
        val many = PartyRowCodec.boardRows((1..60).map { "p$it" }) { it }
        assertEquals(45, many.size)
        assertEquals("p45", many.last())
    }

    @Test
    fun listRowHas13FieldsInScriptOrder() {
        val row = PartyRowCodec.listRow(listOf("Leader", "Two", "Three"), viewerInParty = false,
            preferredSize = 5, preferredCompletions = 0, mode = 0, ageTicks = 123)
        val f = PartyRowCodec.split(row)
        assertEquals(PartyRowCodec.LIST_FIELDS, f.size)
        assertEquals(listOf("Leader", "Two", "Three", "", "", "", "", "", "3", "5", "0", "Normal", "123"), f)
    }

    @Test
    fun viewerSeesOwnLeaderInWhiteAndUnsetSizeAsDash() {
        val f = PartyRowCodec.split(PartyRowCodec.listRow(listOf("Me"), true, 0, 250, 1, -5))
        assertEquals("<col=FFFFFF>Me", f[0])
        assertEquals("1", f[8])
        assertEquals("-", f[9])
        assertEquals("250", f[10])
        assertEquals("Practice", f[11])
        assertEquals("0", f[12], "age never negative")
    }

    @Test
    fun memberRowHas11Fields() {
        val info = PartyRowCodec.MemberInfo("Zezima", 126, 99, 99, 99, 99, 99, 99, 99, 12, 3)
        val f = PartyRowCodec.split(PartyRowCodec.memberRow(info))
        assertEquals(PartyRowCodec.MEMBER_FIELDS, f.size)
        assertEquals(listOf("Zezima", "126", "99", "99", "99", "99", "99", "99", "99", "12", "3"), f)
    }

    @Test
    fun pipesInNamesNeverShiftColumns() {
        val f = PartyRowCodec.split(PartyRowCodec.listRow(listOf("a|b"), false, 0, 0, 0, 0))
        assertEquals(PartyRowCodec.LIST_FIELDS, f.size)
        assertEquals("ab", f[0])
    }

    @Test
    fun overlayNamesFillEightLines() {
        assertEquals("-<br>-<br>-<br>-<br>-<br>-<br>-<br>-", PartyRowCodec.overlayNames(emptyList()))
        val text = PartyRowCodec.overlayNames(listOf("A", "B"))
        assertEquals(listOf("A", "B", "-", "-", "-", "-", "-", "-"), text.split("<br>"))
    }
}
