package com.gadscape.raids.tfa.rooms.ezekiel

import com.zenyte.game.util.Direction
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Sword casts, spawn layouts and the flight resolution (spec §4.2 Animated Swords). */
class SwordsTest {

    private val casts = SwordCast.DEFAULT_KEYS.map { SwordCast.parse(it)!! }

    @Test
    fun theSevenCastsHaveThreeOrFourSwords() {
        assertEquals(7, casts.size)
        assertEquals(listOf(3, 3, 3, 3, 3, 3, 4), casts.map { it.swordCount })
        assertEquals(null, SwordCast.parse("h_full+h_full"))
        assertEquals(null, SwordCast.parse("diagonal"))
        assertEquals(listOf(5532, 5535), SwordGrid.HORIZONTAL_OFFSET.lanes)
        assertEquals(listOf(4060, 4063), SwordGrid.VERTICAL_OFFSET.lanes)
        assertEquals(listOf(4059, 4065), SwordGrid.VERTICAL_FLUSH.lanes)
        assertEquals(listOf(5534), SwordGrid.HORIZONTAL_CENTER.lanes)
    }

    @Test
    fun swordsSpawnOnALaneEndFacingAcross() {
        val random = Random(7)
        repeat(50) {
            for (cast in casts) {
                val specs = SwordPatterns.plan(cast, random, 12, listOf(10, 13, 16))
                assertEquals(cast.swordCount, specs.size)
                for (s in specs) {
                    assertTrue(Rect.footprint(s.start.x, s.start.y, 3).let { it.minX >= 4059 && it.maxX <= 4067 && it.minY >= 5531 && it.maxY <= 5539 })
                    if (s.axis == SwordAxis.HORIZONTAL) {
                        assertEquals(s.lane, s.start.y)
                        assertTrue(s.start.x == 4059 || s.start.x == 4065)
                        assertEquals(if (s.start.x == 4059) 4065 else 4059, s.end.x)
                        assertEquals(if (s.start.x == 4059) Direction.EAST else Direction.WEST, s.direction)
                    } else {
                        assertEquals(s.lane, s.start.x)
                        assertTrue(s.start.y == 5531 || s.start.y == 5537)
                        assertEquals(if (s.start.y == 5531) Direction.NORTH else Direction.SOUTH, s.direction)
                    }
                }
                if (cast.grids.any { it.full }) {
                    assertEquals(setOf(10, 13, 16), specs.map { it.chargeTicks }.toSet(), "full grids charge at different rates")
                } else {
                    assertTrue(specs.all { it.chargeTicks == 12 })
                }
            }
        }
    }

    @Test
    fun flushPlusFlushTakesFourDistinctCorners() {
        val cast = SwordCast.parse("h_flush+v_flush")!!
        val random = Random(11)
        repeat(100) {
            val starts = SwordPatterns.plan(cast, random, 12, listOf(10, 13, 16)).map { it.start }
            assertEquals(4, starts.toSet().size, "spawn cells overlap: $starts")
        }
    }

    private fun hSword(lane: Int, fromWest: Boolean) = SwordSpec(
        0, SwordAxis.HORIZONTAL, lane,
        Tile(if (fromWest) 4059 else 4065, lane), Tile(if (fromWest) 4065 else 4059, lane),
        if (fromWest) Direction.EAST else Direction.WEST, 12,
    )

    private val free: (Tile) -> Boolean = { true }

    @Test
    fun aSwordOnHisSwLaneHitsHimAndStops() {
        val sword = hSword(5534, fromWest = true)
        val players = listOf(
            SwordFlight.Mover("between", Tile(4060, 5535), moving = false),
            SwordFlight.Mover("behind", Tile(4067, 5535), moving = false),
        )
        val r = SwordFlight.resolve(sword, 2, Tile(4063, 5534), players, true, free)
        assertTrue(r.hitsBoss)
        // The leading edge stops on his middle column: sword SW 4062, edge 4064 = his centre (his SW is 4063).
        assertEquals(Tile(4062, 5534), r.stop)
        assertEquals(3, r.travelTiles)
        assertEquals(30, r.travelCycles, "half the 60-cycle run")
        assertEquals(1, r.impactTicks)
        assertEquals(1, r.despawnTicks)
        assertEquals(listOf("between"), r.hit, "players beyond him are not reached")
    }

    @Test
    fun aMisalignedBossIsMissedAndTheSwordFliesTheWholeLane() {
        val sword = hSword(5534, fromWest = false)
        val r = SwordFlight.resolve(sword, 2, Tile(4062, 5535), listOf(SwordFlight.Mover("p", Tile(4060, 5536), false)), true, free)
        assertFalse(r.hitsBoss, "his SW y 5535 is not the lane's 5534")
        assertEquals(sword.end, r.stop)
        assertEquals(6, r.travelTiles)
        assertEquals(60, r.travelCycles)
        assertEquals(2, r.despawnTicks)
        assertEquals(listOf("p"), r.hit)
        // A vertical sword on his x lane hits him.
        val v = SwordSpec(1, SwordAxis.VERTICAL, 4062, Tile(4062, 5531), Tile(4062, 5537), Direction.NORTH, 12)
        assertTrue(SwordFlight.resolve(v, 2, Tile(4062, 5535), emptyList<SwordFlight.Mover<String>>(), true, free).hitsBoss)
        assertFalse(SwordFlight.resolve(v, 2, Tile(4063, 5535), emptyList<SwordFlight.Mover<String>>(), true, free).hitsBoss)
    }

    @Test
    fun aPlayerMovingOnTheLaunchTickRollsOutAndTakesNoDamage() {
        val sword = hSword(5534, fromWest = true)
        val players = listOf(
            // Moving north (step +y): rolls to the first tile above the band (y 5537).
            SwordFlight.Mover("mover", Tile(4061, 5535), moving = true, stepDx = 0, stepDy = 1, facing = Direction.NORTH.direction),
            SwordFlight.Mover("stander", Tile(4062, 5534), moving = false),
            // Moving along the lane while facing east: the nearer side (south, y 5533), sideways to its facing -> strafe.
            SwordFlight.Mover("strafer", Tile(4064, 5534), moving = true, stepDx = 1, stepDy = 0, facing = Direction.EAST.direction),
        )
        val r = SwordFlight.resolve(sword, 2, null, players, true, free)
        assertEquals(listOf("stander"), r.hit)
        val roll = r.dodges.first { it.who == "mover" }
        assertEquals(Tile(4061, 5537), roll.to)
        assertFalse(roll.strafe, "moving the way it faces is a roll")
        val strafe = r.dodges.first { it.who == "strafer" }
        assertEquals(Tile(4064, 5533), strafe.to)
        assertTrue(strafe.strafe, "a sideways move to its facing is a strafe")
        // With the dodge off, everyone in the band is hit.
        assertEquals(3, SwordFlight.resolve(sword, 2, null, players, false, free).hit.size)
    }

    @Test
    fun theDodgeUsesTheOtherSideWhenOneIsBlockedOrOffTheFloor() {
        val edge = hSword(5531, fromWest = true) // band y 5531-5533; below (5530) is off the floor
        val p = SwordFlight.Mover("p", Tile(4060, 5531), moving = true, stepDx = 0, stepDy = -1)
        assertEquals(Tile(4060, 5534), SwordFlight.dodgeTile(edge, p, free))
        val blocked: (Tile) -> Boolean = { it != Tile(4060, 5534) }
        assertEquals(null, SwordFlight.dodgeTile(edge, p, blocked), "no free side: no dodge")
        val r = SwordFlight.resolve(edge, 2, null, listOf(p), true, blocked)
        assertEquals(listOf("p"), r.hit)
    }

    @Test
    fun damageScalesWithEmpower() {
        assertEquals(15, SwordFlight.playerDamage(15, 25, 0.0))
        assertEquals(28, SwordFlight.playerDamage(15, 25, 0.5))
        assertEquals(40, SwordFlight.playerDamage(15, 25, 1.0))
        assertEquals(150, SwordFlight.bossDamage(3000, 5, 0.0))
        assertEquals(300, SwordFlight.bossDamage(3000, 5, 1.0))
        assertEquals(406, SwordFlight.bossDamage(8127, 5, 0.0))
    }

    @Test
    fun sidewaysIsWithinFortyFiveDegreesOfPerpendicular() {
        val north = Direction.NORTH.direction
        assertTrue(SwordFlight.isSideways(north, 1, 0))
        assertTrue(SwordFlight.isSideways(north, -1, 0))
        assertFalse(SwordFlight.isSideways(north, 0, 1))
        assertFalse(SwordFlight.isSideways(north, 0, -1))
        assertFalse(SwordFlight.isSideways(north, 0, 0))
        assertNotNull(EzekielArena.directionOf(1, 1))
    }
}
