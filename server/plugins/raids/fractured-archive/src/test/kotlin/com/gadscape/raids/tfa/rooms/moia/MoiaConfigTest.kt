package com.gadscape.raids.tfa.rooms.moia

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.encounter.RangedDefence
import com.gadscape.raids.tfa.encounter.Scaling
import com.gadscape.raids.tfa.raid.StaticTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoiaConfigTest {

    private val cfg: MoiaConfig = TfaConfigTestKit.load(listOf(MoiaConfig))[MoiaConfig]

    @Test
    fun bundledMatchesDefaults() = TfaConfigTestKit.assertBundledMatchesDefaults(MoiaConfig)

    @Test
    fun specJsonBlockValues() {
        // spec §4.4 "House defaults (moia.json)"
        assertEquals(0.3333, cfg.enrageFraction)
        assertEquals(5, cfg.attackSpeed)
        assertEquals(32, cfg.rangedMax)
        assertEquals(34, cfg.magicMax)
        assertEquals(4, cfg.specialEveryNthAuto)
        assertEquals(30, cfg.infect.interval)
        assertEquals(20, cfg.infect.duration)
        assertEquals(4, cfg.infect.coughEvery)
        assertEquals(4..8, cfg.infect.dmg)
        assertEquals(0.25, cfg.infect.serpMult)
        assertEquals(20, cfg.infect.binderDmg)
        assertEquals(12, cfg.binders.respawn)
        assertEquals(8, cfg.binders.shuffle)
        assertEquals(22, cfg.binders.max)
        assertEquals(2, cfg.binders.reach)
        assertEquals(6, cfg.binders.despawn)
        assertEquals(8, cfg.enrage.set)
        assertEquals(25, cfg.enrage.chargeTicks)
        assertEquals(MoiaArm.W, cfg.startArm)
        assertEquals(1064, cfg.combatLevel)
        assertEquals(1900, cfg.baseHp)
        assertEquals(95, cfg.binders.hp)
    }

    @Test
    fun binderSetSizeByScale() {
        // min(12, 2 + 2 × scale): 6 at 2-scale, 10 at 4, 12 at 5+
        assertEquals(listOf(6, 8, 10, 12, 12, 12, 12), (2..8).map { cfg.binders.setSize(it) })
    }

    @Test
    fun traitsAreTheDocumentedOnes() {
        val t = cfg.traits
        assertEquals(60, t.defenceFloorPct)
        assertEquals(117, t.defenceFloor(195)) // hotfix: Moia 117
        assertEquals(Element.AIR, t.weakness)
        assertEquals(80, t.weaknessPct)
        assertEquals(RangedDefence(60, 120, 140), t.rangedDef)
        assertTrue(t.immunePoison && t.immuneVenom && t.immuneCannon)
        assertEquals(null, cfg.binderTraits.defenceFloorPct)
        assertTrue(!cfg.binderTraits.immunePoison)
    }

    @Test
    fun cacheStatProfiles() {
        assertEquals(listOf(1, 1, 195, 350, 200), cfg.stats.levels)
        assertEquals(listOf(20, 110, 15, 30, 120), cfg.stats.defence)
        assertEquals(listOf(1, 170, 225, 50, 1), cfg.binderStats.levels)
        assertEquals(listOf(12, 15, 10, -20, 0), cfg.binderStats.defence)
    }

    @Test
    fun scaledHpAndLevelFollowTheSpecTable() {
        val s = Scaling.DEFAULT
        assertEquals(listOf(1900, 2712, 3524, 4335, 5147, 5959, 6771), (2..8).map { s.hp(cfg.baseHp, it) })
        assertEquals(1064, s.combatLevel(MoiaNpc.SCALING_KEY))
    }

    @Test
    fun overrideFileChangesOneKey() {
        val snap = TfaConfigTestKit.load(listOf(MoiaConfig), overrides = mapOf("moia.json" to """{"enrage": {"chargeTicks": 30}, "arms": {"W": [4054, 5663, 0]}}"""))
        val c = snap[MoiaConfig]
        assertEquals(30, c.enrage.chargeTicks)
        assertEquals(8, c.enrage.set)
        assertEquals(StaticTile(4054, 5663), c.arms[MoiaArm.W])
        assertEquals(StaticTile(4070, 5662), c.arms[MoiaArm.E])
    }
}
