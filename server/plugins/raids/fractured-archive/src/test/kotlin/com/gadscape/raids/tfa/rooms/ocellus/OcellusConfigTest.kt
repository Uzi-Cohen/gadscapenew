package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.encounter.RangedDefence
import com.gadscape.raids.tfa.encounter.Scaling
import com.gadscape.raids.tfa.raid.StaticTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OcellusConfigTest {

    @Test
    fun bundledOcellusJsonMatchesDefaults() = TfaConfigTestKit.assertBundledMatchesDefaults(OcellusConfig)

    private val cfg: OcellusConfig = TfaConfigTestKit.load(listOf(OcellusConfig), bundled = emptyMap())[OcellusConfig]

    @Test
    fun specHouseDefaults() {
        // spec §4.8 "House defaults"
        with(cfg.p1) {
            assertEquals(5, speed)
            assertEquals(30, autoMax)
            assertEquals(listOf("SW", "NW", "NE", "SE"), anchors)
            assertEquals(3, tendrilInterval)
            assertEquals(4, tendrilLife)
            assertEquals(7, tendrilsPerAnchor)
            assertEquals(5, blockDmg)
            assertEquals(8, meterPerUnblocked)
            assertEquals(2, slamRadius)
            assertEquals(35, slamMax)
            assertEquals(listOf(66, 25), thresholds)
            assertEquals(50, annihilationAt)
            assertEquals(80..90, annihilation)
            assertEquals(17..18, deathAwaits)
            assertTrue(bindable)
        }
        with(cfg.p2) {
            assertEquals(listOf(OcellusConfig.Barrage(4, 2, 2), OcellusConfig.Barrage(3, 1, 3), OcellusConfig.Barrage(2, 3, 1)), barrages)
            assertTrue(randomize)
            assertEquals(5, volleys)
            assertEquals(25..35, laneDmg)
            assertEquals(8, pools)
            assertEquals(15..25, poolDmg)
            assertEquals(6, portalDelay)
            assertEquals(8, quadrantAttackDelay)
            assertEquals(60, quadrantAttackDmg)
            assertTrue(bladeSkipsBarrages)
            assertEquals(listOf("SW", "NW", "NE"), quadrants)
        }
        assertEquals(listOf(75, 50, 25), cfg.p3.thresholds)
        assertEquals(6, cfg.p4.chainCount)
        assertEquals(0, cfg.planeShift)
        assertNull(cfg.combatLevel.p1)
        assertEquals(1601, cfg.combatLevel.p3)
        assertEquals(15, cfg.death.ticks)
    }

    @Test
    fun transitionsAreTheSeqLengths() {
        // cycles / 30 from seq_ocellus.txt: 14814 210c, 14837 180c, 14876 240c, 14878 240c, 14881 150c
        with(cfg.transitions) {
            assertEquals(7, p1ToP2)
            assertEquals(6, p2Form)
            assertEquals(21, bladeDrop + p2ToP3Part1 + p2ToP3Part2) // spec: "21 t"
        }
    }

    @Test
    fun combatProfileTraits() {
        // §3.11 / §4.8: floors 129 and 150, the Blade has none; ranged defence light / standard / heavy
        assertEquals(129, cfg.traits.p1.defenceFloor(215))
        assertEquals(150, cfg.traits.p3.defenceFloor(250))
        assertNull(cfg.traits.blade.defenceFloorPct)
        assertEquals(RangedDefence(50, 135, 50), cfg.traits.p1.rangedDef)
        assertEquals(RangedDefence(100, 110, 80), cfg.traits.blade.rangedDef)
        assertEquals(RangedDefence(250, 250, 250), cfg.traits.p3.rangedDef)
        assertEquals(80, cfg.traits.p1.demonbanePct)
        assertEquals(80, cfg.traits.p3.demonbanePct)
        assertNull(cfg.traits.blade.demonbanePct)
        assertTrue(cfg.traits.p1.immunePoison && cfg.traits.p1.immuneCannon)
    }

    @Test
    fun scaledHpMatchesTheSpecTable() {
        val s = Scaling.DEFAULT
        assertEquals(listOf(2500, 3568, 4636, 5705, 6773, 7841, 8909), (2..8).map { s.hp(cfg.hp.p1, it) })
        assertEquals(listOf(1000, 1427, 1855, 2282, 2709, 3136, 3564), (2..8).map { s.hp(cfg.hp.blade, it) })
        assertEquals(listOf(5250, 7493, 9736, 11980, 14223, 16466, 18709), (2..8).map { s.hp(cfg.hp.p3, it, "ocellus_p3") })
        assertEquals(1601, s.combatLevel("ocellus_p3"))
        assertNull(s.combatLevel("ocellus_p1"))
    }

    @Test
    fun arenaTilesAreOnCachePlaneOne() {
        assertEquals(StaticTile(4126, 5668, 1), cfg.arena.introTile)
        assertEquals(StaticTile(4126, 5672, 1), cfg.arena.centreTile)
        assertEquals(4, cfg.arena.anchorTiles.size)
    }

    @Test
    fun projectileTicksRoundUp() {
        val spec = OcellusConfig.ProjectileSpec(70, 31, 0, 15, 10, 5)
        assertEquals(60, spec.cycles(10))
        assertEquals(2, spec.ticks(10))
        assertEquals(1, spec.ticks(1))
        assertEquals(4, cfg.projectiles.p2Shot.ticks(0)) // the portal shot crosses its lane in 4 t (spot 4269)
    }

    @Test
    fun overrideOfOneKeyKeepsTheRest() {
        val snap = TfaConfigTestKit.load(listOf(OcellusConfig), overrides = mapOf("ocellus.json" to """{"p1": {"autoMax": 40}}"""))
        val c = snap[OcellusConfig]
        assertEquals(40, c.p1.autoMax)
        assertEquals(35, c.p1.slamMax)
    }
}
