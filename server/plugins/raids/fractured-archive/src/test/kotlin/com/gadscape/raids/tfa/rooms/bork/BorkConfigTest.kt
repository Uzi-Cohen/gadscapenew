package com.gadscape.raids.tfa.rooms.bork

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

class BorkConfigTest {

    private val cfg = Harness.DEFAULT_CONFIG

    @Test
    fun bundledMatchesDefaults() = TfaConfigTestKit.assertBundledMatchesDefaults(BorkSmorksConfig)

    /** The spec §4.3 "House defaults" block, key by key. */
    @Test
    fun specHouseBlock() {
        assertEquals(
            listOf(BorkSmorksConfig.TempoTier(66, 7), BorkSmorksConfig.TempoTier(33, 6), BorkSmorksConfig.TempoTier(0, 4)),
            cfg.tempo,
        )
        with(cfg.bork) {
            assertEquals(12, flatArmour)
            assertEquals(10, flatArmourBelow66)
            assertEquals(2, magicResist)
            assertEquals(34, beatMax)
            assertEquals(5, largeBeatRadius)
            assertEquals(0.5, pfmMult)
            assertEquals(40, stompMax)
            assertEquals(4, stomps)
            assertEquals(listOf(3, 6, 9, 12), stompTicks)
            assertEquals(8, gongHits)
            assertEquals(12, gongMax)
            assertEquals(0.5, gongChance)
            assertTrue(gongAfterDaze)
            assertEquals(2, enragedLargeBeats)
        }
        with(cfg.smorks) {
            assertEquals(2, flatArmour)
            assertEquals(28, autoMax)
            assertEquals(4, specialEvery)
            assertEquals(30, incendiaryDmg)
            assertEquals(5, incendiaryBorkPct)
            assertEquals(7, oilTiles)
            assertEquals(5, oilArea)
            assertEquals(30, oilLifetime)
            assertEquals(6..7, oilDmg)
            assertEquals(35, heavyDmg)
            assertEquals(4, heavyFlight)
            assertEquals(3, enragedSpeed)
            assertEquals(1.25, enragedDmgMult)
        }
    }

    /** Spec §2.6 table: Bork 1500 → 4064 at n = 6, the Smorks 600 → 1625; solo counts as 2. */
    @Test
    fun scaledHp() {
        val s = Scaling.DEFAULT
        assertEquals(listOf(1500, 1500, 2141, 2782, 3423, 4064, 4705, 5345), (1..8).map { s.hp(cfg.bork.baseHp, s.partyScale(it)) })
        assertEquals(listOf(600, 600, 856, 1113, 1369, 1625, 1882, 2138), (1..8).map { s.hp(cfg.smorks.baseHp, s.partyScale(it)) })
    }

    /** §3.11 hotfix: Bork's Defence stops at 180; the Smorks can be drained to 0. */
    @Test
    fun defenceFloors() {
        assertEquals(300, cfg.bork.stats.levels[2])
        assertEquals(180, cfg.bork.traits.defenceFloor(300))
        assertEquals(220, cfg.smorks.stats.levels[2])
        assertNull(cfg.smorks.traits.defenceFloorPct)
        assertEquals(0, cfg.smorks.traits.defenceFloor(220))
        // The engine rule: a 35% drain (elder maul) repeated stops at the floor.
        var def = 300
        repeat(5) { def = maxOf(cfg.bork.traits.defenceFloor(300), def - def * 35 / 100) }
        assertEquals(180, def)
    }

    /** §4.3 combat profile (DOC Monster Inspect + CACHE). */
    @Test
    fun combatProfile() {
        with(cfg.bork.traits) {
            assertEquals(Element.EARTH, weakness)
            assertEquals(40, weaknessPct)
            assertEquals(RangedDefence(80, 50, 5), rangedDef)
            assertEquals(12, flatArmour)
            assertEquals(2, magicResist)
            assertEquals(100, freezeResistPct)
            assertTrue(immunePoison && immuneVenom && immuneCannon)
        }
        with(cfg.smorks.traits) {
            assertEquals(Element.EARTH, weakness)
            assertEquals(85, weaknessPct)
            assertEquals(RangedDefence(80, 150, 80), rangedDef)
            assertEquals(2, flatArmour)
            assertEquals(0, magicResist)
            assertEquals(100, freezeResistPct)
        }
        assertEquals(listOf(140, 130, 10, 300, 50), cfg.bork.stats.defenceBonuses)
        assertEquals(listOf(220, 170, 160, 400, 150), cfg.smorks.stats.defenceBonuses)
        assertEquals(listOf(200, 200, 300, 90, 200), cfg.bork.stats.levels)
        assertEquals(listOf(1, 1, 220, 185, 185), cfg.smorks.stats.levels)
        assertEquals(1.6, cfg.bork.xpModifier)
    }

    /** Spec §6.2 test 3: a successful 10 melee hit on Bork with 12 flat armour deals 1; magic loses 2 (E8 arithmetic). */
    @Test
    fun flatArmourArithmetic() {
        assertEquals(1, CombatTraitRules.applyFlatArmour(10, 12))
        assertEquals(28, CombatTraitRules.applyFlatArmour(40, 12))
        assertEquals(8, CombatTraitRules.applyFlatArmour(10, 2))
        assertEquals(0, CombatTraitRules.applyFlatArmour(0, 12))
    }

    /** The NPC max-hit formula the house values come from: floor(((lvl+9)×(bonus+64)+320)/640). */
    @Test
    fun houseMaxHits() {
        fun npcMax(level: Int, bonus: Int) = ((level + 9) * (bonus + 64) + 320) / 640
        assertEquals(34, npcMax(200, 40))
        assertEquals(28, npcMax(185, 30))
        assertEquals(35, Math.round(28 * cfg.smorks.enragedDmgMult).toInt())
    }

    @Test
    fun spawnsAndTexts() {
        assertEquals(StaticTile(4194, 5597, 0), cfg.bork.spawn)
        assertEquals(StaticTile(4190, 5598, 0), cfg.smorks.spawn)
        assertEquals("Bork smash %s now.", cfg.bork.texts.smash)
        assertEquals("Bork very angry!", cfg.bork.texts.veryAngry)
        assertEquals("Bork crush now.", cfg.bork.texts.crushNow)
        assertEquals("Bork think it stomp time now!", cfg.bork.texts.stompTime)
        assertEquals("Burny gloop!", cfg.smorks.texts.incendiary)
        assertEquals("Big bomb. Bork watch out!", cfg.smorks.texts.heavy)
        assertTrue(BorkArena.footprintWalkable(cfg.bork.spawn, BorkArena.BORK_SIZE))
        assertFalse(BorkArena.isWalkable(cfg.smorks.spawn))
    }

    /** A bad override falls back key by key; a tempo table that does not end at 0 falls back to the spec's. */
    @Test
    fun overrideFallbacks() {
        val snap = TfaConfigTestKit.load(
            listOf(BorkSmorksConfig),
            overrides = mapOf(
                "bork_smorks.json" to """
                {
                  "tempo": [ { "abovePct": 50, "speed": 5 }, { "abovePct": 20, "speed": 3 } ],
                  "bork": { "beatMax": "lots", "stompMax": 50, "stomps": 2 },
                  "smorks": { "oilArea": 4 }
                }
                """.trimIndent(),
            ),
        )
        val c = snap[BorkSmorksConfig]
        assertEquals(BorkSmorksConfig.DEFAULT_TEMPO, c.tempo)
        assertEquals(34, c.bork.beatMax)
        assertEquals(50, c.bork.stompMax)
        assertEquals(2, c.bork.stomps)
        assertEquals(listOf(3, 6), c.bork.stompTicks) // the 4 bundled ticks do not fit 2 stomps: every 3 t
        assertEquals(5, c.smorks.oilArea)
        assertTrue(snap.issuesOf(BorkSmorksConfig).isNotEmpty())
    }
}
