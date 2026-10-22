package com.gadscape.raids.tfa.death

import com.gadscape.raids.tfa.raid.StaticTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeathFeeZoneTest {

    @Test
    fun zoneSquaresOfProc10182() {
        assertTrue(DeathFeeZone.contains(StaticTile(4127, 5599, 0)))   // nexus (64,87)
        assertTrue(DeathFeeZone.contains(StaticTile(4063, 5532, 0)))   // Ezekiel (63,86)
        assertTrue(DeathFeeZone.contains(StaticTile(4186, 5599, 3)))   // Bork (65,87), any plane
        assertTrue(DeathFeeZone.contains(StaticTile(4066, 5663, 0)))   // Moia (63,88)
        assertTrue(DeathFeeZone.contains(StaticTile(4127, 5673, 1)))   // Ocellus arena, plane 1
        assertFalse(DeathFeeZone.contains(StaticTile(4127, 5673, 3)))  // ...but not plane 3
        assertFalse(DeathFeeZone.contains(StaticTile(4126, 5508, 0)))  // lobby (64,86)
        assertFalse(DeathFeeZone.contains(StaticTile(4127, 5775, 2)))  // vault (64,90)
        assertFalse(DeathFeeZone.contains(StaticTile(3222, 3222, 0)))
    }

    @Test
    fun feeIsAQuarterBelowFiveKc() {
        val inZone = StaticTile(4127, 5599, 0)
        assertEquals(25, DeathFeeZone.feePercent(inZone, 0))
        assertEquals(25, DeathFeeZone.feePercent(inZone, 4))
        assertEquals(100, DeathFeeZone.feePercent(inZone, 5))
        assertEquals(100, DeathFeeZone.feePercent(StaticTile(4126, 5508, 0), 0))
        assertEquals(100, DeathFeeZone.feePercent(null, 0))
    }
}
