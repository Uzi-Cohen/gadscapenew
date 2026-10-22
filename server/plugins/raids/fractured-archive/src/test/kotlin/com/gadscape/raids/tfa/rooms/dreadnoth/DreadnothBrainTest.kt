package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.StaticTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** WU-24 acceptance tests (spec §6.4) on the engine-free boss script. */
class DreadnothBrainTest {

    private val melee = TfaIds.Seq.DREADNOTH_MELEE
    private val hop = TfaIds.Seq.DREADNOTH_HOP
    private val jump = TfaIds.Seq.DREADNOTH_JUMP
    private val death = TfaIds.Seq.DREADNOTH_DEATH

    private fun Harness.anims(seq: Int) = host.of("anim").filter { it.a == seq }

    // ------------------------------------------------------------------------------------------------- chase

    @Test
    fun movesOneTilePerTwoTicks() {
        val h = Harness()
        h.host.sw = StaticTile(4194, 5661, 0) // footprint x 4194..4198
        h.add("p", 4185, 5663)                // 9 tiles west: out of reach for the whole test
        h.brain.start()
        h.tick(10)
        val steps = h.host.of("step")
        assertEquals(5, steps.size, "one tile every two ticks [DOC]")
        assertTrue(steps.all { it.tick % 2 == 0 }, "on even ticks only")
        assertTrue(steps.all { it.a == -1 && it.b == 0 }, "straight toward the target")
        assertTrue(h.host.of("attack").isEmpty(), "nobody in reach: no melee")
    }

    @Test
    fun stopsWhenTheTargetIsInReach() {
        val h = Harness()
        h.add("p", 4187, 5663) // 2 tiles west of the footprint
        h.brain.start()
        h.tick(8)
        assertEquals(1, h.host.of("step").size)
        assertEquals(StaticTile(4188, 5661, 0), h.host.sw)
    }

    @Test
    fun aoeMeleeEveryFourTicksOnEveryoneInReachAndBloodEveryThird() {
        val h = Harness()
        val a = h.add("a", 4188, 5663) // west side, in reach
        val b = h.add("b", 4191, 5663) // under him
        val c = h.add("c", 4185, 5663) // out of reach
        h.brain.start()
        h.tick(13)
        val swings = h.anims(melee).map { it.tick }
        assertEquals(listOf(1, 5, 9, 13), swings, "attack speed 4")
        val hits = h.host.of("attack")
        assertTrue(hits.all { it.style == AttackStyle.MELEE && it.a == 45 })
        assertEquals(listOf(2, 2, 6, 6, 10, 10), hits.map { it.tick }, "lands 1 t after the swing")
        assertEquals(setOf(a, b), hits.map { it.target }.toSet(), "the 7x7 only, under him included")
        assertFalse(hits.any { it.target == c })
        val blood = h.host.of("blood")
        assertEquals(1, blood.size, "a blood tile on every 3rd melee")
        assertEquals(10, blood[0].tick)
        assertTrue(blood[0].tile in listOf(a.tile, b.tile, c.tile), "under a random player in the arena")
    }

    @Test
    fun meleeNeedsSomeoneInReachAndThenSwingsAtOnce() {
        val h = Harness()
        val p = h.add("p", 4185, 5663)
        h.brain.start()
        h.tick(3)
        assertTrue(h.anims(melee).isEmpty())
        p.tile = StaticTile(4188, 5663, 0)
        h.tick(1)
        assertEquals(listOf(4), h.anims(melee).map { it.tick })
    }

    // -------------------------------------------------------------------------------------------- thresholds

    @Test
    fun threshold85RunsOneCycle() {
        val h = Harness()
        val p = h.add("p", 4185, 5663)
        val q = h.add("q", 4197, 5669)
        h.brain.start()
        h.tick(2)
        h.hpTo(84)
        h.tick(1)
        val s = h.now
        assertEquals(listOf(s), h.anims(hop).map { it.tick }, "hop 14777 starts the cycle")
        assertEquals(DreadnothBrain.State.THRESHOLD, h.brain.state)
        h.tick(16)
        val slam = h.host.of("attack").filter { it.tick == s + 1 }
        assertEquals(setOf<Fighter>(p, q), slam.map { it.target }.toSet(), "arena-wide slam")
        assertTrue(slam.all { it.style == AttackStyle.MELEE && it.a == 45 })
        val volleys = h.host.of("projectile").map { it.tick }.distinct()
        assertEquals(listOf(s + 3, s + 7, s + 11), volleys)
        assertEquals(6, h.host.of("projectile").size, "every player, every volley")
        val ranged = h.host.of("attack").filter { it.style == AttackStyle.RANGED }
        assertEquals(6, ranged.size)
        assertTrue(ranged.all { it.a == 34 && it.tick > s + 3 })
        val jellies = h.host.of("jellies")
        assertEquals(listOf(s + 14), jellies.map { it.tick }, "the batch at the end of the cycle")
        assertEquals(6, jellies[0].a, "min(12, 4 + n) at n = 2")
        assertEquals(DreadnothBrain.State.CHASE, h.brain.state)
        assertTrue(h.anims(melee).none { it.tick in s + 1 until s + 14 && it.a == melee && h.host.of("projectile").none { p2 -> p2.tick == it.tick } },
            "no melee during the sequence (only volley swings)")
        assertTrue(h.host.of("step").none { it.tick in s until s + 14 }, "no movement during the sequence")
    }

    @Test
    fun thresholds65And45RunTwoAndThreeCycles() {
        val h = Harness()
        h.add("p", 4185, 5663)
        h.brain.start()
        h.hpTo(84)
        h.tick(20)
        h.host.events.clear()
        h.hpTo(64)
        h.tick(1)
        val s = h.now
        h.tick(30)
        assertEquals(listOf(s, s + 14), h.anims(hop).map { it.tick })
        assertEquals(6, h.host.of("projectile").size)
        assertEquals(listOf(3, 3), h.host.of("jellies").map { it.a })
        h.host.events.clear()
        h.hpTo(44)
        h.tick(1)
        val s3 = h.now
        h.tick(44)
        assertEquals(listOf(s3, s3 + 14, s3 + 28), h.anims(hop).map { it.tick })
        assertEquals(9, h.host.of("projectile").size)
        assertEquals(listOf(2, 2, 2), h.host.of("jellies").map { it.a })
    }

    @Test
    fun oneHitThroughSeveralThresholdsQueuesThemInOrder() {
        val h = Harness()
        h.add("p", 4185, 5663)
        h.brain.start()
        h.tick(1)
        h.hpTo(40)
        assertEquals(
            listOf(DreadnothBrain.Step.Threshold(85, 1), DreadnothBrain.Step.Threshold(65, 2), DreadnothBrain.Step.Threshold(45, 3)),
            h.brain.queued,
        )
        h.tick(1)
        val s = h.now
        h.tick(6 * 14 + 2)
        assertEquals((0 until 6).map { s + 14 * it }, h.anims(hop).map { it.tick }, "1 + 2 + 3 cycles back to back")
        assertEquals(listOf(6, 3, 3, 2, 2, 2), h.host.of("jellies").map { it.a })
        assertEquals(DreadnothBrain.State.CHASE, h.brain.state)
    }

    @Test
    fun scaledJellyBatches() {
        val h = Harness(scale = 8)
        h.add("p", 4185, 5663)
        h.brain.start()
        h.hpTo(84)
        h.tick(16)
        assertEquals(listOf(12), h.host.of("jellies").map { it.a }, "capped at 12 for 8 players")
    }

    @Test
    fun multihitModeHopsKTimesThenThreeVolleys() {
        val cfg = Harness.DEFAULT_CONFIG.copy(thresholdMode = DreadnothConfig.ThresholdMode.MULTIHIT)
        val h = Harness(cfg)
        h.add("p", 4185, 5663)
        h.brain.start()
        h.hpTo(84)
        h.tick(20)
        h.hpTo(64)
        h.tick(30)
        h.host.events.clear()
        h.hpTo(44)
        h.tick(1)
        val s = h.now
        h.tick(40)
        assertEquals(listOf(s, s + 2, s + 4), h.anims(hop).map { it.tick }, "one slam per hop, k hops")
        assertEquals(3, h.host.of("attack").count { it.tick in listOf(s + 1, s + 3, s + 5) && it.a == 45 })
        assertEquals(listOf(s + 7, s + 11, s + 15), h.host.of("projectile").map { it.tick }, "then three volleys")
        assertEquals(listOf(6), h.host.of("jellies").map { it.a }, "one batch")
    }

    // ------------------------------------------------------------------------------------------------ enrage

    @Test
    fun enrageJumpsEveryEightTicksWithTheShadowThreeTicksBeforeLanding() {
        val h = Harness()
        val p = h.add("p", 4185, 5657)
        p.prayers += Protect.MELEE
        h.brain.start()
        h.hpTo(24)
        assertEquals(
            listOf(DreadnothBrain.Step.Threshold(85, 1), DreadnothBrain.Step.Threshold(65, 2), DreadnothBrain.Step.Threshold(45, 3), DreadnothBrain.Step.Enrage),
            h.brain.queued, "the enrage queues behind the thresholds",
        )
        h.brain.debugReset(25)
        h.hpTo(24)
        h.tick(1)
        assertEquals(DreadnothBrain.State.ENRAGE, h.brain.state)
        h.tick(30)
        val jumps = h.anims(jump).map { it.tick }
        assertTrue(jumps.size >= 3)
        assertEquals(listOf(8, 8), jumps.zipWithNext { a, b -> b - a }.take(2), "a jump every 8 t")
        val j = jumps[0]
        val shadow = h.host.of("graphic").first { it.a == TfaIds.Spot.DREADNOTH_JUMP_SHADOW }
        assertEquals(j, shadow.tick, "5x5 shadow 4209 at take-off")
        val area = DreadnothArena.landingArea(StaticTile(4185, 5657, 0))
        assertEquals(DreadnothArena.centreOf(area), shadow.tile)
        val leap = h.host.of("leap").first()
        assertEquals(area, leap.tile)
        assertEquals(2 to 88, leap.a to leap.b, "flight between the take-off and landing synths")
        val untarget = h.host.of("targetable")
        assertTrue(untarget.any { it.tick == j && it.a == 0 } && untarget.any { it.tick == j + 3 && it.a == 1 }, "untargetable while airborne")
        val landing = h.host.of("damage").first()
        assertEquals(j + 3, landing.tick)
        assertTrue(landing.a in 20..35, "40-70 halved by Protect from Melee")
        val heal = h.host.of("heal").first()
        assertEquals(j + 3, heal.tick)
        assertTrue(heal.a in 30..40)
        val follow = h.host.of("projectile").filter { it.tick == j + 4 }
        assertEquals(listOf<Fighter?>(p), follow.map { it.target }, "ranged follow-up at the jumped player")
        val splat = h.host.of("projectileTile").first { it.tick == j + 4 }
        assertEquals(TfaIds.Spot.DREADNOTH_SPLATTER_PROJ, splat.a)
        assertEquals(StaticTile(4185, 5657, 0), splat.tile)
        val blood = h.host.of("blood").first { it.tick > j }
        assertEquals(j + 6, blood.tick, "the splatter leaves blood where it lands (t6)")
        assertEquals(StaticTile(4185, 5657, 0), blood.tile)
        assertTrue(h.anims(melee).none { it.tick >= j }, "no ordinary melee in enrage")
    }

    @Test
    fun landingHitsTheAreaFullWithoutPrayerAndEveryoneElseWithoutProtectFromMelee() {
        val h = Harness()
        val target = h.add("target", 4191, 5663)
        val far = h.add("far", 4185, 5669)
        val farPraying = h.add("farPraying", 4197, 5657).also { it.prayers += Protect.MELEE }
        h.brain.start()
        h.brain.debugReset(25)
        h.hpTo(25)
        h.tick(2) // enters enrage, first jump next tick
        h.tick(1)
        val j = h.anims(jump).first().tick
        h.tick(4)
        val dmg = h.host.of("damage").filter { it.tick == j + 3 }
        val outside = h.host.of("attack").filter { it.tick == j + 3 }
        val l = h.brain.landing
        assertNotNull(l)
        val inside = listOf(target, far, farPraying).filter { DreadnothArena.inFootprint(l, it.tile) }
        assertEquals(inside.toSet(), dmg.map { it.target }.toSet())
        assertTrue(dmg.all { it.a in 40..70 })
        assertEquals(dmg.size, h.host.of("heal").count { it.tick == j + 3 }, "30-40 healed per player hit")
        val expectedOutside = listOf(target, far).filter { it !in inside }
        assertEquals(expectedOutside.toSet(), outside.map { it.target }.toSet(), "no Protect from Melee → hit anywhere")
        assertTrue(outside.all { it.style == AttackStyle.MELEE && it.a == 45 })
    }

    // ------------------------------------------------------------------------------------------------- death

    @Test
    fun deathCancelsTheScriptSpawnsTheWaveAndDespawns() {
        val h = Harness()
        h.add("p", 4185, 5663)
        h.brain.start()
        h.hpTo(84)
        h.tick(4) // mid-cycle: a volley in flight, more pending
        h.host.events.clear()
        h.host.hp = 0
        h.brain.onDeath()
        val d = h.now
        assertEquals(listOf(d), h.anims(death).map { it.tick })
        assertTrue(h.host.of("targetable").any { it.a == 0 })
        h.tick(10)
        assertTrue(h.host.of("projectile").isEmpty(), "no volley after death")
        assertTrue(h.host.of("attack").isEmpty(), "in-flight hits cancelled")
        assertTrue(h.host.of("jellies").isEmpty(), "the threshold batch never comes")
        assertEquals(listOf(d + 2 to 10), h.host.of("deathWave").map { it.tick to it.a }, "about ten jellies at +2 t")
        assertEquals(listOf(d + 5), h.host.of("despawn").map { it.tick })
        assertTrue(h.brain.deathWaveSpawned)
        assertEquals(DreadnothBrain.State.DEAD, h.brain.state)
        h.hpTo(10)
        assertTrue(h.brain.queued.isEmpty(), "no thresholds once dead")
    }

    @Test
    fun deadOrLostPlayersAreNotHit() {
        val h = Harness()
        val p = h.add("p", 4188, 5663)
        h.brain.start()
        h.tick(1) // swing at p
        p.alive = false
        h.brain.onFighterLost(p)
        h.tick(1)
        assertTrue(h.host.of("attack").isEmpty())
    }

    @Test
    fun debugResetSkipsEarlierThresholds() {
        val h = Harness()
        h.add("p", 4185, 5663)
        h.brain.start()
        h.brain.debugReset(65)
        h.hpTo(65)
        assertEquals(listOf<DreadnothBrain.Step>(DreadnothBrain.Step.Threshold(65, 2)), h.brain.queued)
        h.brain.debugReset(25)
        h.hpTo(25)
        assertEquals(listOf<DreadnothBrain.Step>(DreadnothBrain.Step.Enrage), h.brain.queued)
    }

    @Test
    fun sameSeedSameFight() {
        fun run(): List<Ev> {
            val h = Harness(seed = 42)
            h.add("a", 4188, 5663)
            h.add("b", 4194, 5666)
            h.add("c", 4186, 5658)
            h.brain.start()
            h.tick(3)
            h.hpTo(20)
            h.tick(120)
            return h.host.events.map { it.copy(target = null) }
        }
        assertEquals(run(), run())
    }
}
