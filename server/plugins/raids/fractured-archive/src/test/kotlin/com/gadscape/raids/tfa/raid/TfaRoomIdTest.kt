package com.gadscape.raids.tfa.raid

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.config.ConfigReader
import com.gadscape.raids.tfa.config.TfaConfigSpec
import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.encounter.RangedDefence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TfaRoomIdTest {

    @Test
    fun spec22Table() {
        // id, region, stats dbrow, entry dbrow, prefetch, music dbrow, midi, side
        val expected = mapOf(
            TfaRoomId.GUARDIAN to listOf(1, 16471, 17134, 17054, 10013, 17002, 887),
            TfaRoomId.EZEKIEL to listOf(2, 16214, 17137, 17055, 10012, 17003, 890),
            TfaRoomId.XARTH to listOf(3, 16726, 17141, 17056, 10030, 17008, 896),
            TfaRoomId.DAGONHAI to listOf(4, 16215, 17136, 17057, 10011, 17005, 889),
            TfaRoomId.BORK to listOf(5, 16727, 17135, 17058, 10010, 17004, 888),
            TfaRoomId.MOIA to listOf(6, 16216, 17138, 17059, 10015, 17007, 892),
            TfaRoomId.DREADNOTH to listOf(7, 16728, 17140, 17060, 10014, 17006, 895),
            TfaRoomId.OCELLUS to listOf(8, 16472, 17139, 17061, 10016, 17009, 893),
        )
        for ((room, v) in expected) {
            assertEquals(v, listOf(room.id, room.staticRegion, room.statsDbrow, room.entryDbrow, room.prefetchScript, room.musicDbrow, room.midi), room.name)
        }
        assertEquals(Side.WEST, TfaRoomId.EZEKIEL.side)
        assertEquals(Side.EAST, TfaRoomId.XARTH.side)
        assertNull(TfaRoomId.GUARDIAN.side)
    }

    @Test
    fun sectionsAndPairs() {
        assertEquals(TfaRoomId.EZEKIEL, TfaRoomId.of(1, Side.WEST))
        assertEquals(TfaRoomId.BORK, TfaRoomId.of(1, Side.EAST))
        assertEquals(TfaRoomId.MOIA, TfaRoomId.of(2, Side.WEST))
        assertEquals(TfaRoomId.DREADNOTH, TfaRoomId.of(2, Side.EAST))
        assertEquals(TfaRoomId.DAGONHAI, TfaRoomId.of(3, Side.WEST))
        assertEquals(TfaRoomId.XARTH, TfaRoomId.of(3, Side.EAST))
        assertEquals(TfaRoomId.XARTH, TfaRoomId.DAGONHAI.pair)
        assertNull(TfaRoomId.OCELLUS.pair)
        assertEquals(8, TfaRoomId.ROUTE_ORDER.toSet().size)
    }

    @Test
    fun lookupByName() {
        assertEquals(TfaRoomId.BORK, TfaRoomId.byName("smorks"))
        assertEquals(TfaRoomId.BORK, TfaRoomId.byName("bork_smorks"))
        assertEquals(TfaRoomId.DREADNOTH, TfaRoomId.byName("Jelly"))
        assertEquals(TfaRoomId.DAGONHAI, TfaRoomId.byName("trio"))
        assertEquals(TfaRoomId.GUARDIAN, TfaRoomId.byName("archive guardian"))
        assertNull(TfaRoomId.byName("vault"))
        assertNull(TfaRoomId.byName("p3"))
    }

    @Test
    fun staticTileRegion() {
        assertEquals(16471, StaticTile(4127, 5599).regionId)
        assertEquals(16470, StaticTile(4126, 5508).regionId)
        assertEquals(StaticTile(4127, 5600), StaticTile(4127, 5599).step(Compass.N))
        assertEquals(Compass.SW, Compass.NE.opposite)
    }

    // --------------------------------------------------------------------------- CombatTraits (§2.2, §3.11)

    @Test
    fun defenceFloorsMatchTheHotfixList() {
        val t = CombatTraits.BOSS_DEFAULT
        assertEquals(156, t.defenceFloor(260), "Archive Guardian")
        assertEquals(180, t.defenceFloor(300), "Bork")
        assertEquals(120, t.defenceFloor(200), "Ezekiel")
        assertEquals(117, t.defenceFloor(195), "Moia")
        assertEquals(102, t.defenceFloor(170), "Xarth")
        assertEquals(129, t.defenceFloor(215), "Ocellus P1")
        assertEquals(150, t.defenceFloor(250), "Ocellus P3")
        assertEquals(0, t.copy(defenceFloorPct = null).defenceFloor(125), "Dreadnoth: no cap")
    }

    private object TraitsSpec : TfaConfigSpec<CombatTraits>("traits_test.json") {
        override fun read(r: ConfigReader) = CombatTraits.read(r, CombatTraits.BOSS_DEFAULT)
    }

    @Test
    fun traitsReadFromBossJson() {
        val snap = TfaConfigTestKit.load(listOf(TraitsSpec), bundled = emptyMap(), overrides = mapOf("traits_test.json" to """
            { "traits": { "weakness": "fire", "weaknessPct": 80, "rangedDef": [75, 120, 50], "immuneAncients": true,
                          "magicDrainInverted": true, "flying": true } }
        """.trimIndent()))
        val t = snap[TraitsSpec]
        assertEquals(Element.FIRE, t.weakness)
        assertEquals(80, t.weaknessPct)
        assertEquals(RangedDefence(75, 120, 50), t.rangedDef)
        assertTrue(t.immuneAncients && t.magicDrainInverted && t.flying && t.immunePoison)
        assertEquals(60, t.defenceFloorPct)
        assertTrue(snap.issuesOf(TraitsSpec).none { it.key?.startsWith("traits") == true }, snap.issuesOf(TraitsSpec).joinToString())
    }
}
