package com.gadscape.raids.tfa.raid

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.encounter.HazardTiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameworkConfigTest {

    @Test
    fun bundledFrameworkJsonMatchesDefaults() = TfaConfigTestKit.assertBundledMatchesDefaults(FrameworkConfig)

    @Test
    fun specValues() {
        val cfg = TfaConfigTestKit.load(listOf(FrameworkConfig), bundled = emptyMap())[FrameworkConfig]
        assertEquals(100, cfg.spectate.maxSpectators) // [DOC] "maximum number of spectators"
        assertEquals(5, cfg.death.feeKcThreshold)     // script 1797
        assertEquals(25, cfg.death.feeReducedPct)     // scale(1,4,fee)
    }

    @Test
    fun hazardTilesExpireAndRefresh() {
        val h = HazardTiles()
        val a = StaticTile(4190, 5600)
        val b = StaticTile(4191, 5600)
        assertTrue(h.add(a, 2))
        assertTrue(h.add(b, 0)) // until removed
        assertFalse(h.add(a, 3)) // refresh, not new
        assertEquals(emptyList(), h.advance())
        assertEquals(emptyList(), h.advance())
        assertEquals(listOf(a), h.advance())
        assertTrue(b in h)
        assertTrue(h.remove(b))
        assertEquals(0, h.size)
    }
}
