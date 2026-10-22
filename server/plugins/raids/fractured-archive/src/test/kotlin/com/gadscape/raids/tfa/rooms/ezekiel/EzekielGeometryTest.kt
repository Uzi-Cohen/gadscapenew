package com.gadscape.raids.tfa.rooms.ezekiel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Arena, cleave ring and cross geometry (spec §4.2), static coordinates. */
class EzekielGeometryTest {

    /** Ezekiel at his spawn: SW (4062,5534), the centre cell. */
    private val sw = Tile(4062, 5534)

    @Test
    fun arenaIsANineByNineFloorOfThreeByThreeCells() {
        assertEquals(9, EzekielArena.FLOOR.width)
        assertEquals(9, EzekielArena.FLOOR.height)
        assertEquals(Tile(4059, 5531), EzekielArena.cellSw(0, 0))
        assertEquals(Tile(4065, 5534), EzekielArena.cellSw(2, 1))
        assertEquals(Pair(1, 1), EzekielArena.cellOf(EzekielArena.CENTRE))
        assertEquals(null, EzekielArena.cellOf(Tile(4068, 5535)))
    }

    @Test
    fun cleaveRingHasSixteenTiles() {
        val ring = Cleave.ring(sw)
        assertEquals(16, ring.size)
        assertEquals(16, ring.toSet().size)
        for (t in ring) {
            assertFalse(EzekielArena.under(sw, 3, t), "$t is under him")
            val dx = maxOf(sw.x - t.x, t.x - (sw.x + 2), 0)
            val dy = maxOf(sw.y - t.y, t.y - (sw.y + 2), 0)
            assertEquals(1, maxOf(dx, dy), "$t is one tile from his footprint")
        }
    }

    @Test
    fun nonTanksDirectlyBehindHimAreNotCleaved() {
        // Tank west of him (middle of his west side): the 3 tiles of the east side are spared.
        val tank = Tile(4061, 5535)
        val (sx, sy) = Cleave.swingDirection(sw, tank)
        assertEquals(Pair(-1, 0), Pair(sx, sy))
        val spared = Cleave.excluded(sw, sx, sy)
        assertEquals(setOf(Tile(4065, 5534), Tile(4065, 5535), Tile(4065, 5536)), spared)
        val hit = Cleave.hitTiles(sw, sx, sy)
        assertEquals(13, hit.size)
        assertTrue(tank in hit)
        assertFalse(Tile(4065, 5535) in hit, "directly behind him is safe")
        assertTrue(Tile(4065, 5537) in hit, "the corners behind him are still cleaved")
        assertTrue(Tile(4063, 5537) in hit, "beside him is cleaved")
        // Tank north of him: the south side is spared.
        val north = Cleave.excluded(sw, 0, 1)
        assertEquals(setOf(Tile(4062, 5533), Tile(4063, 5533), Tile(4064, 5533)), north)
    }

    @Test
    fun diagonalTankSparesTheOppositeCornerBlock() {
        // Tank at the north end of his west side: off his centre lines, so the swing is NW and the SE corner block is safe.
        val tank = Tile(4061, 5536)
        val (sx, sy) = Cleave.swingDirection(sw, tank)
        assertEquals(Pair(-1, 1), Pair(sx, sy))
        val spared = Cleave.excluded(sw, sx, sy)
        assertEquals(setOf(Tile(4065, 5533), Tile(4064, 5533), Tile(4065, 5534)), spared)
        assertEquals(13, Cleave.hitTiles(sw, sx, sy).size)
        assertTrue(Cleave.excluded(sw, 0, 0).isEmpty())
    }

    @Test
    fun crossCentredCoversTheCentreCellAndFourArms() {
        val tiles = Star.tiles(sw, 3, 3)
        // 3x3 core + 4 arms of 3x3 = 45 tiles; the four corner cells are safe.
        assertEquals(45, tiles.size)
        for (c in listOf(Pair(0, 0), Pair(2, 0), Pair(0, 2), Pair(2, 2))) {
            for (t in EzekielArena.cell(c.first, c.second).tiles()) assertFalse(t in tiles, "$t in corner cell $c")
        }
        for (c in listOf(Pair(1, 0), Pair(0, 1), Pair(1, 1), Pair(2, 1), Pair(1, 2))) {
            for (t in EzekielArena.cell(c.first, c.second).tiles()) assertTrue(t in tiles, "$t in cross cell $c")
        }
        // Two steps sideways from the middle of an arm leaves it.
        assertTrue(Tile(4063, 5532) in tiles)
        assertFalse(Tile(4061, 5532) in tiles)
    }

    @Test
    fun crossIsClippedToTheFloor() {
        val corner = Tile(4059, 5531) // Ezekiel in the SW cell
        val tiles = Star.tiles(corner, 3, 3)
        assertTrue(tiles.all { it in EzekielArena.FLOOR })
        // core 9 + north arm 9 + east arm 9 (south and west are off the floor)
        assertEquals(27, tiles.size)
    }

    @Test
    fun tankSuccessionFollowsTransporterOrder() {
        val order = TankOrder<String>()
        assertTrue(order.entered("a"))
        assertTrue(order.entered("b"))
        assertTrue(order.entered("c"))
        assertFalse(order.entered("a"), "re-entry keeps the first position")
        val alive = mutableSetOf("a", "b", "c")
        assertEquals("a", order.refresh { it in alive }, "the first transporter user is the tank")
        assertEquals(null, order.refresh { it in alive }, "no change while the tank lives")
        alive -= "a"
        assertEquals("b", order.lost("a") { it in alive })
        alive -= "b"
        assertEquals("c", order.refresh { it in alive })
        alive.clear()
        assertEquals(null, order.refresh { it in alive })
        assertEquals(null, order.tank)
    }
}
