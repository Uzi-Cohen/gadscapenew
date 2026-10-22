package com.gadscape.raids.tfa.rooms.guardian

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.encounter.RangedDefence
import com.gadscape.raids.tfa.raid.StaticTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuardianConfigTest {

    private val cfg: GuardianConfig = TfaConfigTestKit.load(listOf(GuardianConfig), bundled = emptyMap())[GuardianConfig]

    @Test
    fun bundledMatchesDefaults() = TfaConfigTestKit.assertBundledMatchesDefaults(GuardianConfig)

    /** The §4.1 "House defaults (guardian.json)" block, key by key. */
    @Test
    fun specHouseDefaults() {
        assertEquals(30, cfg.enragePct)
        assertEquals(35, cfg.lift.mainStepPct)
        assertEquals(24, cfg.lift.maxGroundedTicks)
        assertEquals(30, cfg.slamMax)
        assertEquals(25, cfg.rangedMax)
        assertEquals(5, cfg.attackSpeed)
        assertEquals(3, cfg.mushroomEveryNthAttack)
        with(cfg.mushrooms) {
            assertEquals(listOf(3, 1, 2, 6, 30, 12, 3, 10, 10, 25), listOf(base, perTwoAlive, minDist, maxDist, lifetime, cap, dmgMin, dmgMax, poison, wearyTicks))
        }
        with(cfg.tendril) { assertEquals(listOf(6, 3, 3, 25, 11), listOf(firstDelay, interval, swipes, max, maxPrayed)) }
        assertEquals(45, cfg.laser.perAlive)
        assertEquals(0.8, cfg.laser.pfmMult)
        assertEquals(18, cfg.laser.length)
        assertEquals(10, cfg.laser.killRangePct)
        assertFalse(cfg.rangedCone)
        assertFalse(cfg.tailSwipe)
        assertEquals(180, cfg.battery.hp)
        assertEquals(40, cfg.battery.shieldPct)
        assertEquals(50, cfg.battery.earthWeaknessPct)
        assertEquals(mapOf(Alcove.NW to true, Alcove.SW to true, Alcove.NE to true, Alcove.SE to false), cfg.battery.startStates)
    }

    /** §4.1 combat profile [DOC wiki Monster Inspect] and §3.11 floor. */
    @Test
    fun combatProfiles() {
        assertEquals(550, cfg.shieldHp)
        assertEquals(1100, cfg.mainHp)
        val fly = cfg.traits.flying
        assertEquals(Element.FIRE, fly.weakness)
        assertEquals(80, fly.weaknessPct)
        assertEquals(RangedDefence(75, 120, 50), fly.rangedDef)
        assertTrue(fly.flying && fly.immuneAncients && fly.magicDrainInverted && fly.immunePoison && fly.immuneVenom && fly.immuneCannon)
        assertEquals(100, fly.freezeResistPct)
        assertEquals(156, fly.defenceFloor(260))
        val ground = cfg.traits.grounded
        assertEquals(40, ground.weaknessPct)
        assertEquals(RangedDefence(50, 115, 50), ground.rangedDef)
        assertFalse(ground.flying)
        assertEquals(156, ground.defenceFloor(260))
        assertTrue(cfg.traits.enraged.flying)
        val battery = cfg.traits.battery
        assertEquals(Element.EARTH, battery.weakness)
        assertEquals(50, battery.weaknessPct)
        assertNull(battery.defenceFloorPct)
        assertFalse(battery.immunePoison)
        assertEquals(listOf(250, 240, 260, 250, 280), cfg.stats.flying.levels)
        assertEquals(listOf(120, 110, 20, 130, 120), cfg.stats.flying.defenceBonuses)
        assertEquals(listOf(120, 90, 20, 130, 115), cfg.stats.grounded.defenceBonuses)
        assertEquals(listOf(0, 0, 0, 350, 350), cfg.stats.flying.attackBonuses)
        assertEquals(listOf(0, 55, 55), cfg.stats.flying.otherBonuses)
        assertEquals(listOf(0, 20, -20, -20, 0), cfg.stats.battery.defenceBonuses)
    }

    @Test
    fun arenaTiles() {
        assertEquals(StaticTile(4125, 5597, 0), cfg.bossSpawn)
        assertEquals(StaticTile(4114, 5605, 0), cfg.battery.tiles[Alcove.NW])
        assertEquals(StaticTile(4114, 5592, 0), cfg.battery.tiles[Alcove.SW])
        assertEquals(StaticTile(4139, 5605, 0), cfg.battery.tiles[Alcove.NE])
        assertEquals(StaticTile(4139, 5592, 0), cfg.battery.tiles[Alcove.SE])
        assertEquals(16471, cfg.bossSpawn.regionId)
    }

    @Test
    fun overrideOneKey() {
        val snap = TfaConfigTestKit.load(listOf(GuardianConfig), overrides = mapOf("guardian.json" to """{ "enragePct": 33, "battery": { "earthWeaknessPct": 25 } }"""))
        val c = snap[GuardianConfig]
        assertEquals(33, c.enragePct)
        assertEquals(25, c.traits.battery.weaknessPct)
        assertEquals(24, c.lift.maxGroundedTicks)
    }
}
