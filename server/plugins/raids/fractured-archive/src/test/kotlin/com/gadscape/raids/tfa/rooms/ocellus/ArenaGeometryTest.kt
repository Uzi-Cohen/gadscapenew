package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaGeometryTest {

    private val geo = ArenaGeometry()

    @Test
    fun octagonMatchesTheCacheMap() {
        // raids4/ocellus/NOTES.md §3.3: 481 walkable tiles, 141 on the cross, 85 per quadrant
        assertEquals(481, geo.tiles.size)
        assertEquals(141, geo.crossTiles().size)
        for (q in Quadrant.entries) assertEquals(85, geo.quadrantTiles(q).size, "$q")
        assertEquals(481, geo.crossTiles().size + Quadrant.entries.sumOf { geo.quadrantTiles(it).size })
        // row widths from the south: 9, 11, ..., 23, then 25 for y 5669-5677
        val widths = (5661..5685).map { y -> geo.tiles.count { it.y == y } }
        assertEquals(listOf(9, 11, 13, 15, 17, 19, 21, 23) + List(9) { 25 } + listOf(23, 21, 19, 17, 15, 13, 11, 9), widths)
        assertTrue(geo.tiles.all { it.plane == 1 })
    }

    @Test
    fun quadrantsAndCentre() {
        assertEquals(Quadrant.SW, Quadrant.of(4119, 5665))
        assertEquals(Quadrant.NW, Quadrant.of(4119, 5680))
        assertEquals(Quadrant.NE, Quadrant.of(4135, 5680))
        assertEquals(Quadrant.SE, Quadrant.of(4135, 5665))
        assertNull(Quadrant.of(4127, 5673))
        assertNull(Quadrant.of(4126, 5665)) // N-S arm
        assertTrue(ArenaGeometry.onCross(4127, 5661))
        assertFalse(ArenaGeometry.inOctagon(4115, 5661)) // clipped corner
        assertEquals(listOf(Quadrant.NW, Quadrant.NE, Quadrant.SE, Quadrant.SW), Quadrant.entries.map { it.next })
    }

    @Test
    fun clockwiseRotationMapsQuadrantsOntoEachOther() {
        for (q in Quadrant.entries) {
            val rotated = geo.quadrantTiles(q).map { ArenaGeometry.rotateCw(it) }.toSet()
            assertEquals(geo.quadrantTiles(q.next).toSet(), rotated, "$q -> ${q.next}")
        }
        val t = StaticTile(4120, 5666, 1)
        assertEquals(t, ArenaGeometry.rotateCw(t, 4))
    }

    @Test
    fun footprintsInsideTheOctagon() {
        val anchors = mapOf(
            Quadrant.SW to StaticTile(4119, 5665, 1), Quadrant.NW to StaticTile(4119, 5679, 1),
            Quadrant.NE to StaticTile(4133, 5679, 1), Quadrant.SE to StaticTile(4133, 5665, 1),
        )
        for ((q, a) in anchors) {
            for (dx in 0..2) for (dy in 0..2) {
                assertTrue(q.contains(a.x + dx, a.y + dy), "anchor $q footprint tile ${a.x + dx},${a.y + dy}")
            }
        }
        // the intro / P1 spawn 3x3 at (4126,5668) sits on the south arm; the centre 3x3 covers the Blade tile
        for (dx in 0..2) for (dy in 0..2) assertTrue(ArenaGeometry.onCross(4126 + dx, 5668 + dy))
        assertEquals(0, ArenaGeometry.distanceToFootprint(4127, 5673, 4126, 5672, 3))
    }

    @Test
    fun tendrilLinesRunFromTheFootprintToTheEdge() {
        // SW anchor: the SW diagonal points straight into the octagon's wall
        val sw = StaticTile(4119, 5665, 1)
        assertTrue(geo.tendrilLine(sw.x, sw.y, 3, Compass.SW).isEmpty())
        val north = geo.tendrilLine(sw.x, sw.y, 3, Compass.N)
        assertEquals(StaticTile(4120, 5668, 1), north.first())
        assertTrue(north.all { it.x == 4120 && ArenaGeometry.inOctagon(it.x, it.y) })
        assertFalse(ArenaGeometry.inOctagon(4120, north.last().y + 1))
        // centre: all eight directions reach the edge, 11 tiles straight, 7 diagonal
        val c = StaticTile(4126, 5672, 1)
        for (d in ArenaGeometry.CLOCKWISE) assertTrue(geo.tendrilLine(c.x, c.y, 3, d).isNotEmpty(), "$d")
        assertEquals(11, geo.tendrilLine(c.x, c.y, 3, Compass.N).size)
        assertEquals(7, geo.tendrilLine(c.x, c.y, 3, Compass.NE).size)
    }

    @Test
    fun slamRingIsChebyshevTwo() {
        val ring = geo.ringAround(4126, 5672, 3, 2)
        assertEquals(7 * 7 - 9, ring.size)
        assertTrue(ring.all { ArenaGeometry.distanceToFootprint(it.x, it.y, 4126, 5672, 3) in 1..2 })
    }
}
