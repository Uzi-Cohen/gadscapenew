package com.gadscape.raids.tfa.rooms.guardian

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuardianGeometryTest {

    @Test
    fun centreAndFootprint() {
        assertEquals(Tile(4127, 5599), GuardianGeometry.centre(4125, 5597))
        assertTrue(GuardianGeometry.under(4125, 5597, 5, 4125, 5597))
        assertTrue(GuardianGeometry.under(4125, 5597, 5, 4129, 5601))
        assertFalse(GuardianGeometry.under(4125, 5597, 5, 4130, 5599))
        assertFalse(GuardianGeometry.under(4125, 5597, 5, 4124, 5599))
    }

    /** §4.1 tendrils: one tile further from the boss than the player; on-axis players move on one axis only. */
    @Test
    fun tendrilTileIsBehindThePlayer() {
        val cx = 4127
        val cy = 5599
        assertEquals(Tile(4134, 5599), GuardianGeometry.tendrilTile(4133, 5599, cx, cy)) // east of the boss → east
        assertEquals(Tile(4119, 5599), GuardianGeometry.tendrilTile(4120, 5599, cx, cy)) // west → west
        assertEquals(Tile(4135, 5608), GuardianGeometry.tendrilTile(4134, 5607, cx, cy)) // NE diagonal
        assertEquals(Tile(4120, 5591), GuardianGeometry.tendrilTile(4121, 5592, cx, cy)) // SW diagonal
        assertEquals(Tile(4127, 5606), GuardianGeometry.tendrilTile(4127, 5605, cx, cy)) // due north
        assertEquals(Tile(4127, 5599), GuardianGeometry.tendrilTile(4127, 5599, cx, cy)) // on the centre
        // wiki: "If you start far away from the boss, you only need to move 1 tile towards the boss to avoid tendrils"
        val tendril = GuardianGeometry.tendrilTile(4135, 5599, cx, cy)
        assertEquals(Tile(4136, 5599), tendril)
        assertTrue(GuardianGeometry.inSwipe(tendril.x, tendril.y, 4135, 5599), "standing still is hit")
        assertFalse(GuardianGeometry.inSwipe(tendril.x, tendril.y, 4134, 5599), "one step toward the boss is safe")
    }

    @Test
    fun swipeIs3x3() {
        var n = 0
        for (x in 0..6) for (y in 0..6) if (GuardianGeometry.inSwipe(3, 3, x, y)) n++
        assertEquals(9, n)
    }

    /** §4.1 laser: an 18-tile Bresenham ray from the centre through the target's tile. */
    @Test
    fun rayPassesThroughTheAimAndHasTheLength() {
        val vectors = listOf(3 to 1, 1 to 3, -2 to 5, 7 to -4, -6 to -6, 0 to 9, -11 to 0, 5 to 2, 1 to 1, 13 to 4, 2 to 17)
        for ((dx, dy) in vectors) {
            val ray = GuardianGeometry.ray(100, 100, 100 + dx, 100 + dy, 18)
            assertEquals(Tile(100, 100), ray.first(), "starts at the origin for ($dx,$dy)")
            assertTrue(Tile(100 + dx, 100 + dy) in ray, "passes through the aim for ($dx,$dy)")
            assertEquals(19, ray.size, "18 steps plus the origin for ($dx,$dy)")
            assertTrue(ray.all { GuardianGeometry.chebyshev(100, 100, it.x, it.y) <= 18 })
            // one tile wide: consecutive tiles touch
            ray.zipWithNext().forEach { (a, b) -> assertEquals(1, GuardianGeometry.chebyshev(a.x, a.y, b.x, b.y)) }
        }
        val south = GuardianGeometry.ray(10, 10, 10, 10, 5)
        assertEquals(Tile(10, 5), south.last())
    }

    @Test
    fun rayExtendsBehindTheTarget() {
        // A player standing behind the target on the same line shares the beam.
        val ray = GuardianGeometry.ray(4127, 5599, 4127, 5594, 18).toSet()
        assertTrue(Tile(4127, 5590) in ray)
        assertTrue(Tile(4127, 5581) in ray)
        assertFalse(Tile(4127, 5580) in ray)
        assertFalse(Tile(4128, 5590) in ray)
    }

    /** The SE battery (SW 4139,5592; 2x2) is on the beam aimed at a player standing in front of its alcove. */
    @Test
    fun beamReachesTheSouthEastBattery() {
        val ray = GuardianGeometry.ray(4127, 5599, 4134, 5595, 18).toSet()
        val batteryTiles = (0..1).flatMap { dx -> (0..1).map { dy -> Tile(4139 + dx, 5592 + dy) } }
        assertTrue(batteryTiles.any { it in ray }, "ray $ray")
    }

    @Test
    fun mushroomCandidates() {
        val all = GuardianGeometry.mushroomCandidates(4125, 5597, 2, 6, walkable = { true }, taken = { false })
        assertTrue(all.none { GuardianGeometry.under(4125, 5597, 5, it.x, it.y) }, "never under the boss")
        assertTrue(all.all { GuardianGeometry.chebyshev(4127, 5599, it.x, it.y) in 3..6 })
        assertEquals(13 * 13 - 5 * 5, all.size)
        val taken = setOf(Tile(4130, 5599))
        val fewer = GuardianGeometry.mushroomCandidates(4125, 5597, 2, 6, walkable = { it.x != 4121 }, taken = { it in taken })
        assertFalse(Tile(4130, 5599) in fewer)
        assertTrue(fewer.none { it.x == 4121 })
    }

    @Test
    fun followStep() {
        val box = listOf(4116, 5585, 4138, 5613)
        assertNull(GuardianGeometry.followStep(4125, 5597, 4131, 5599, 6, box), "in range")
        assertEquals(Tile(4126, 5597), GuardianGeometry.followStep(4125, 5597, 4134, 5599, 6, box))
        assertEquals(Tile(4126, 5598), GuardianGeometry.followStep(4125, 5597, 4134, 5607, 6, box))
        // At the east edge of the box it cannot step east.
        assertEquals(Tile(4134, 5598), GuardianGeometry.followStep(4134, 5597, 4145, 5607, 6, box))
        assertNull(GuardianGeometry.followStep(4134, 5609, 4145, 5620, 6, box))
        assertTrue(GuardianGeometry.inBox(4116, 5585, 5, box))
        assertFalse(GuardianGeometry.inBox(4135, 5585, 5, box))
    }

    @Test
    fun tailStripIs7x2Behind() {
        val strip = GuardianGeometry.tailStrip(4125, 5597, 4127, 5590) // target south → strip north of the boss
        assertEquals(14, strip.size)
        assertTrue(strip.all { it.y in 5602..5603 && it.x in 4124..4130 })
        val west = GuardianGeometry.tailStrip(4125, 5597, 4140, 5599) // target east → strip west
        assertTrue(west.all { it.x in 4123..4124 && it.y in 5596..5602 })
    }

    @Test
    fun cone() {
        assertTrue(GuardianGeometry.inCone(0, 0, 0, -6, 1, -4, 2))
        assertTrue(GuardianGeometry.inCone(0, 0, 0, -6, -2, -9, 2))
        assertFalse(GuardianGeometry.inCone(0, 0, 0, -6, 3, -4, 2))
        assertFalse(GuardianGeometry.inCone(0, 0, 0, -6, 0, 3, 2), "behind the boss")
    }
}
