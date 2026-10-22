package com.gadscape.raids.tfa.rooms.moia

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WU-23: "she only stands on arms", "melee cannot reach in phase 1", "teleport special lands off the 5-t grid". */
class MoiaArenaTest {

    private val cfg: MoiaConfig = TfaConfigTestKit.load(listOf(MoiaConfig))[MoiaConfig]

    private val floor: List<StaticTile> = (MoiaGeometry.FLOOR_MIN_X..MoiaGeometry.FLOOR_MAX_X).flatMap { x ->
        (MoiaGeometry.FLOOR_MIN_Y..MoiaGeometry.FLOOR_MAX_Y).map { y -> StaticTile(x, y) }
    }

    @Test
    fun graveyardIsNineByNine() {
        assertEquals(81, floor.size)
        assertEquals(StaticTile(4063, 5663), MoiaGeometry.CENTRE)
    }

    @Test
    fun armsAreOutsideMeleeAndHalberdReach() {
        for (arm in MoiaArm.OUTER) {
            val sw = cfg.arms.getValue(arm)
            val nearest = floor.minOf { MoiaGeometry.footprintDistance(sw, MoiaGeometry.MOIA_SIZE, it) }
            val farthest = floor.maxOf { MoiaGeometry.footprintDistance(sw, MoiaGeometry.MOIA_SIZE, it) }
            assertEquals(3, nearest, "$arm: the near fence row is 3 tiles from Moia")
            assertEquals(11, farthest, "$arm: the far row is 11")
            // no tile of her 3x3 is on the floor
            for (dx in 0 until 3) for (dy in 0 until 3) assertTrue(!MoiaGeometry.onFloor(sw.x + dx, sw.y + dy))
        }
        // the enrage centre is inside the graveyard, around the middle grave cell: melee reaches her there
        val centre = cfg.arms.getValue(MoiaArm.CENTRE)
        assertEquals(0, MoiaGeometry.footprintDistance(centre, MoiaGeometry.MOIA_SIZE, MoiaGeometry.CENTRE))
    }

    @Test
    fun teleportAlwaysPicksAnotherOuterArm() {
        val random = Random(1)
        for (arm in MoiaArm.OUTER) repeat(200) {
            val next = MoiaArm.randomOther(arm, random)
            assertTrue(next != arm && next.isOuter)
        }
        // all three others are used
        assertEquals(3, (0 until 300).map { MoiaArm.randomOther(MoiaArm.W, random) }.toSet().size)
    }

    @Test
    fun specialLandsOffTheGridAndNeverWithADagger() {
        val plan = MoiaAttackPlan(cfg.attackSpeed, cfg.specialEveryNthAuto, 10_000)
        val start = 100
        plan.start(start)
        var specialTick = -1
        for (t in start until start + 40) {
            if (plan.poll(t, canInfect = false) == MoiaAttackPlan.Attack.DAGGER_THEN_SPECIAL) { specialTick = t; break }
        }
        assertEquals(start + 15, specialTick, "the 4th auto is the special")
        for (from in MoiaArm.OUTER) for (to in MoiaArm.OUTER) {
            if (from == to) continue
            for (target in floor) {
                val newSw = cfg.arms.getValue(to)
                val oldSw = cfg.arms.getValue(from)
                val land = specialTick + cfg.special.cast + MoiaTiming.magicDelay(cfg, middle(newSw, target))
                assertTrue((land - start) % cfg.attackSpeed != 0, "magic on the grid: $from->$to $target")
                // dagger landings on the same target: the one thrown on T (old arm) and the next two (new arm)
                val daggers = listOf(specialTick to oldSw, specialTick + 5 to newSw, specialTick + 10 to newSw).map { (thrown, sw) ->
                    thrown + MoiaTiming.landingDelay(cfg.projectiles.dagger, foot(sw, target), middle(sw, target))
                }
                assertTrue(land !in daggers, "magic lands with a dagger: $from->$to $target ($land vs $daggers)")
                // the projectile reaches the target no later than the hit
                assertTrue(cfg.projectiles.magic.flightCycles(middle(newSw, target)) <= 30 * (land - specialTick - cfg.special.cast))
            }
        }
    }

    @Test
    fun projectilesNeverLandAfterTheirHit() {
        for (arm in MoiaArm.OUTER) for (target in floor) {
            val sw = cfg.arms.getValue(arm)
            for (spec in listOf(cfg.projectiles.dagger, cfg.projectiles.infect)) {
                val delay = MoiaTiming.landingDelay(spec, foot(sw, target), middle(sw, target))
                assertTrue(spec.flightCycles(middle(sw, target)) <= delay * 30)
                assertTrue(delay >= spec.hitDelay(foot(sw, target)))
            }
        }
        // the standard ranged delay: 2 t up close, 3 t from the far row
        assertEquals(2, cfg.projectiles.dagger.hitDelay(3))
        assertEquals(3, cfg.projectiles.dagger.hitDelay(11))
    }

    @Test
    fun slopeConversionRoundTrips() {
        // Projectile turns degrees into client units with round(deg / (90 / 64))
        for (slope in 0..64) {
            val deg = MoiaTiming.slopeDegrees(slope)
            assertEquals(slope, Math.round(deg / (90f / 64f)))
        }
    }

    private fun foot(sw: StaticTile, t: StaticTile) = MoiaGeometry.footprintDistance(sw, MoiaGeometry.MOIA_SIZE, t)

    private fun middle(sw: StaticTile, t: StaticTile) = maxOf(kotlin.math.abs(sw.x + 1 - t.x), kotlin.math.abs(sw.y + 1 - t.y))
}
