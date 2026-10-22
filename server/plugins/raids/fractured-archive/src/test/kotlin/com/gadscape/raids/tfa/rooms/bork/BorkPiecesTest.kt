package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BorkPiecesTest {

    private val cfg = Harness.DEFAULT_CONFIG

    // ------------------------------------------------------------------------------------------------- tempo

    /** Spec §4.3: > 66% → 7, 33-66% → 6, < 33% → 4; dazes 2 × tempo = 14 / 12 / 8 t. */
    @Test
    fun tempoTiers() {
        val t = cfg.tempo
        assertEquals(7, Tempo.speed(t, 1500, 1500))
        assertEquals(7, Tempo.speed(t, 991, 1500)) // 66.07%
        assertEquals(6, Tempo.speed(t, 990, 1500)) // exactly 66%
        assertEquals(6, Tempo.speed(t, 496, 1500))
        assertEquals(4, Tempo.speed(t, 495, 1500)) // exactly 33%
        assertEquals(4, Tempo.speed(t, 1, 1500))
        assertEquals(4, Tempo.speed(t, 0, 1500))
        assertEquals(listOf(14, 12, 8), listOf(7, 6, 4).map { Tempo.dazeTicks(it, cfg.bork.dazeTicksPerSpeed) })
    }

    // ------------------------------------------------------------------------------------------------- arena

    /** CACHE map: 15 x 17 box, 4 corners blocked, 9 cart tiles: 242 walkable (the flood fill finds 242). */
    @Test
    fun arenaTiles() {
        assertEquals(242, BorkArena.WALKABLE.size)
        assertFalse(BorkArena.isWalkable(4184, 5591))
        assertFalse(BorkArena.isWalkable(4198, 5607))
        assertTrue(BorkArena.isWalkable(4185, 5591))
        assertTrue(BorkArena.isWalkable(4184, 5592))
        for (x in 4190..4192) for (y in 5598..5600) {
            assertFalse(BorkArena.isWalkable(x, y))
            assertTrue(BorkArena.isCart(x, y))
        }
        assertFalse(BorkArena.isWalkable(4183, 5599))
        assertFalse(BorkArena.isWalkable(4199, 5599))
        assertTrue(BorkArena.isWalkable(4186, 5599)) // entry tile
        assertEquals(StaticTile(4191, 5599, 0), BorkArena.middle(BorkArena.CART_SW, BorkArena.CART_SIZE))
    }

    @Test
    fun footprintDistances() {
        val sw = StaticTile(4194, 5597, 0) // 4194..4197 x 5597..5600
        assertEquals(0, BorkArena.distToFootprint(sw, 4, 4195, 5598))
        assertEquals(1, BorkArena.distToFootprint(sw, 4, 4193, 5597))
        assertEquals(1, BorkArena.distToFootprint(sw, 4, 4198, 5601)) // diagonal corner
        assertEquals(3, BorkArena.distToFootprint(sw, 4, 4191, 5603))
        // the small beat covers the 6x6 (minus nothing: 4193..4198 x 5596..5601 are all floor here)
        val small = BorkArena.WALKABLE.count { BorkArena.distToFootprint(sw, 4, it) <= 1 }
        assertEquals(36, small)
        assertTrue(BorkArena.squareTouchesFootprint(StaticTile(4193, 5598, 0), 3, sw, 4))
        assertFalse(BorkArena.squareTouchesFootprint(StaticTile(4192, 5598, 0), 3, sw, 4))
        assertEquals(25 - 0, BorkArena.walkableSquare(StaticTile(4187, 5603, 0), 5).size)
        assertEquals(19, BorkArena.walkableSquare(StaticTile(4189, 5599, 0), 5).size) // 6 of its tiles are the cart
    }

    // ------------------------------------------------------------------------------------------- cart shadow

    @Test
    fun cartShadowBehindTheCart() {
        val shadow = CartShadow(CartShadow.Mode.BRESENHAM, Bridge::line)
        val bork = StaticTile(4194, 5597, 0) // east of the cart, middle tile (4196,5599)
        assertTrue(shadow.isShadowed(bork, 4, StaticTile(4186, 5599, 0))) // straight behind the cart
        assertTrue(shadow.isShadowed(bork, 4, StaticTile(4189, 5600, 0)))
        assertFalse(shadow.isShadowed(bork, 4, StaticTile(4186, 5606, 0))) // far north-west corner: in the open
        assertFalse(shadow.isShadowed(bork, 4, StaticTile(4193, 5599, 0))) // between Bork and the cart
        assertFalse(shadow.isShadowed(bork, 4, StaticTile(4196, 5604, 0))) // beside Bork
        // nothing on Bork's side of the cart's east face is shadowed; the far west column behind it is
        val tiles = shadow.shadowed(bork, 4, BorkArena.WALKABLE)
        assertTrue(tiles.none { it.x >= 4193 })
        assertTrue(listOf(5598, 5599, 5600).all { StaticTile(4184, it, 0) in tiles })
        // the segment rule is a little wider and contains the Bresenham core straight behind the cart
        val seg = CartShadow(CartShadow.Mode.SEGMENT, Bridge::line)
        assertTrue(seg.isShadowed(bork, 4, StaticTile(4186, 5599, 0)))
        assertFalse(seg.isShadowed(bork, 4, StaticTile(4186, 5606, 0)))
        assertTrue(seg.shadowed(bork, 4, BorkArena.WALKABLE).none { it.x >= 4193 })
    }

    @Test
    fun cartShadowFollowsBork() {
        val shadow = CartShadow(CartShadow.Mode.BRESENHAM, Bridge::line)
        val north = StaticTile(4189, 5603, 0) // 4189..4192 x 5603..5606, north of the cart
        val tiles = shadow.shadowed(north, 4, BorkArena.WALKABLE)
        assertTrue(StaticTile(4191, 5594, 0) in tiles)
        assertTrue(tiles.none { it.y >= 5601 })
        assertFalse(shadow.isShadowed(north, 4, StaticTile(4198, 5594, 0)))
    }

    // ------------------------------------------------------------------------------------------- wave dust

    @Test
    fun waveDustDirections() {
        val sw = StaticTile(4194, 5597, 0)
        assertEquals(-1, WaveDust.angle(sw, 4, StaticTile(4195, 5598, 0)))
        assertEquals(0, WaveDust.angle(sw, 4, StaticTile(4195, 5596, 0)))      // south
        assertEquals(90, WaveDust.angle(sw, 4, StaticTile(4193, 5598, 0)))     // west
        assertEquals(180, WaveDust.angle(sw, 4, StaticTile(4195, 5601, 0)))    // north
        assertEquals(270, WaveDust.angle(sw, 4, StaticTile(4198, 5598, 0)))    // east
        assertEquals(45, WaveDust.angle(sw, 4, StaticTile(4193, 5596, 0)))     // south-west
        assertEquals(135, WaveDust.angle(sw, 4, StaticTile(4193, 5601, 0)))
        assertEquals(225, WaveDust.angle(sw, 4, StaticTile(4198, 5601, 0)))
        assertEquals(315, WaveDust.angle(sw, 4, StaticTile(4198, 5596, 0)))
        val dust = TfaIds.Spot.WAVE_DUST
        assertEquals(dust[0], WaveDust.spotFor(0, long = true, angleOffset = 0))   // 4394
        assertEquals(dust[1], WaveDust.spotFor(0, long = false, angleOffset = 0))  // 4395
        assertEquals(dust[2], WaveDust.spotFor(90, long = true, angleOffset = 0))  // 4396
        assertEquals(dust[7], WaveDust.spotFor(270, long = false, angleOffset = 0)) // 4401
        assertEquals(dust[8], WaveDust.spotFor(45, long = true, angleOffset = 0))  // 4402
        assertEquals(dust[11], WaveDust.spotFor(315, long = false, angleOffset = 0)) // 4405
        assertEquals(dust[4], WaveDust.spotFor(0, long = true, angleOffset = 180))
        assertEquals(4394, dust[0])
        assertEquals(4405, dust[11])
        // delays grow by ring
        val plan = WaveDust.plan(sw, 4, listOf(StaticTile(4193, 5598, 0), StaticTile(4191, 5598, 0), StaticTile(4195, 5598, 0)), true, cfg.wave)
        assertEquals(2, plan.size) // the tile under him gets none
        assertEquals(listOf(cfg.wave.cyclesPerTile, 3 * cfg.wave.cyclesPerTile), plan.map { it.delayCycles })
    }

    // ----------------------------------------------------------------------------------------------- oil

    private class OilSink : OilField.Sink {
        val log = ArrayList<String>()
        override fun spawn(tile: StaticTile, rotation: Int) { log += "spawn $tile $rotation" }
        override fun fade(tile: StaticTile, rotation: Int) { log += "fade $tile $rotation" }
        override fun remove(tile: StaticTile) { log += "remove $tile" }
    }

    @Test
    fun oilLifetimeAndDamage() {
        val sink = OilSink()
        val oil = OilField(30, 6..7, sink)
        val tile = StaticTile(4187, 5594, 0)
        val p = FakeFighter("p", tile)
        val q = FakeFighter("q", StaticTile(4188, 5594, 0))
        val hits = ArrayList<Pair<String, Int>>()
        val rnd = Random(1)
        oil.add(tile, 10, 2)
        assertEquals(listOf("spawn $tile 2"), sink.log)
        oil.tick(10, listOf(p, q), rnd) { f, d -> hits += f.name to d } // placed this tick: no damage yet
        assertTrue(hits.isEmpty())
        for (t in 11..39) oil.tick(t, listOf(p, q), rnd) { f, d -> hits += f.name to d }
        assertEquals(29, hits.size)
        assertTrue(hits.all { it.first == "p" && it.second in 6..7 })
        assertTrue(hits.any { it.second == 6 } && hits.any { it.second == 7 })
        oil.tick(40, listOf(p), rnd) { f, d -> hits += f.name to d } // expired at 10 + 30
        assertEquals(29, hits.size)
        assertEquals("fade $tile 2", sink.log.last())
        oil.tick(41, listOf(p), rnd) { _, _ -> }
        assertEquals("remove $tile", sink.log.last())
        assertEquals(0, oil.size)
    }

    @Test
    fun oilRefreshAndClear() {
        val sink = OilSink()
        val oil = OilField(30, 6..7, sink)
        val a = StaticTile(4187, 5594, 0)
        val b = StaticTile(4188, 5594, 0)
        oil.add(a, 0, 0)
        oil.add(a, 20, 3) // refresh: no second spawn, lives to 50
        oil.add(b, 5, 1)
        for (t in 1..49) oil.tick(t, emptyList<FakeFighter>(), Random(1)) { _, _ -> }
        assertTrue(oil.isOiled(a))
        assertFalse(oil.isOiled(b))
        assertEquals(2, sink.log.count { it.startsWith("spawn") })
        oil.clear()
        assertEquals(0, oil.size)
        assertTrue(sink.log.contains("remove $a"))
    }

    // --------------------------------------------------------------------------------------------- timeline

    @Test
    fun timelineOrderAndCancel() {
        val t = Timeline()
        val log = ArrayList<String>()
        t.after(2, Timeline.Tag.BORK) { log += "bork@${t.now}"; t.after(0, Timeline.Tag.BORK) { log += "same@${t.now}" } }
        t.after(2, Timeline.Tag.FLIGHT) { log += "flight@${t.now}" }
        t.after(3, Timeline.Tag.BORK) { log += "late@${t.now}" }
        t.advance()
        t.advance()
        assertEquals(listOf("bork@2", "flight@2", "same@2"), log)
        assertEquals(1, t.cancel(Timeline.Tag.BORK))
        t.advance()
        assertEquals(3, log.size)
        t.after(1, Timeline.Tag.DEATH) { log += "x" }
        t.cancelAll()
        t.advance()
        assertEquals(3, log.size)
        assertEquals(0, t.pending())
    }
}
