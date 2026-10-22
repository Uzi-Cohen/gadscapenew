package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The static geometry of region 16215 (spec §4.6 "Arena", NOTES §5, arena_grid.txt). */
class DagonhaiArenaTest {

    @Test
    fun floorMatchesTheCacheFloodFill() {
        assertEquals(263, DagonhaiArena.OPEN_TILES.size)
        assertEquals(263 - 9, DagonhaiArena.HAZARD_TILES.size) // minus the cauldron's 3x3
        assertTrue(DagonhaiArena.OPEN_TILES.all { it.x in DagonhaiArena.MIN_X..DagonhaiArena.MAX_X && it.y in DagonhaiArena.MIN_Y..DagonhaiArena.MAX_Y })
        // the two walkable 2x2 desks (NOTES §5) are floor too
        assertTrue(DagonhaiArena.onFloor(StaticTile(4058, 5604)))
        assertTrue(DagonhaiArena.onFloor(StaticTile(4057, 5594)))
        assertFalse(DagonhaiArena.onFloor(StaticTile(4064, 5607))) // N desk 62563
        assertFalse(DagonhaiArena.onFloor(StaticTile(4049, 5599))) // the Book of Life and Death, behind inviswalls
        assertFalse(DagonhaiArena.onFloor(StaticTile(4080, 5599))) // antechamber
    }

    @Test
    fun spawnsAndEntryAreOnTheFloor() {
        val cfg = DagonhaiConfig.SPEC.let { com.gadscape.raids.tfa.config.TfaConfigTestKit.load(listOf(it))[it] }
        for (role in Role.entries) {
            val sw = cfg.arena.spawn(role)
            for (dx in 0..1) for (dy in 0..1) assertTrue(DagonhaiArena.isOpen(sw.offset(dx, dy)), "$role spawn ${sw.offset(dx, dy)}")
        }
        assertTrue(DagonhaiArena.isOpen(StaticTile(4072, 5601))) // transporter landing, spec §2.4
        assertEquals(StaticTile(4065, 5599), DagonhaiArena.CAULDRON_CENTRE)
        assertTrue(DagonhaiArena.inCauldron(StaticTile(4066, 5600)))
        assertFalse(DagonhaiArena.inCauldron(StaticTile(4067, 5599)))
    }

    /** arena_grid.txt: N 6, NE 4, E 7, SE 4, S 6, SW 5, W 9, NW 4 open ray tiles. */
    @Test
    fun raysMatchTheGrid() {
        val counts = DagonhaiArena.RAYS.mapValues { it.value.size }
        assertEquals(mapOf(Compass.N to 6, Compass.NE to 4, Compass.E to 7, Compass.SE to 4, Compass.S to 6, Compass.SW to 5, Compass.W to 9, Compass.NW to 4), counts)
        for ((dir, tiles) in DagonhaiArena.RAYS) {
            for ((k, t) in tiles.withIndex()) {
                assertTrue(DagonhaiArena.isOpen(t), "$dir ray tile $t")
                assertFalse(DagonhaiArena.inCauldron(t))
                assertEquals(k + 1, DagonhaiArena.cauldronDistance(t), "$dir ray tile $t is ${k + 1} from the cauldron")
            }
        }
    }

    @Test
    fun monkStandsSitOnTheLaneEndsNextToTheirDesks() {
        for ((dir, stand) in DagonhaiConfig.DEFAULT_STANDS) {
            for (dx in 0..1) for (dy in 0..1) assertTrue(DagonhaiArena.isOpen(stand.offset(dx, dy)), "$dir stand ${stand.offset(dx, dy)}")
            val ray = DagonhaiArena.RAYS.getValue(dir)
            assertTrue(DagonhaiArena.inFootprint(ray.last(), stand, 2), "$dir stand covers the lane's outer end")
            val lane = DagonhaiArena.laneTiles(dir, stand)
            assertTrue(lane.size >= 2, "$dir lane has ${lane.size} blockable tiles")
            // flight order: from the monk towards the cauldron
            assertEquals(lane.map { DagonhaiArena.cauldronDistance(it) }.sortedDescending(), lane.map { DagonhaiArena.cauldronDistance(it) })
            assertEquals(1, DagonhaiArena.cauldronDistance(lane.last()))
            assertTrue(lane.none { DagonhaiArena.inFootprint(it, stand, 2) })
        }
        assertEquals(
            listOf(StaticTile(4065, 5604), StaticTile(4065, 5603), StaticTile(4065, 5602), StaticTile(4065, 5601)),
            DagonhaiArena.laneTiles(Compass.N, DagonhaiConfig.DEFAULT_STANDS.getValue(Compass.N)),
        )
        assertEquals(7, DagonhaiArena.laneTiles(Compass.W, DagonhaiConfig.DEFAULT_STANDS.getValue(Compass.W)).size)
    }

    @Test
    fun footprintDistance() {
        val sw = StaticTile(4064, 5598)
        assertEquals(0, DagonhaiArena.footprintDistance(sw, 3, StaticTile(4065, 5599)))
        assertEquals(1, DagonhaiArena.footprintDistance(sw, 3, StaticTile(4067, 5600)))
        assertEquals(2, DagonhaiArena.footprintDistance(sw, 3, StaticTile(4062, 5596)))
        assertEquals(8, DagonhaiArena.footprintDistance(sw, 3, StaticTile(4074, 5599)))
    }

    @Test
    fun tilePicksAreDistinctAndSpread() {
        val r = Random(7)
        val picks = DagonhaiArena.pickTiles(DagonhaiArena.HAZARD_TILES, 3, r, minApart = 2)
        assertEquals(3, picks.size)
        assertEquals(3, picks.toSet().size)
        for (a in picks) for (b in picks) if (a != b) assertTrue(DagonhaiArena.chebyshev(a, b) >= 2)
        val players = listOf(StaticTile(4060, 5600), StaticTile(4070, 5600))
        val rifts = DagonhaiArena.riftCandidates(players, 3)
        assertTrue(rifts.isNotEmpty())
        assertTrue(rifts.all { t -> players.all { DagonhaiArena.chebyshev(it, t) >= 3 } })
    }
}
