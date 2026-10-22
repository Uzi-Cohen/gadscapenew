package com.gadscape.raids.tfa.rooms.ezekiel

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.encounter.RangedDefence
import com.gadscape.raids.tfa.encounter.Scaling
import com.gadscape.raids.tfa.raid.StaticTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ezekiel.json`: the bundled file equals the code defaults, the spec §4.2 values, overrides and fallbacks. */
class EzekielConfigTest {

    private fun load(override: String? = null): EzekielConfig =
        TfaConfigTestKit.load(listOf(EzekielConfig), overrides = override?.let { mapOf("ezekiel.json" to it) } ?: emptyMap(),
            bundled = emptyMap())[EzekielConfig]

    @Test
    fun bundledMatchesDefaults() = TfaConfigTestKit.assertBundledMatchesDefaults(EzekielConfig)

    @Test
    fun specHouseDefaults() {
        val c = load()
        assertEquals(3000, c.baseHp)
        assertEquals(5, c.attackSpeed)
        assertEquals(36, c.meleeMax)
        assertEquals(30, c.magicMax)
        assertEquals(0.5, c.magicPfmMult)
        assertEquals(20, c.starBurstDamage)
        assertEquals(3, c.starArmWidth)
        assertEquals(3, c.starArmLength)
        assertEquals(listOf(3, 5), c.starBurstTicks)
        assertEquals(12, c.swordChargeTicks)
        assertEquals(listOf(10, 13, 16), c.swordFullStagger)
        assertEquals(2, c.swordFlightTicks)
        assertEquals(15, c.swordPlayerDmgBase)
        assertEquals(25, c.swordPlayerDmgEmpower)
        assertEquals(5, c.swordBossPct)
        assertEquals(200, c.swordAirWeaknessPct)
        assertEquals(300, c.swordRangedDef)
        assertEquals(8, c.stunTicks)
        assertEquals(5, c.healPct)
        assertEquals(EzekielConfig.HealRule.NO_SWORD_HIT, c.healRule)
        assertEquals(33.3, c.enragePct)
        assertEquals(EzekielConfig.BleedMode.VARDORVIS, c.bleed)
        assertEquals(false, c.bleedOnTank)
        with(c.finale) {
            assertEquals(2, hitDelay)
            assertEquals(4, nextAttack)
            assertEquals(3, perVolley)
            assertEquals(3, volleyPause)
            assertEquals(25, damage)
            assertEquals(0.5, pfmMult)
            assertEquals(StaticTile(4065, 5534, 0), bossTile)
            assertEquals(Rect(4062, 5534, 4064, 5536), penRect)
            assertEquals(7, fenceTiles.size)
            assertEquals(Finale.DEFAULT_COMBOS, combos)
            assertEquals(Finale.DEFAULT_ROTATIONS, rotations.values.toList())
        }
        assertEquals(StaticTile(4062, 5534, 0), c.spawn)
        assertEquals(SwordCast.DEFAULT_KEYS, c.swordCasts.map { it.key })
    }

    @Test
    fun combatTraitsAndProfiles() {
        val c = load()
        // Ezekiel: earth 25%, ranged 445/370/320, 60% floor of Def 200 = 120 (spec §3.11), poison/venom/cannon immune.
        assertEquals(Element.EARTH, c.traits.weakness)
        assertEquals(25, c.traits.weaknessPct)
        assertEquals(RangedDefence(445, 370, 320), c.traits.rangedDef)
        assertEquals(120, c.traits.defenceFloor(c.stats.ezekiel.levels[2]))
        assertTrue(c.traits.immunePoison && c.traits.immuneVenom && c.traits.immuneCannon)
        assertEquals(listOf(250, 150, 200, 200, 80), c.stats.ezekiel.levels)
        assertEquals(listOf(150, 90, 35, 200, 370), c.stats.ezekiel.defenceBonuses)
        assertEquals(listOf(80, 0, 30), c.stats.ezekiel.otherBonuses)
        // Swords: air 200%, ranged 300 each, magic always hits, no floor, never poisoned.
        val s = c.swordTraits
        assertEquals(Element.AIR, s.weakness)
        assertEquals(200, s.weaknessPct)
        assertEquals(RangedDefence(300, 300, 300), s.rangedDef)
        assertTrue(s.guaranteedMagic)
        assertEquals(0, s.defenceFloor(115))
        assertTrue(s.immunePoison)
        assertEquals(listOf(1, 1, 115, 90, 1), c.stats.sword.levels)
    }

    @Test
    fun scaledHpMatchesTheSpecTable() {
        val c = load()
        val table = listOf(3000, 4282, 5564, 6845, 8127, 9409, 10691)
        for (n in 2..8) assertEquals(table[n - 2], Scaling.DEFAULT.hp(c.baseHp, n), "n = $n")
        assertEquals(3000, Scaling.DEFAULT.hp(c.baseHp, 1), "solo counts as 2")
    }

    @Test
    fun overridesAndFallbacks() {
        val c = load("""{ "healRule": "per_missing_sword", "swordCasts": ["h_full"], "finale": { "damage": 30 },
            "swordAirWeaknessPct": 150, "swordMagicAlwaysHits": false }""")
        assertEquals(EzekielConfig.HealRule.PER_MISSING_SWORD, c.healRule)
        assertEquals(listOf("h_full"), c.swordCasts.map { it.key })
        assertEquals(30, c.finale.damage)
        assertEquals(4, c.finale.nextAttack, "untouched keys keep their defaults")
        assertEquals(150, c.swordTraits.weaknessPct)
        assertEquals(false, c.swordTraits.guaranteedMagic)
        // An unknown cast falls back to the default list instead of failing.
        val bad = load("""{ "swordCasts": ["h_full", "diagonal"] }""")
        assertEquals(SwordCast.DEFAULT_KEYS, bad.swordCasts.map { it.key })
        // A bad combo word falls back to the default combos.
        val badCombo = load("""{ "finale": { "combos": ["left up front"] } }""")
        assertEquals(Finale.DEFAULT_COMBOS, badCombo.finale.combos)
    }
}
