package com.gadscape.raids.tfa.raid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaidGeometryTest {

    @Test
    fun antechambersAreTwoTilesInsideThePassageway() {
        // Passageway 62765 (2 x 5 for rot 1/3) [CACHE raid_locs]; west rooms lie west of it, east rooms east.
        assertEquals(StaticTile(4083, 5535), RaidGeometry.antechamber(TfaRoomId.EZEKIEL, 2))
        assertEquals(StaticTile(4086, 5599), RaidGeometry.antechamber(TfaRoomId.DAGONHAI, 2))
        assertEquals(StaticTile(4084, 5663), RaidGeometry.antechamber(TfaRoomId.MOIA, 2))
        assertEquals(StaticTile(4172, 5535), RaidGeometry.antechamber(TfaRoomId.XARTH, 2))
        assertEquals(StaticTile(4167, 5599), RaidGeometry.antechamber(TfaRoomId.BORK, 2))
        assertEquals(StaticTile(4172, 5663), RaidGeometry.antechamber(TfaRoomId.DREADNOTH, 2))
        // The antechamber row is the transporter's row.
        for (room in TfaRoomId.entries.filter { it.isPaired }) {
            assertEquals(RaidGeometry.exitOf(room).transporter.y, RaidGeometry.antechamber(room, 2).y, "$room")
        }
    }

    @Test
    fun antechamberIsBetweenPassagewayAndTransporter() {
        for (room in TfaRoomId.entries.filter { it.isPaired }) {
            val a = RaidGeometry.antechamber(room, 2)
            val e = RaidGeometry.exitOf(room)
            val lo = minOf(e.passageway.x, e.transporter.x)
            val hi = maxOf(e.passageway.x + 1, e.transporter.x)
            assertTrue(a.x in lo..hi, "$room antechamber $a")
            assertTrue(!RaidGeometry.onPassageway(room, a), "$room antechamber on the passageway")
        }
    }

    @Test
    fun sectionDoors() {
        val w1 = RaidGeometry.door(1, Side.WEST)
        assertEquals(StaticTile(4113, 5584), w1.tile)
        assertEquals(3, w1.rotation)
        val e3 = RaidGeometry.door(3, Side.EAST)
        assertEquals(StaticTile(4141, 5610), e3.tile)
        assertEquals(1, e3.rotation)
    }

    @Test
    fun doorIdsMapBack() {
        // 63088-63107 by section / side [CACHE]; 63094 / 63095 (closed) have no ops and map to nothing.
        assertEquals(1 to Side.WEST, RaidGeometry.doorOf(63088))
        assertEquals(1 to Side.EAST, RaidGeometry.doorOf(63093))
        assertEquals(2 to Side.WEST, RaidGeometry.doorOf(63097))
        assertEquals(3 to Side.EAST, RaidGeometry.doorOf(63107))
        assertNull(RaidGeometry.doorOf(63094))
        assertNull(RaidGeometry.doorOf(63095))
    }
}
