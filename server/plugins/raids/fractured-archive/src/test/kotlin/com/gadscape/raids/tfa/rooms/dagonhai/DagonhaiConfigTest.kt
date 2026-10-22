package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.encounter.RangedDefence
import com.gadscape.raids.tfa.encounter.Scaling
import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DagonhaiConfigTest {

    private val cfg: DagonhaiConfig = TfaConfigTestKit.load(listOf(DagonhaiConfig))[DagonhaiConfig]

    @Test
    fun bundledMatchesDefaults() = TfaConfigTestKit.assertBundledMatchesDefaults(DagonhaiConfig)

    /** spec §4.6 "House defaults (dagonhai.json)", key for key. */
    @Test
    fun specJsonBlockValues() {
        assertEquals(640, cfg.pool2)
        assertEquals(5, cfg.breaks)
        assertEquals(200, cfg.stabilityPerBreak)
        assertEquals(4, cfg.ritualQuarters)
        assertEquals(7, cfg.finalHp)
        assertEquals(6, cfg.elder.speed)
        assertEquals(27, cfg.elder.rangedMax)
        assertEquals(mapOf(Element.FIRE to 24, Element.WATER to 22, Element.AIR to 21, Element.EARTH to 23), cfg.elder.surgeMax)
        assertEquals(4, cfg.elder.focusAttacks)
        assertEquals(1, cfg.elder.splash)
        assertEquals(15, cfg.channel.first)
        assertEquals(20..25, cfg.channel.interval)
        assertEquals(12, cfg.channel.length)
        assertEquals(4..8, cfg.monk.searchTicks)
        assertEquals(4, cfg.monk.throwRelease)
        assertEquals(2, cfg.monk.flight)
        assertEquals(89, cfg.poolHeadbar)
        val t = cfg.tome
        assertTrue(t.addsProgress)
        assertEquals(listOf(55, 20, 25, 15, 20), listOf(t.bandosBlocker, t.bandosRing, t.zamBlocker, t.zamBlast, t.zamCauldronTiles))
        assertEquals(listOf(3, 3, 2, 2, 1, 1), t.zamBurn)
        assertEquals(listOf(20, 10, 10, 5, 20, 10), listOf(t.saraHeal, t.saraTiles, t.saraTileHeal, t.saraTilePrayer, t.saraTileDmg, t.saraPoolHealPct))
        val p = cfg.protect
        assertEquals(listOf(3, 2, 44, 20, 30), listOf(p.rifts, p.impsPerRift, p.impHp, p.impMax, p.maxTicks))
        assertEquals(2.0, p.strengthMult)
        val e = cfg.empowered
        assertEquals(listOf(5, 2, 45, 30), listOf(e.speed, e.elderHits, e.elderMax, e.monkMax))
        assertEquals(0.75, e.prayerMult)
        assertEquals(6, cfg.explosion.monkStun)
        assertEquals(3, cfg.explosion.elderStun)
        assertEquals(Role.BOOK, cfg.explosion.dies)
    }

    /** Cache timings the room relies on (seqlen_named.txt: cycles / 30). */
    @Test
    fun cacheTimings() {
        assertEquals(1000, cfg.stability)
        assertEquals(112, cfg.monk.releaseCycle)          // throw seqs: release synth at frame 29 = cycle 112
        assertEquals(listOf(3, 4, 5, 6), cfg.tome.zamFuses) // 14664-14667: 90 / 120 / 150 / 180 cycles
        assertEquals(listOf(5, 6), cfg.tome.saraHealStrikes) // 7818 / 7819: 150 / 180 cycles
        assertEquals(5, cfg.tome.saraDamageStrike)
        assertEquals(6, cfg.protect.domeEvery)              // 9601: 180 cycles
        assertEquals(2, cfg.protect.impDespawn)             // 14530: 60 cycles
        assertEquals(5, cfg.explosion.deathTicks)           // 14654: 125 cycles
        assertEquals(2, cfg.explosion.cauldronTicks)        // 14679: 60 cycles
        assertEquals(5, cfg.finale.deathTicks)              // 14652 / 14653: 125 cycles
        assertEquals(4, cfg.protect.impSpeed)               // npc 16650 param 14
    }

    /** spec §2.6 table: "Dagon'hai pool per break 640 | 640 | 913 | 1187 | 1460 | 1734 | 2007 | 2281". */
    @Test
    fun scaledPoolFollowsTheSpecTable() {
        assertEquals(listOf(640, 913, 1187, 1460, 1734, 2007, 2281), (2..8).map { cfg.scaledPool(Scaling.DEFAULT, it) })
        assertEquals(640, cfg.scaledPool(Scaling.DEFAULT, 1)) // solo counts as 2
    }

    @Test
    fun traitsAreTheDocumentedOnes() {
        val t = cfg.traits
        assertNull(t.defenceFloorPct)          // hotfix: no defence-reduction cap
        assertEquals(0, t.defenceFloor(100))
        assertNull(t.weakness)                 // Monster Inspect 4th tab never captured: none [HOUSE]
        assertEquals(RangedDefence(80, 80, 80), t.rangedDef)
        assertTrue(t.immunePoison && t.immuneVenom && t.immuneCannon)
        assertNull(cfg.impTraits.defenceFloorPct)
        assertTrue(!cfg.impTraits.immunePoison) // adds can be poisoned (§3.11)
    }

    @Test
    fun cacheStatProfiles() {
        assertEquals(listOf(1, 1, 100, 290, 260), cfg.stats.levels)
        assertEquals(listOf(0, 0, 0, 250, 180), cfg.stats.attack)
        assertEquals(listOf(50, 50, 50, 100, 80), cfg.stats.defence)
        assertEquals(listOf(250, 195, 50, 125, 66), cfg.impStats.levels)
    }

    @Test
    fun monkDesksAndStands() {
        assertEquals(Compass.entries.toList(), cfg.monk.desks)
        assertEquals(Compass.NW, cfg.monk.firstDesks[Role.STAFF])
        assertEquals(Compass.SW, cfg.monk.firstDesks[Role.BOOK])
        assertEquals(DagonhaiConfig.DEFAULT_STANDS, cfg.monk.stands)
    }

    @Test
    fun overrideFileChangesOneKey() {
        val snap = TfaConfigTestKit.load(
            listOf(DagonhaiConfig),
            overrides = mapOf("dagonhai.json" to """{"channel": {"length": 10}, "explosion": {"dies": "staff"}, "poolHeadbar": 86, "monk": {"stands": {"n": [4064, 5605, 0]}}}"""),
        )
        val c = snap[DagonhaiConfig]
        assertEquals(10, c.channel.length)
        assertEquals(15, c.channel.first)
        assertEquals(Role.STAFF, c.explosion.dies)
        assertEquals(86, c.poolHeadbar)
        assertEquals(StaticTile(4064, 5605), c.monk.stands[Compass.N])
        assertEquals(StaticTile(4072, 5599), c.monk.stands[Compass.E])
    }

    @Test
    fun badPoolHeadbarFallsBack() {
        val snap = TfaConfigTestKit.load(listOf(DagonhaiConfig), overrides = mapOf("dagonhai.json" to """{"poolHeadbar": 85}"""))
        assertEquals(89, snap[DagonhaiConfig].poolHeadbar)
    }
}
