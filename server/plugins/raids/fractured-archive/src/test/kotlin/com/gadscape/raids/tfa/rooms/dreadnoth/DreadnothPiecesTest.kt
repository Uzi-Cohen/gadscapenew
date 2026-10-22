package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Timeline, arena geometry, jelly planning and blood tiles. */
class DreadnothPiecesTest {

    private val cfg = Harness.DEFAULT_CONFIG

    // ------------------------------------------------------------------------------------------------ timeline

    @Test
    fun timelineRunsInOrderAndCancelsByTag() {
        val t = Timeline()
        val log = ArrayList<String>()
        t.after(2, Timeline.Tag.BOSS) { log += "boss@${t.now}" }
        t.after(2, Timeline.Tag.JELLY) { log += "jelly@${t.now}" }
        t.after(1, Timeline.Tag.OTHER) {
            log += "other@${t.now}"
            t.after(0, Timeline.Tag.OTHER) { log += "same@${t.now}" }
        }
        t.after(5, Timeline.Tag.BOSS) { log += "late@${t.now}" }
        t.advance()
        t.advance()
        assertEquals(listOf("other@1", "same@1", "boss@2", "jelly@2"), log)
        assertEquals(1, t.cancel(Timeline.Tag.BOSS))
        repeat(5) { t.advance() }
        assertEquals(4, log.size, "the cancelled action never ran")
        assertEquals(0, t.pending())
    }

    @Test
    fun timelineAtNowRunsImmediatelyOutsideTheQueue() {
        val t = Timeline()
        t.advance()
        var ran = false
        assertNull(t.at(t.now, Timeline.Tag.OTHER) { ran = true })
        assertTrue(ran)
        t.at(t.now + 3, Timeline.Tag.OTHER) {}
        assertEquals(t.now + 3, t.nextDue(Timeline.Tag.OTHER))
        t.cancelAll()
        assertEquals(0, t.pending())
    }

    // ------------------------------------------------------------------------------------------------ arena

    @Test
    fun arenaIsThe15x15BoxWithoutItsCorners() {
        assertEquals(221, DreadnothArena.WALKABLE.size, "flood fill of arena_map.txt: 221 tiles")
        assertFalse(DreadnothArena.isWalkable(StaticTile(4184, 5656, 0)))
        assertFalse(DreadnothArena.isWalkable(StaticTile(4198, 5670, 0)))
        assertTrue(DreadnothArena.isWalkable(StaticTile(4185, 5656, 0)))
        assertFalse(DreadnothArena.isWalkable(StaticTile(4183, 5663, 0)))
        assertFalse(DreadnothArena.isWalkable(StaticTile(4191, 5663, 1)))
    }

    @Test
    fun meleeZoneIsThe7x7UnderHimIncluded() {
        val sw = StaticTile(4189, 5661, 0)
        var count = 0
        for (x in 4180..4202) for (y in 5652..5674) if (DreadnothArena.inMeleeZone(sw, StaticTile(x, y, 0))) count++
        assertEquals(49, count)
        assertTrue(DreadnothArena.inMeleeZone(sw, StaticTile(4191, 5663, 0)), "under him")
        assertTrue(DreadnothArena.inMeleeZone(sw, StaticTile(4188, 5660, 0)), "diagonal corner")
        assertFalse(DreadnothArena.inMeleeZone(sw, StaticTile(4187, 5663, 0)))
        assertEquals(2, DreadnothArena.distanceToFootprint(sw, StaticTile(4187, 5663, 0)))
    }

    @Test
    fun landingAreaIsCentredOnTheTargetAndClampedToTheArena() {
        assertEquals(StaticTile(4189, 5661, 0), DreadnothArena.landingArea(StaticTile(4191, 5663, 0)))
        assertEquals(StaticTile(4184, 5656, 0), DreadnothArena.landingArea(StaticTile(4185, 5656, 0)), "south-west corner")
        assertEquals(StaticTile(4194, 5666, 0), DreadnothArena.landingArea(StaticTile(4198, 5669, 0)), "north-east corner")
        for (t in DreadnothArena.WALKABLE) {
            val l = DreadnothArena.landingArea(t)
            assertTrue(DreadnothArena.footprintInBox(l))
            assertTrue(DreadnothArena.inFootprint(l, t), "the target is always inside its landing area")
        }
    }

    @Test
    fun stepsTowardTheTarget() {
        val sw = StaticTile(4189, 5661, 0)
        assertEquals(-1 to 0, DreadnothArena.stepToward(sw, StaticTile(4185, 5663, 0)))
        assertEquals(1 to 1, DreadnothArena.stepToward(sw, StaticTile(4197, 5669, 0)))
        assertEquals(0 to -1, DreadnothArena.stepToward(sw, StaticTile(4191, 5657, 0)))
        assertEquals(0 to 0, DreadnothArena.stepToward(sw, StaticTile(4190, 5662, 0)))
    }

    // ------------------------------------------------------------------------------------------------ jellies

    @Test
    fun jellyCountsAndSplits() {
        assertEquals(6, JellyPlanner.count(cfg.jelly, 2), "min(12, 4 + 2)")
        assertEquals(10, JellyPlanner.count(cfg.jelly, 6))
        assertEquals(12, JellyPlanner.count(cfg.jelly, 8), "capped at 12")
        assertEquals(listOf(6), JellyPlanner.split(6, 1))
        assertEquals(listOf(3, 3), JellyPlanner.split(6, 2))
        assertEquals(listOf(2, 2, 2), JellyPlanner.split(6, 3))
        assertEquals(listOf(4, 3), JellyPlanner.split(7, 2))
        assertEquals(listOf(4, 3, 3), JellyPlanner.split(10, 3))
    }

    @Test
    fun jelliesFormOnFreeTilesAwayFromTheBoss() {
        val sw = StaticTile(4189, 5661, 0)
        val occupied = setOf(StaticTile(4185, 5663, 0), StaticTile(4186, 5663, 0))
        val tiles = JellyPlanner.spawnTiles(12, sw, occupied, 2, Random(1))
        assertEquals(12, tiles.size)
        assertEquals(12, tiles.toSet().size, "distinct")
        for (t in tiles) {
            assertTrue(DreadnothArena.isWalkable(t))
            assertTrue(t !in occupied)
            assertTrue(DreadnothArena.distanceToFootprint(sw, t) >= 2, "$t is at least 2 tiles from the boss")
        }
        assertEquals(tiles, JellyPlanner.spawnTiles(12, sw, occupied, 2, Random(1)), "deterministic with the raid's seed")
    }

    @Test
    fun crowdedArenaRelaxesTheDistanceButNeverSpawnsUnderTheBoss() {
        val sw = StaticTile(4189, 5661, 0)
        val farTiles = DreadnothArena.WALKABLE.filter { DreadnothArena.distanceToFootprint(sw, it) >= 2 }.toSet()
        val tiles = JellyPlanner.spawnTiles(5, sw, farTiles, 2, Random(3))
        assertEquals(5, tiles.size)
        for (t in tiles) assertEquals(1, DreadnothArena.distanceToFootprint(sw, t))
    }

    @Test
    fun deathWaveHopsOutOfTheFootprint() {
        val sw = StaticTile(4189, 5661, 0)
        val wave = JellyPlanner.deathWave(10, sw, emptySet(), cfg.jelly.hopMaxTiles, Random(5))
        assertEquals(10, wave.hops.size)
        assertTrue(wave.risers.isEmpty())
        for (h in wave.hops) {
            assertTrue(DreadnothArena.inFootprint(sw, h.origin), "starts inside the boss")
            assertFalse(DreadnothArena.inFootprint(sw, h.dest))
            assertTrue(h.distance in 1..3)
            assertEquals(DreadnothArena.chebyshev(h.origin, h.dest), h.distance)
            assertEquals(h.distance > 1, h.longHop, "14774 for one tile, 14773 beyond")
            assertTrue(DreadnothArena.isWalkable(h.dest))
        }
        assertEquals(10, wave.hops.map { it.dest }.toSet().size)
    }

    @Test
    fun deathWaveInACornerRisesTheJelliesThatDoNotFit() {
        val sw = StaticTile(4184, 5656, 0) // boss in the south-west corner
        val reach = DreadnothArena.WALKABLE.count { DreadnothArena.distanceToFootprint(sw, it) == 1 }
        val wave = JellyPlanner.deathWave(reach + 3, sw, emptySet(), 1, Random(2))
        assertEquals(reach, wave.hops.size)
        assertEquals(3, wave.risers.size)
        for (t in wave.risers) assertTrue(DreadnothArena.distanceToFootprint(sw, t) >= 1)
    }

    // ------------------------------------------------------------------------------------------------ blood

    private class RecordingSink : BloodField.Sink {
        val spawned = ArrayList<StaticTile>()
        val removed = ArrayList<StaticTile>()
        override fun spawn(tile: StaticTile) {
            spawned += tile
        }
        override fun remove(tile: StaticTile) {
            removed += tile
        }
    }

    @Test
    fun bloodHurtsSixToEightFromTheNextTickForItsLifetime() {
        val sink = RecordingSink()
        val field = BloodField(cfg.blood, sink)
        val tile = StaticTile(4190, 5660, 0)
        val p = FakeFighter("p", tile)
        val q = FakeFighter("q", StaticTile(4191, 5660, 0))
        val hits = ArrayList<Pair<Int, Int>>()
        field.add(tile, 10)
        assertEquals(listOf(tile), sink.spawned)
        val random = Random(9)
        for (now in 10..45) field.tick(now, listOf(p, q), random) { f, dmg ->
            assertEquals(p, f)
            hits += now to dmg
        }
        assertEquals((11..39).toList(), hits.map { it.first }, "from the tick after it lands until it expires at +30")
        assertTrue(hits.all { it.second in 6..8 })
        assertEquals(listOf(tile), sink.removed)
        assertEquals(0, field.size)
    }

    @Test
    fun bloodRefreshesAndClears() {
        val sink = RecordingSink()
        val field = BloodField(cfg.blood, sink)
        val tile = StaticTile(4190, 5660, 0)
        field.add(tile, 0)
        field.add(tile, 20)
        assertEquals(1, sink.spawned.size, "one loc per tile")
        field.tick(31, emptyList(), Random(1)) { _, _ -> }
        assertTrue(tile in field, "lifetime restarted at 20")
        field.tick(50, emptyList(), Random(1)) { _, _ -> }
        assertFalse(tile in field)
        field.add(StaticTile(4185, 5660, 0), 60)
        field.add(StaticTile(4186, 5660, 0), 60)
        field.clear()
        assertEquals(0, field.size)
        assertEquals(3, sink.removed.size)
    }

    @Test
    fun deadOrAbsentPlayersTakeNoBloodDamage() {
        val field = BloodField(cfg.blood, RecordingSink())
        val tile = StaticTile(4190, 5660, 0)
        field.add(tile, 0)
        val dead = FakeFighter("dead", tile, alive = false)
        var hits = 0
        field.tick(5, listOf(dead), Random(1)) { _, _ -> hits++ }
        assertEquals(0, hits)
    }
}
