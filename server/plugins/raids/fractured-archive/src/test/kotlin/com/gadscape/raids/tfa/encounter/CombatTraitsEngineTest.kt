package com.gadscape.raids.tfa.encounter

import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.combat.CombatTraitRules
import com.zenyte.game.world.entity.npc.combat.RangedAttackType
import com.zenyte.game.world.entity.npc.combat.SpellElement
import com.zenyte.game.world.entity.player.SkillConstants
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WU-04 (spec §6.2) acceptance tests 1-7 for the engine combat traits (E5-E9), on the pure rules the pipeline calls
 * ([CombatTraitRules]) and the raid's engine view ([CombatTraitsView]). Seeded RNG where a roll is involved.
 */
class CombatTraitsEngineTest {

    // Boss traits from spec §4 (the per-room units ship them in their JSON; these are the documented values).
    private val guardianFlying = CombatTraits.BOSS_DEFAULT.copy(weakness = Element.FIRE, weaknessPct = 80,
        rangedDef = RangedDefence(75, 120, 50), freezeResistPct = 100, immuneAncients = true, magicDrainInverted = true, flying = true)
    private val bork = CombatTraits.BOSS_DEFAULT.copy(weakness = Element.EARTH, weaknessPct = 40,
        rangedDef = RangedDefence(80, 50, 5), flatArmour = 12, magicResist = 2, freezeResistPct = 100)
    private val borkDazed = bork.copy(flatArmour = 0, magicResist = 0, guaranteedMelee = true)
    private val smorks = CombatTraits.ADD_DEFAULT.copy(immunePoison = true, immuneVenom = true, weakness = Element.EARTH,
        weaknessPct = 85, rangedDef = RangedDefence(80, 150, 80), flatArmour = 2, freezeResistPct = 100)
    private val dreadnoth = CombatTraits.BOSS_DEFAULT.copy(defenceFloorPct = null, weakness = Element.FIRE, weaknessPct = 85,
        rangedDef = RangedDefence(100, 80, 80), freezeResistPct = 40, demonbanePct = 25, immuneVenom = false)
    private val xarth = CombatTraits.BOSS_DEFAULT.copy(weakness = Element.WATER, weaknessPct = 85,
        rangedDef = RangedDefence(125, 230, 155), freezeResistPct = 100, demonbanePct = 80)

    /** 1. Fire Surge (base 24) against 80% fire weakness: max hit +19, accuracy +80%. */
    @Test
    fun fireSurgeAgainstEightyPercentWeakness() {
        val view = guardianFlying.toEngine()
        assertEquals(SpellElement.FIRE, view.weaknessElement())
        assertEquals(80, view.weaknessPercent())
        assertEquals(SpellElement.FIRE, SpellElement.ofName("FIRE_SURGE"))
        assertEquals(19, CombatTraitRules.weaknessBonusDamage(24, view.weaknessPercent()))
        assertEquals(9, CombatTraitRules.weaknessBonusDamage(24, 40), "grounded Guardian 40%: +9 (spec §4.1)")
        assertEquals(20, CombatTraitRules.weaknessBonusDamage(24, xarth.weaknessPct), "Water Surge on Xarth: +20 (xarth notes)")
        assertEquals(18_000, CombatTraitRules.weaknessAccuracy(10_000, view.weaknessPercent()), "+80% accuracy")
        assertEquals(10_000, CombatTraitRules.weaknessAccuracy(10_000, 0))
        // only standard elemental spells count
        assertNull(SpellElement.ofName("ICE_BARRAGE"))
        assertNull(SpellElement.ofName("FLAMES_OF_ZAMORAK"))
        assertNull(SpellElement.ofName("TRIDENT_OF_THE_SEAS"))
        assertEquals(0, bork.copy(weakness = null).toEngine().weaknessPercent(), "no element, no weakness")
    }

    /** E6: light / standard / heavy ranged defence, fallback to the single value. */
    @Test
    fun rangedDefenceTypes() {
        val view = bork.toEngine()
        assertEquals(80, view.rangedDefenceBonus(RangedAttackType.LIGHT, 999))
        assertEquals(50, view.rangedDefenceBonus(RangedAttackType.STANDARD, 999))
        assertEquals(5, view.rangedDefenceBonus(RangedAttackType.HEAVY, 999), "Bork is weak to crossbows")
        assertEquals(999, CombatTraits.BOSS_DEFAULT.toEngine().rangedDefenceBonus(RangedAttackType.HEAVY, 999))
        assertEquals(RangedAttackType.HEAVY, RangedAttackType.classify("RUNITE_BOLT", "Rune crossbow"))
        assertEquals(RangedAttackType.HEAVY, RangedAttackType.classify("DRAGON_JAVELIN", "Heavy ballista"))
        assertEquals(RangedAttackType.HEAVY, RangedAttackType.classify("BLACK_CHINCHOMPA", "Black chinchompa"))
        assertEquals(RangedAttackType.STANDARD, RangedAttackType.classify("DRAGON_ARROW", "Twisted bow"))
        assertEquals(RangedAttackType.LIGHT, RangedAttackType.classify("BLOWPIPE_DRAGON_DART", "Toxic blowpipe"))
        assertEquals(RangedAttackType.LIGHT, RangedAttackType.classify("MORRIGANS_JAVELIN", "Morrigan's javelin"))
        assertEquals(RangedAttackType.LIGHT, RangedAttackType.classify(null, "Dragon knife"))
        assertEquals(RangedAttackType.STANDARD, RangedAttackType.classify(null, null))
    }

    /** 2. Elder maul (35% of current) then DWH (30%) on Bork stops at Defence 180; on the Smorks it reaches 0. */
    @Test
    fun defenceFloorHoldsAgainstEveryDrain() {
        val borkView = bork.toEngine()
        val borkFloor = borkView.drainFloor(SkillConstants.DEFENCE, 300)
        assertEquals(180, borkFloor)
        var def = 300
        def = CombatTraitRules.drainTo(def, CombatTraitRules.percentDrainAmount(def, 35.0, 0), borkFloor) // elder maul: 105 → 195
        assertEquals(195, def)
        def = CombatTraitRules.drainTo(def, CombatTraitRules.percentDrainAmount(def, 30.0, 0), borkFloor) // DWH: 58 → stops at 180
        assertEquals(180, def)
        def = CombatTraitRules.drainTo(def, 50, borkFloor) // a BGS drain changes nothing
        assertEquals(180, def)
        assertEquals(0, borkView.drainFloor(SkillConstants.ATTACK, 200), "only Defence has a floor")

        val smorksView = smorks.toEngine()
        val smorksFloor = smorksView.drainFloor(SkillConstants.DEFENCE, 220)
        assertEquals(0, smorksFloor)
        var sd = 220
        sd = CombatTraitRules.drainTo(sd, CombatTraitRules.percentDrainAmount(sd, 35.0, 0), smorksFloor)
        sd = CombatTraitRules.drainTo(sd, CombatTraitRules.percentDrainAmount(sd, 30.0, 0), smorksFloor)
        assertEquals(101, sd)
        sd = CombatTraitRules.drainTo(sd, 150, smorksFloor) // BGS
        assertEquals(0, sd)
        // a level already under a floor is never raised by a drain
        assertEquals(150, CombatTraitRules.drainTo(150, 10, 180))
    }

    /** 3. A successful 10 melee hit on Bork with 12 flat armour deals 1; magic loses 2; dazed melee skips the roll. */
    @Test
    fun flatArmourMagicResistanceAndGuaranteedHits() {
        val view = bork.toEngine()
        assertEquals(1, CombatTraitRules.applyFlatArmour(10, view.flatArmour()))
        assertEquals(0, CombatTraitRules.applyFlatArmour(0, view.flatArmour()), "a miss stays a miss")
        assertEquals(38, CombatTraitRules.applyFlatArmour(50, view.flatArmour()))
        assertEquals(8, CombatTraitRules.applyFlatArmour(10, view.magicResistance()), "magic ignores flat armour, loses 2")
        assertEquals(1, CombatTraitRules.applyFlatArmour(1, smorks.toEngine().flatArmour()))
        assertEquals(12, CombatTraitRules.applyFlatArmour(10, -2), "negative flat armour adds")
        assertFalse(view.guaranteedHit(HitType.MELEE))

        val dazed = CombatTraitsView { borkDazed }
        assertTrue(dazed.guaranteedHit(HitType.MELEE))
        assertFalse(dazed.guaranteedHit(HitType.RANGED))
        assertEquals(10, CombatTraitRules.applyFlatArmour(10, dazed.flatArmour()))
        // accuracy 0 against a huge defence never hits... unless the roll is skipped
        val random = Random(4)
        repeat(50) { assertEquals(0, CombatTraitRules.rollHit(random, 0, 50_000, 1, 40, false)) }
        repeat(50) { assertTrue(CombatTraitRules.rollHit(random, 0, 50_000, 1, 40, true) in 1..40) }

        // the view follows a dynamic source (TfaBossNpc re-reads `traits` on every hit)
        var isDazed = false
        val live = CombatTraitsView { if (isDazed) borkDazed else bork }
        assertEquals(12, live.flatArmour())
        isDazed = true
        assertEquals(0, live.flatArmour())
        assertTrue(live.guaranteedHit(HitType.MELEE))
    }

    /** 4. Ice Barrage (32 t) on Dreadnoth (40% freeze resistance) freezes for 19 t; 100% = immune. */
    @Test
    fun freezeResistance() {
        assertEquals(40, dreadnoth.toEngine().freezeResistancePercent())
        assertEquals(19, CombatTraitRules.resistedFreeze(32, 40))
        assertEquals(25, CombatTraitRules.resistedFreeze(32, 20), "jelly 20%")
        assertEquals(32, CombatTraitRules.resistedFreeze(32, 0))
        assertEquals(0, CombatTraitRules.resistedFreeze(32, 100), "Archive Guardian, Bork: immune")
    }

    /** 5. Arclight (+70%) on Xarth (80% demonbane effectiveness): +56%; Dreadnoth 25%: +17.5%. */
    @Test
    fun demonbaneEffectiveness() {
        val xarthView = xarth.toEngine()
        assertEquals(true, xarthView.isDemon())
        assertEquals(80, xarthView.demonbanePercent())
        assertEquals(1.56, CombatTraitRules.demonbaneMultiplier(0.7, xarthView.demonbanePercent()), 1e-9)
        assertEquals(1.175, CombatTraitRules.demonbaneMultiplier(0.7, dreadnoth.demonbanePct!!), 1e-9)
        assertEquals(1.48, CombatTraitRules.demonbaneMultiplier(0.6, 80), 1e-9, "Silverlight on Xarth")
        assertEquals(0.05, CombatTraitRules.scaledDemonbaneBonus(0.2, 25), 1e-9, "demonbane spell accuracy on Dreadnoth")
        assertEquals(false, bork.toEngine().isDemon(), "raid NPCs without demonbanePct are not demons")
    }

    /** 6. A scythe on the flying Guardian is refused; a halberd works. */
    @Test
    fun flyingGuardianOnlyHalberds() {
        assertTrue(guardianFlying.toEngine().flying())
        assertFalse(CombatTraitRules.meleeReachesFlying("Scythe of vitur"))
        assertFalse(CombatTraitRules.meleeReachesFlying("Abyssal whip"))
        assertTrue(CombatTraitRules.meleeReachesFlying("Dragon halberd"))
        assertTrue(CombatTraitRules.meleeReachesFlying("Noxious halberd"))
        assertFalse(guardianFlying.copy(flying = false).toEngine().flying(), "grounded form")
    }

    /** 7. An Accursed sceptre drain (15% of base Magic) raises the Guardian's Magic; Ancient Magicks are refused. */
    @Test
    fun invertedMagicDrainAndAncientsImmunity() {
        val view = guardianFlying.toEngine()
        assertTrue(view.magicDrainInverted())
        val drain = (250 * 0.15).toInt()
        assertEquals(287, CombatTraitRules.drainedLevel(250, drain, 0, view.magicDrainInverted()))
        assertEquals(213, CombatTraitRules.drainedLevel(250, drain, 0, false), "a normal NPC loses it")
        assertTrue(view.immuneToAncientMagicks())
        assertFalse(bork.toEngine().immuneToAncientMagicks())
    }
}
