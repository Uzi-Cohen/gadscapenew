package com.gadscape.raids.tfa.raid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TfaRouteTest {

    @Test
    fun firstCommitWinsAndPathBitsFollowTheSides() {
        val r = TfaRoute()
        assertEquals(0, r.pathValue)
        assertTrue(r.commit(1, Side.EAST))
        assertFalse(r.commit(1, Side.WEST), "a section commits once")
        assertEquals(TfaRoomId.BORK, r.room(1))
        assertTrue(r.commit(2, Side.WEST))
        assertTrue(r.commit(3, Side.EAST))
        // bit (section - 1) = 1 for east [INF varbit 16417]
        assertEquals(0b101, r.pathValue)
        assertEquals(TfaRoomId.XARTH, r.room(3))
    }

    @Test
    fun nextSectionAfterTheGuardian() {
        val r = TfaRoute()
        assertNull(r.nextSection(), "no doors before the Guardian dies")
        r.markComplete(TfaRoomId.GUARDIAN)
        assertEquals(1, r.nextSection())
        r.commit(1, Side.WEST)
        r.markComplete(TfaRoomId.EZEKIEL)
        assertEquals(2, r.nextSection())
        r.commit(2, Side.EAST)
        r.markComplete(TfaRoomId.DREADNOTH)
        assertEquals(3, r.nextSection())
        r.commit(3, Side.WEST)
        r.markComplete(TfaRoomId.DAGONHAI)
        assertNull(r.nextSection())
        assertTrue(r.sectionsDone)
        assertEquals(listOf(TfaRoomId.GUARDIAN, TfaRoomId.EZEKIEL, TfaRoomId.DREADNOTH, TfaRoomId.DAGONHAI), r.doneInOrder())
        assertEquals(4, r.roomsDone)
    }
}
