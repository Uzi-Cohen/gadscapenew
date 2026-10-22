package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.raid.StaticTile
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BarrageLayoutTest {

    private val geo = ArenaGeometry()

    /** The house barrage table (spec §4.8) in every quadrant, plus the extreme indexes 0..3. */
    private val cases: List<Pair<Int, Int>> = listOf(2 to 2, 1 to 3, 3 to 1, 0 to 0, 3 to 2, 2 to 3, 0 to 3)

    @Test
    fun tenWallsOnTheArmsNextToTheQuadrant() {
        for (q in Quadrant.entries) {
            val layout = BarrageLayout(geo, q, 2, 2)
            assertEquals(10, layout.segments.size)
            assertEquals(2, layout.segments.count { layout.isOrange(it) })
            val wallTiles = layout.segments.flatMap { it.tiles }
            assertEquals(20, wallTiles.toSet().size, "$q walls overlap")
            for (t in wallTiles + layout.blockTiles) {
                assertTrue(ArenaGeometry.onCross(t.x, t.y), "$q wall tile $t on the cross")
                assertTrue(Quadrant.of(t.x, t.y) == null)
                // never on the centre 3x3 (Ocellus 16675 / the Blade hitbox stand there)
                assertTrue(ArenaGeometry.distanceToFootprint(t.x, t.y, 4126, 5672, 3) >= 1, "$q wall tile $t on the centre")
            }
            // each 2x1 loc covers exactly its two tiles: SW tile + rotation
            for (s in layout.segments) {
                val vertical = s.rotation % 2 == 1
                val covered = if (vertical) listOf(s.locTile, s.locTile.offset(0, 1)) else listOf(s.locTile, s.locTile.offset(1, 0))
                assertEquals(s.tiles.toSet(), covered.toSet(), "$q segment ${s.edge} ${s.index}")
            }
        }
    }

    @Test
    fun onlyTheOrangeIntersectionIsSafe() {
        for (q in Quadrant.entries) for ((col, row) in cases) {
            val layout = BarrageLayout(geo, q, col, row)
            val safe = layout.safeTiles()
            val expected = if (col == 3 && row == 3) 1 else 4 // (3,3) would be clipped by the octagon diagonal
            assertEquals(expected, safe.size, "$q col=$col row=$row safe=$safe")
            for (t in layout.quadrantTiles) {
                val lanes = layout.purpleLanesAt(t)
                if (t in safe) assertTrue(lanes.isEmpty(), "$q $t is safe but in ${lanes.map { "${it.edge}${it.index}" }}")
                else assertTrue(lanes.isNotEmpty(), "$q $t in no purple lane")
            }
            // the safe 2x2 is contiguous
            assertTrue(safe.all { a -> safe.all { b -> abs(a.x - b.x) <= 1 && abs(a.y - b.y) <= 1 } })
        }
    }

    @Test
    fun wallsAndBlocksSealTheQuadrant() {
        for (q in Quadrant.entries) {
            val layout = BarrageLayout(geo, q, 2, 2)
            val closed = (layout.segments.flatMap { it.tiles } + layout.blockTiles).map { it.x to it.y }.toSet() +
                // the vine gate row is shut during the fight, the centre 3x3 holds Ocellus (players may pass NPCs,
                // but not diagonally between two walls; checked below with the corner rule)
                (4126..4128).map { it to 5660 }
            val start = layout.quadrantTiles.first()
            val seen = HashSet<Pair<Int, Int>>()
            val queue = ArrayDeque(listOf(start.x to start.y))
            seen += start.x to start.y
            fun free(x: Int, y: Int) = ArenaGeometry.inOctagon(x, y) && (x to y) !in closed
            while (queue.isNotEmpty()) {
                val (x, y) = queue.removeFirst()
                for (dx in -1..1) for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (!free(nx, ny)) continue
                    if (dx != 0 && dy != 0 && (!free(x + dx, y) || !free(x, y + dy))) continue // OSRS corner rule
                    if (seen.add(nx to ny)) queue += nx to ny
                }
            }
            val outside = seen.filter { Quadrant.of(it.first, it.second) != q }
            assertTrue(outside.isEmpty(), "$q leaks to $outside")
            assertEquals(85, seen.size)
        }
    }

    @Test
    fun shotsPassFromTheWallOutwardInFourTicks() {
        val layout = BarrageLayout(geo, Quadrant.SW, 2, 2)
        for (s in layout.segments) {
            val lane = layout.laneTiles(s)
            assertTrue(lane.isNotEmpty())
            val byDistance = lane.sortedBy { layout.distance(s, it) }
            val ticks = byDistance.map { layout.passTick(s, it, 4) }
            assertEquals(ticks.sorted(), ticks)
            assertEquals(1, ticks.first())
            assertEquals(4, ticks.last())
            for ((from, to) in layout.shotTargets(s)) {
                assertTrue(Quadrant.SW.contains(to.x, to.y), "target $to")
                assertTrue(from in s.tiles)
                if (s.edge == Edge.VERTICAL) assertEquals(from.y, to.y) else assertEquals(from.x, to.x)
            }
        }
    }

    @Test
    fun theFloorPortalHopLandsInTheNextQuadrant() {
        for (q in Quadrant.entries) for (t in geo.quadrantTiles(q)) {
            val d = ArenaGeometry.rotateCw(t)
            assertTrue(q.next.contains(d.x, d.y), "$q $t -> $d")
        }
        assertEquals(StaticTile(4119, 5681, 1), ArenaGeometry.rotateCw(StaticTile(4119, 5665, 1)))
    }
}
