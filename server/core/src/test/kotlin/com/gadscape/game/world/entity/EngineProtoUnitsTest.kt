package com.gadscape.game.world.entity

import com.gadscape.game.world.PrivateScene
import com.gadscape.game.world.entity.npc.NpcHeadIcons
import com.zenyte.game.world.entity.InterpolatingHitBar
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** E13 (head icon slots), E14 (interpolating head bars) and the loc layers of E16 (per-player locs). */
class EngineProtoUnitsTest {

    @Test
    fun `headbar 85 fills over 12 ticks`() {
        val bar = InterpolatingHitBar.Charge70().interpolate(0.0, 1.0, 12)
        assertEquals(85, bar.type)
        assertEquals(0, bar.percentage)            // start fill
        assertEquals(70, bar.interpolatePercentage()) // end fill = all 70 segments
        assertEquals(0, bar.delay)
        assertEquals(360, bar.interpolateTime())   // 12 ticks x 30 client cycles
    }

    @Test
    fun `segments per 2746 headbar config`() {
        assertEquals(85 to 70, InterpolatingHitBar.Charge70().let { it.type to it.segments })
        assertEquals(86 to 60, InterpolatingHitBar.Shield60().let { it.type to it.segments })
        assertEquals(87 to 60, InterpolatingHitBar.Blood45().let { it.type to it.segments })
        assertEquals(88 to 60, InterpolatingHitBar.Heat60().let { it.type to it.segments })
        assertEquals(89 to 30, InterpolatingHitBar.Shield30().let { it.type to it.segments })
    }

    @Test
    fun `static fills round and clamp`() {
        val shield = InterpolatingHitBar.Shield60().fill(0.5)
        assertEquals(30, shield.percentage)
        assertEquals(30, shield.interpolatePercentage())
        assertEquals(0, shield.interpolateTime())
        assertEquals(60, InterpolatingHitBar.Shield60().fill(7.0).percentage)
        assertEquals(0, InterpolatingHitBar.Shield60().fill(-1.0).percentage)
        // a draining charge: full to empty over 25 ticks (Moia enrage)
        val drain = InterpolatingHitBar.Charge70().interpolate(1.0, 0.0, 25)
        assertEquals(70, drain.percentage)
        assertEquals(0, drain.interpolatePercentage())
        assertEquals(750, drain.interpolateTime())
        // the block caps times at 32766 cycles (32767 means remove)
        assertEquals(InterpolatingHitBar.MAX_CYCLES, InterpolatingHitBar.Heat60().interpolate(0.0, 1.0, 5000).interpolateTime())
    }

    @Test
    fun `two head icon slots for Ocellus`() {
        val icons = NpcHeadIcons()
        val v0 = icons.version
        icons.setPrayers(NpcHeadIcons.DEFLECT_MELEE, NpcHeadIcons.DEFLECT_MAGIC)
        assertTrue(icons.version > v0)
        assertEquals(440, icons.group(0))
        assertEquals(12, icons.index(0))
        assertEquals(440, icons.group(1))
        assertEquals(14, icons.index(1))
        assertTrue(icons.isEmpty(2))
        val v1 = icons.version
        icons.set(1, 440, 14) // unchanged
        assertEquals(v1, icons.version)
        icons.clear(1)
        assertTrue(icons.isEmpty(1))
        assertFailsWith<IllegalArgumentException> { icons.set(8, 440, 0) }
    }

    @Test
    fun `loc layers follow the client's loc slots`() {
        assertEquals(0, PrivateScene.layer(0))
        assertEquals(0, PrivateScene.layer(3))
        assertEquals(1, PrivateScene.layer(4))
        assertEquals(1, PrivateScene.layer(8))
        assertEquals(2, PrivateScene.layer(10))
        assertEquals(2, PrivateScene.layer(11))
        assertEquals(2, PrivateScene.layer(21))
        assertEquals(3, PrivateScene.layer(22))
    }
}
