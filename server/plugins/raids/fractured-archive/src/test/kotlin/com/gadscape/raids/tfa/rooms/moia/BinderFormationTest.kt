package com.gadscape.raids.tfa.rooms.moia

import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinderFormationTest {

    private val lattices = BinderFormation.Lattice.entries

    @Test
    fun latticesAreInsideTheGraveyardAndNeverAdjacent() {
        assertEquals(25, BinderFormation.latticePoints(BinderFormation.Lattice.A).size)
        assertEquals(16, BinderFormation.latticePoints(BinderFormation.Lattice.B).size)
        for (l in lattices) {
            val points = BinderFormation.latticePoints(l)
            assertTrue(points.all { MoiaGeometry.onFloor(it) })
            assertTrue(BinderFormation.noTwoAdjacent(points), "lattice $l has adjacent points")
        }
    }

    /** WU-23: "binders never adjacent after any shuffle" (random formations, corpses, many steps). */
    @Test
    fun bindersAreNeverAdjacentAfterAnyShuffle() {
        val random = Random(23)
        repeat(400) { round ->
            val f = BinderFormation<Int>()
            val n = 1 + random.nextInt(12)
            f.pickPoints(n, random, emptyList(), 0).forEachIndexed { i, p -> f.add(i, p) }
            assertEquals(n, f.size)
            var step = 0
            repeat(60) {
                val reserved = (0 until random.nextInt(3)).map {
                    StaticTile(MoiaGeometry.FLOOR_MIN_X + random.nextInt(9), MoiaGeometry.FLOOR_MIN_Y + random.nextInt(9))
                }
                val before = f.tiles()
                val lattice = f.lattice
                val dir = BinderFormation.SHUFFLE_ORDER[step++ % 4]
                val moved = f.shuffle(dir, reserved)
                val after = f.tiles()
                assertEquals(before.size, f.size)
                assertTrue(after.all { MoiaGeometry.onFloor(it) }, "round $round: off the floor $after")
                assertTrue(BinderFormation.noTwoAdjacent(after), "round $round: adjacent after $dir: $after")
                assertTrue(after.all { it in BinderFormation.latticePoints(f.lattice) }, "round $round: mixed lattices $after")
                if (moved == null) {
                    assertEquals(before, after)
                    assertEquals(lattice, f.lattice)
                } else {
                    assertEquals(lattice.other, f.lattice)
                    // every binder moved exactly one diagonal tile and none onto a corpse
                    before.zip(after).forEach { (a, b) ->
                        assertEquals(1, kotlin.math.abs(a.x - b.x))
                        assertEquals(1, kotlin.math.abs(a.y - b.y))
                        assertTrue(b !in reserved)
                    }
                }
                // occasionally lose or gain a binder (deaths and respawns)
                if (random.nextInt(5) == 0 && f.size > 1) f.remove(f.members().first())
                if (random.nextInt(5) == 0) {
                    f.pickPoints(1, random, emptyList(), 0).firstOrNull()?.let { f.add(1000 + step, it) }
                }
            }
        }
    }

    @Test
    fun theLoopReturnsAnInnerFormationHome() {
        val f = BinderFormation<String>()
        val start = listOf(StaticTile(4061, 5661), StaticTile(4065, 5665), StaticTile(4063, 5663))
        start.forEachIndexed { i, t -> f.add("b$i", t) }
        BinderFormation.SHUFFLE_ORDER.forEach { assertNotNull(f.shuffle(it)) }
        assertEquals(start, f.tiles())
        assertEquals(BinderFormation.Lattice.A, f.lattice)
    }

    @Test
    fun edgeBindersReflect() {
        val f = BinderFormation<String>()
        f.add("corner", StaticTile(4067, 5667))
        val plan = assertNotNull(f.shuffle(Compass.NE))
        assertEquals(StaticTile(4066, 5666), plan["corner"])
    }

    @Test
    fun collidingReflectionsTakeAnotherDiagonal() {
        val f = BinderFormation<String>()
        f.add("a", StaticTile(4065, 5663))
        f.add("b", StaticTile(4067, 5663))
        val plan = assertNotNull(f.shuffle(Compass.NE))
        assertEquals(StaticTile(4066, 5664), plan["a"])
        assertTrue(plan["b"] != plan["a"])
        assertTrue(BinderFormation.noTwoAdjacent(f.tiles()))
    }

    @Test
    fun newBindersKeepTheirDistanceFromPlayers() {
        val random = Random(7)
        val f = BinderFormation<Int>()
        val players = listOf(StaticTile(4066, 5663), StaticTile(4060, 5660))
        repeat(200) {
            val points = f.pickPoints(12, random, players, 2)
            assertTrue(points.all { p -> players.all { MoiaGeometry.chebyshev(it, p) >= 2 } })
            assertTrue(points.toSet().size == points.size)
        }
        // occupied and reserved points are never offered
        f.add(1, StaticTile(4059, 5659))
        val free = f.freePoints(emptyList(), 0, listOf(StaticTile(4061, 5659)))
        assertTrue(StaticTile(4059, 5659) !in free && StaticTile(4061, 5659) !in free)
    }

    @Test
    fun diamondIsEightNonAdjacentTilesAroundTheCentre() {
        val diamond = MoiaConfig.DEFAULT_DIAMOND
        assertEquals(8, diamond.size)
        assertTrue(BinderFormation.noTwoAdjacent(diamond))
        assertTrue(diamond.all { MoiaGeometry.onFloor(it) })
        val centre = MoiaConfig.DEFAULT_ARMS.getValue(MoiaArm.CENTRE)
        // never under Moia's 3x3 in the centre, and close to it
        assertTrue(diamond.all { MoiaGeometry.footprintDistance(centre, MoiaGeometry.MOIA_SIZE, it) in 1..2 })
        val f = BinderFormation<Int>()
        f.useDiamond(diamond)
        assertEquals(diamond, f.freePoints(emptyList(), 0))
        f.add(1, diamond[0])
        assertEquals(diamond.drop(1), f.freePoints(emptyList(), 0))
        assertNull(f.planShuffle(Compass.NE), "no shuffle in the diamond")
    }
}
