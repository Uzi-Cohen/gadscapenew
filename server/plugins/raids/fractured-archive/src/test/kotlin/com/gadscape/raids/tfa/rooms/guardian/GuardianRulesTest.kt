package com.gadscape.raids.tfa.rooms.guardian

import com.gadscape.raids.tfa.encounter.Scaling
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuardianRulesTest {

    /** §4.1: T = 45 × alive; T(2) = 90 leaves a lone 99-HP target alive; with 3 alive an unshared laser kills. */
    @Test
    fun laserSplit() {
        val t2 = GuardianRules.laserTotal(2, 45)
        assertEquals(90, t2)
        assertTrue(GuardianRules.laserShare(t2, 1, false, 0.8) < 99)
        val t3 = GuardianRules.laserTotal(3, 45)
        assertEquals(135, t3)
        assertTrue(GuardianRules.laserShare(t3, 1, false, 0.8) >= 99, "a lone target dies when 3 are alive")
        assertTrue(GuardianRules.laserShare(t3, 1, true, 0.8) >= 99, "Protect from Magic only reduces it slightly")
        assertEquals(67, GuardianRules.laserShare(t3, 2, false, 0.8))
        assertEquals(45, GuardianRules.laserShare(t3, 3, false, 0.8))
        assertEquals(36, GuardianRules.laserShare(t3, 3, true, 0.8))
        assertEquals(360, GuardianRules.laserTotal(8, 45))
        assertEquals(45, GuardianRules.laserShare(360, 8, false, 0.8))
    }

    /** §4.1 batteries: 40% of the scaled max shield (596 of 1490 at 6-scale, the one live data point). */
    @Test
    fun batteryDischarge() {
        assertEquals(220, GuardianRules.discharge(550, 40))
        assertEquals(596, GuardianRules.discharge(Scaling.DEFAULT.hp(550, 6), 40))
        assertEquals(1490, Scaling.DEFAULT.hp(550, 6))
        assertEquals(2980, Scaling.DEFAULT.hp(1100, 6))
        assertEquals(314, GuardianRules.discharge(785, 40))
    }

    @Test
    fun killRange() {
        assertEquals(110, GuardianRules.killRangeFloor(1100, 10))
        assertEquals(298, GuardianRules.killRangeFloor(2980, 10))
        assertEquals(220, GuardianRules.clampToFloor(500, 220, 110))
        assertEquals(40, GuardianRules.clampToFloor(150, 220, 110))
        assertEquals(0, GuardianRules.clampToFloor(110, 220, 110), "in kill range batteries stop hurting it")
        assertEquals(0, GuardianRules.clampToFloor(90, 220, 110))
        assertEquals(220, GuardianRules.clampToFloor(550, 220, 0), "the shield has no floor")
    }

    /** §4.1 lift rule: the next 35% step of main HP (100 → 65); 30% is the enrage. */
    @Test
    fun liftBoundary() {
        assertEquals(65, GuardianRules.liftBoundaryPct(100.0, 35, 30))
        assertEquals(65, GuardianRules.liftBoundaryPct(80.0, 35, 30))
        assertNull(GuardianRules.liftBoundaryPct(65.0, 35, 30))
        assertNull(GuardianRules.liftBoundaryPct(50.0, 35, 30))
        assertEquals(75, GuardianRules.liftBoundaryPct(100.0, 25, 30))
        assertEquals(50, GuardianRules.liftBoundaryPct(75.0, 25, 30))
        assertNull(GuardianRules.liftBoundaryPct(50.0, 25, 30))
    }

    @Test
    fun mushroomsAndClock() {
        assertEquals(4, GuardianRules.mushroomCount(2, 3, 1, 12))
        assertEquals(4, GuardianRules.mushroomCount(3, 3, 1, 12))
        assertEquals(7, GuardianRules.mushroomCount(8, 3, 1, 12))
        assertEquals(2, GuardianRules.mushroomCount(8, 3, 1, 2), "never above the cap")
        assertEquals(0, GuardianRules.mushroomCount(8, 3, 1, 0))
        assertFalse(GuardianRules.isMushroomAttack(1, 3))
        assertFalse(GuardianRules.isMushroomAttack(2, 3))
        assertTrue(GuardianRules.isMushroomAttack(3, 3))
        assertTrue(GuardianRules.isMushroomAttack(6, 3))
        assertFalse(GuardianRules.isMushroomAttack(3, 0))
    }

    /** §4.1 tendrils: max 25 unprayed, 11 with Protect from Melee [COMM hotfix value]. */
    @Test
    fun tendrilCap() {
        assertEquals(11, GuardianRules.tendrilMax(true, 25, 11))
        assertEquals(25, GuardianRules.tendrilMax(false, 25, 11))
        val r = Random(7)
        repeat(2000) {
            val roll = GuardianRules.roll(r, 0, GuardianRules.tendrilMax(true, 25, 11))
            assertTrue(roll in 0..11)
        }
        repeat(2000) { assertTrue(GuardianRules.roll(r, 3, 10) in 3..10) }
        assertEquals(5, GuardianRules.roll(r, 5, 5))
    }

    @Test
    fun accuracyRoll() {
        val r = Random(1)
        repeat(500) { assertEquals(0, GuardianRules.accuracyRoll(r, 0.0, 25)) }
        var nonZero = 0
        repeat(500) {
            val v = GuardianRules.accuracyRoll(r, 1.0, 25)
            assertTrue(v in 0..25)
            if (v > 0) nonZero++
        }
        assertTrue(nonZero > 400)
    }

    /** §4 shared aggro rule: most damage in the window, then nearest, then slot. */
    @Test
    fun aggro() {
        val c = { who: String, dmg: Int, dist: Int, slot: Int -> GuardianRules.AggroCandidate(who, dmg, dist, slot) }
        assertEquals("b", GuardianRules.pickAggro(listOf(c("a", 10, 1, 0), c("b", 30, 5, 1), c("c", 30, 6, 2))))
        assertEquals("c", GuardianRules.pickAggro(listOf(c("a", 0, 4, 0), c("b", 0, 3, 1), c("c", 0, 2, 2))))
        assertEquals("a", GuardianRules.pickAggro(listOf(c("b", 0, 2, 1), c("a", 0, 2, 0))))
        assertNull(GuardianRules.pickAggro(emptyList<GuardianRules.AggroCandidate<String>>()))
    }
}
