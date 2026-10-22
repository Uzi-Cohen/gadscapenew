package com.gadscape.raids.tfa.encounter

import com.gadscape.game.world.Lines
import com.zenyte.game.world.entity.npc.combat.CombatTraitRules
import com.zenyte.game.world.entity.npc.combat.SpellElement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WU-07 (spec §6.2) acceptance tests for the spell hooks (E17) and line helpers (E18). */
class SpellAndLineHelpersTest {

    /** Xarth's pillars (spec §4.7): 2x2 "Rubble", south-west tiles. */
    private val pillars = mapOf(
        "A" to Lines.tile(4193, 5530),
        "B" to Lines.tile(4193, 5540),
        "C" to Lines.tile(4196, 5536),
    )

    private fun pillarAt(x: Int, y: Int): String? =
        pillars.entries.firstOrNull { (_, sw) -> Lines.inFootprint(x, y, Lines.tileX(sw), Lines.tileY(sw), 2) }?.key

    /** The ray from Xarth's centre (4191,5535) to (4198,5536) hits pillar C first. */
    @Test
    fun xarthBlastRayHitsPillarC() {
        val ray = Lines.bresenham(4191, 5535, 4198, 5536)
        assertEquals(8, ray.size)
        assertEquals(Lines.tile(4191, 5535), ray.first())
        assertEquals(Lines.tile(4198, 5536), ray.last())
        val hit = Lines.firstBlocked(4191, 5535, 4198, 5536) { x, y -> pillarAt(x, y) != null }
        assertEquals(Lines.tile(4196, 5536), hit)
        assertEquals("C", pillarAt(Lines.tileX(hit!!), Lines.tileY(hit)))
        // a player north-west of the boss has a clear line
        assertNull(Lines.firstBlocked(4191, 5535, 4185, 5540) { x, y -> pillarAt(x, y) != null })
        assertFalse(Lines.isBlocked(4191, 5535, 4185, 5540) { x, y -> pillarAt(x, y) != null })
        assertTrue(Lines.isBlocked(4191, 5535, 4194, 5529) { x, y -> pillarAt(x, y) != null }, "pillar A covers the south-east")
    }

    @Test
    fun bresenhamIsSymmetricInLengthAndCoversEndpoints() {
        assertContentEquals(intArrayOf(Lines.tile(5, 5)), Lines.bresenham(5, 5, 5, 5))
        val diagonal = Lines.bresenham(0, 0, 3, 3)
        assertContentEquals(intArrayOf(Lines.tile(0, 0), Lines.tile(1, 1), Lines.tile(2, 2), Lines.tile(3, 3)), diagonal)
        val back = Lines.bresenham(4198, 5536, 4191, 5535)
        assertEquals(8, back.size)
        assertEquals(Lines.tile(4191, 5535), back.last())
    }

    /** A Water Bolt reports WATER (the element passed to `NPC.onSpellHit`); the raid enum maps both ways. */
    @Test
    fun waterBoltReportsWater() {
        assertEquals(SpellElement.WATER, SpellElement.ofName("WATER_BOLT"))
        assertEquals(SpellElement.AIR, SpellElement.ofName("WIND_WAVE"))
        assertEquals(SpellElement.EARTH, SpellElement.ofName("EARTH_SURGE"))
        assertEquals(Element.WATER, Element.of(SpellElement.ofName("WATER_BOLT")))
        assertEquals(SpellElement.FIRE, Element.FIRE.engine)
        assertNull(Element.of(null))
    }

    /** A zero-delay override lands the hit on the cast tick (processed immediately); other overrides keep their ticks. */
    @Test
    fun zeroDelayOverrideLandsOnTheCastTick() {
        assertEquals(-1, CombatTraitRules.landingDelay(0))
        assertEquals(-1, CombatTraitRules.landingDelay(-3))
        assertEquals(2, CombatTraitRules.landingDelay(2))
    }
}
