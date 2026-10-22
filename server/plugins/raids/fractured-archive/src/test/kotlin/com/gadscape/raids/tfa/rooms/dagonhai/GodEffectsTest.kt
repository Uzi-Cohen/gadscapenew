package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tome effect geometry (spec §4.6 "Tome effects", NOTES §7.4). */
class GodEffectsTest {

    @Test
    fun bandosRingsCoverOneToEight() {
        assertEquals(listOf(1..2, 3..4, 5..6, 7..8), (1..4).map { GodEffects.ringRange(it, 2) })
    }

    @Test
    fun ringVictimsSkipTheBlockerAndFollowDistance() {
        val centre = StaticTile(4060, 5600)
        val blocker = FakeFighter(centre, "blocker")
        val near = FakeFighter(StaticTile(4062, 5600), "near")   // distance 2: ring 1
        val mid = FakeFighter(StaticTile(4063, 5601), "mid")     // distance 3: ring 2
        val far = FakeFighter(StaticTile(4069, 5600), "far")     // distance 9: no ring
        val all = listOf(blocker, near, mid, far)
        assertEquals(listOf<Fighter>(near), GodEffects.ringVictims(centre, 1, 1, 2, all, blocker))
        assertEquals(listOf<Fighter>(mid), GodEffects.ringVictims(centre, 1, 2, 2, all, blocker))
        assertTrue((1..4).all { k -> far !in GodEffects.ringVictims(centre, 1, k, 2, all, blocker) })
        // a runner moving outwards with the wave is hit again
        near.tile0 = StaticTile(4064, 5600) // distance 4 on the next tick
        assertEquals(listOf<Fighter>(near, mid), GodEffects.ringVictims(centre, 1, 2, 2, all, blocker))
    }

    @Test
    fun cauldronRingsMeasureFromTheFootprint() {
        val p = FakeFighter(StaticTile(4067, 5599)) // adjacent to the cauldron's east edge
        assertEquals(listOf<Fighter>(p), GodEffects.ringVictims(DagonhaiArena.CAULDRON_SW, 3, 1, 2, listOf(p), null))
    }

    @Test
    fun zamorakFieldIsTheSevenBySevenOnTheFloor() {
        val centre = StaticTile(4060, 5600)
        val field = GodEffects.zamorakField(centre, 3, "random", 4, Random(3))
        assertEquals(49, field.size) // fully on the floor here
        assertTrue(field.all { (t, f) -> DagonhaiArena.chebyshev(t, centre) <= 3 && f in 0..3 })
        assertTrue(field.any { it.first == centre })
        val ringsOut = GodEffects.zamorakField(centre, 3, "rings_out", 4, Random(3)).toMap()
        assertEquals(0, ringsOut[centre])
        assertEquals(3, ringsOut[StaticTile(4063, 5603)])
        // near a wall the square is clipped to the floor and never covers the cauldron
        val clipped = GodEffects.zamorakField(StaticTile(4067, 5599), 3, "random", 4, Random(3))
        assertTrue(clipped.size < 49)
        assertTrue(clipped.none { DagonhaiArena.inCauldron(it.first) })
    }

    @Test
    fun stormTilesAreDistinct() {
        val (heal, damage) = GodEffects.stormTiles(StaticTile(4065, 5603), 20, 10, Random(5))
        assertEquals(10, heal.size)
        assertEquals(10, damage.size)
        assertEquals(20, (heal + damage).toSet().size)
        assertTrue((heal + damage).all { DagonhaiArena.isOpen(it) && !DagonhaiArena.inCauldron(it) })
    }
}
