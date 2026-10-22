package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.encounter.RangedDefence
import com.gadscape.raids.tfa.encounter.Scaling
import com.gadscape.raids.tfa.raid.StaticTile
import com.zenyte.game.world.entity.npc.combat.CombatTraitRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DreadnothConfigTest {

    private val cfg = Harness.DEFAULT_CONFIG

    @Test
    fun bundledMatchesDefaults() = TfaConfigTestKit.assertBundledMatchesDefaults(DreadnothConfig)

    /** The spec §4.5 "House defaults" block, key by key. */
    @Test
    fun specHouseBlock() {
        assertEquals(2, cfg.moveEveryTicks)
        assertEquals(4, cfg.attackSpeed)
        assertEquals(45, cfg.meleeMax)
        assertEquals(45, cfg.slamMax)
        assertEquals(34, cfg.rangedMax)
        assertEquals(listOf(85, 65, 45), cfg.thresholds)
        assertEquals(DreadnothConfig.ThresholdMode.CYCLES, cfg.thresholdMode)
        assertEquals(listOf(3, 7, 11), cfg.volleyTicks)
        assertEquals(14, cfg.cycleLength)
        assertEquals(25, cfg.enragePct)
        with(cfg.jump) {
            assertEquals(8, period)
            assertEquals(3, landTick)
            assertEquals(40..70, landingDmg)
            assertEquals(0.5, pfmMult)
            assertEquals(30..40, heal)
            assertEquals(45, noPrayerMax)
        }
        with(cfg.jelly) {
            assertEquals(75, hp)
            assertEquals(4, perThresholdBase)
            assertEquals(1, perScale)
            assertEquals(12, cap)
            assertEquals(10, deathWave)
            assertEquals(17, max)
            assertEquals(2, prayerDrain)
            assertEquals(3, spawnTicks)
            assertTrue(rangedWhenBlocked)
        }
        with(cfg.blood) {
            assertEquals(6..8, dmg)
            assertEquals(30, lifetime)
            assertEquals(3, periodicEveryNthMelee)
        }
    }

    /** §4.5 max hits are the NPC formula floor(((lvl+9)×(bonus+64)+320)/640). */
    @Test
    fun maxHitsFollowTheNpcFormula() {
        fun formula(level: Int, bonus: Int) = ((level + 9) * (bonus + 64) + 320) / 640
        assertEquals(cfg.meleeMax, formula(cfg.stats.levels[1], cfg.stats.otherBonuses[0]))
        assertEquals(cfg.rangedMax, formula(cfg.stats.levels[4], cfg.stats.otherBonuses[1]))
        assertEquals(cfg.jelly.max, formula(cfg.jelly.stats.levels[1], cfg.jelly.stats.otherBonuses[0]))
    }

    /** §4.5 combat profile → traits. */
    @Test
    fun combatProfile() {
        val t = cfg.traits
        assertNull(t.defenceFloorPct, "hotfix: Dreadnoth has no defence-reduction cap")
        assertEquals(0, t.defenceFloor(125), "Defence can be drained to 0")
        assertEquals(Element.FIRE, t.weakness)
        assertEquals(85, t.weaknessPct)
        assertEquals(RangedDefence(100, 80, 80), t.rangedDef)
        assertEquals(40, t.freezeResistPct)
        assertEquals(25, t.demonbanePct)
        assertTrue(t.immunePoison && t.immuneCannon)
        val j = cfg.jelly.traits
        assertNull(j.defenceFloorPct)
        assertEquals(Element.FIRE, j.weakness)
        assertEquals(60, j.weaknessPct)
        assertEquals(RangedDefence(-45, 18, -50), j.rangedDef)
        assertEquals(20, j.freezeResistPct)
        assertEquals(100, j.demonbanePct, "a demon with normal demonbane")
        assertTrue(j.immuneCannon)
        assertFalse(j.immunePoison, "adds can be poisoned (§3.11)")
    }

    /** WU-04 test 4 and §4.5: Ice Barrage freezes Dreadnoth 19 t, a jelly 25 t; Arclight +70% → +17.5%. */
    @Test
    fun freezeAndDemonbaneThroughTheEngineRules() {
        assertEquals(19, CombatTraitRules.resistedFreeze(32, cfg.traits.freezeResistPct))
        assertEquals(25, CombatTraitRules.resistedFreeze(32, cfg.jelly.traits.freezeResistPct))
        assertEquals(0.175, CombatTraitRules.scaledDemonbaneBonus(0.70, cfg.traits.demonbanePct!!), 1e-9)
    }

    /** §2.6 table: 2100 at n = 2 ... 7484 at n = 8 (shared encounter test: HP at n = 2 and n = 6). */
    @Test
    fun scaledHp() {
        val expected = listOf(2100, 2997, 3895, 4792, 5689, 6586, 7484)
        for ((i, hp) in expected.withIndex()) assertEquals(hp, Scaling.DEFAULT.hp(cfg.baseHp, i + 2), "n=${i + 2}")
        assertEquals(2100, Scaling.DEFAULT.hp(cfg.baseHp, 1), "solo counts as 2")
    }

    @Test
    fun arenaDefaults() {
        assertEquals(StaticTile(4189, 5661, 0), cfg.arena.spawn)
        assertEquals(DreadnothArena.CENTRE, DreadnothArena.centreOf(cfg.arena.spawn), "spawned centred on the octagram")
    }

    /** §4.5: 4211 arrives at 30 + 5d cycles; 4212 lands 2 t after the throw (t4 → t6). */
    @Test
    fun projectileTiming() {
        val ranged = cfg.projectiles.ranged
        assertEquals(60, ranged.cycles(6))
        assertEquals(2, ranged.flightTicks(6))
        assertEquals(3, ranged.flightTicks(7))
        assertEquals(1, ranged.flightTicks(0))
        for (d in 0..14) assertEquals(2, cfg.projectiles.splatter.flightTicks(d))
    }

    @Test
    fun overridesFallBackPerKey() {
        val snap = TfaConfigTestKit.load(
            listOf(DreadnothConfig),
            overrides = mapOf("dreadnoth.json" to """{ "thresholds": [45, 65], "jelly": { "cap": 9 }, "thresholdMode": "bogus" }"""),
        )
        val c = snap[DreadnothConfig]
        assertEquals(listOf(85, 65, 45), c.thresholds, "non-decreasing thresholds are rejected")
        assertEquals(9, c.jelly.cap)
        assertEquals(DreadnothConfig.ThresholdMode.CYCLES, c.thresholdMode)
        assertTrue(snap.issuesOf(DreadnothConfig).size >= 2)
    }
}
