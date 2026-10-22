package com.gadscape.raids.tfa.raid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockMappingTest {

    /** A block as MapBuilder would place it: regions (100, 1) + 13 chunks of padding. */
    private val m = BlockMapping((100 shl 6) + 104, (1 shl 6) + 104)

    @Test
    fun translateKeepsTheStaticLayout() {
        // spec §2.4: translate(x, y, p) = (x - 4032 + base.x, y - 5504 + base.y, p)
        assertEquals(m.baseX + (4127 - 4032), m.instanceX(4127))
        assertEquals(m.baseY + (5599 - 5504), m.instanceY(5599))
        assertEquals(0, m.instancePlane(4127, 5599, 0))
    }

    @Test
    fun guardianPlinthRoundTrips() {
        // WU-12 test: translate(4127,5599,0) and toStatic round-trip.
        val s = m.toStatic(m.instanceX(4127), m.instanceY(5599), m.instancePlane(4127, 5599, 0))
        assertEquals(StaticTile(4127, 5599, 0), s)
    }

    @Test
    fun everyRoomRoundTrips() {
        val tiles = listOf(
            StaticTile(4063, 5532), StaticTile(4186, 5599), StaticTile(4066, 5663), StaticTile(4185, 5663),
            StaticTile(4072, 5601), StaticTile(4184, 5535), StaticTile(4127, 5634, 1), StaticTile(4127, 5775, 2),
        )
        for (t in tiles) {
            val back = m.toStatic(m.instanceX(t.x), m.instanceY(t.y), m.instancePlane(t.x, t.y, t.plane))
            assertEquals(t, back, "round trip of $t")
        }
    }

    @Test
    fun campVariantShiftsOnlyTheNexus() {
        val nexus = 16471
        m.setPlaneOffset(nexus, 2)
        try {
            assertEquals(2, m.instancePlane(4127, 5582, 0))
            assertEquals(StaticTile(4127, 5582, 0), m.toStatic(m.instanceX(4127), m.instanceY(5582), 2))
            // Other rooms are untouched.
            assertEquals(0, m.instancePlane(4063, 5532, 0))
            assertEquals(1, m.instancePlane(4127, 5634, 1))
        } finally {
            m.setPlaneOffset(nexus, 0)
        }
    }

    @Test
    fun outsideTheBlockIsNull() {
        assertNull(m.toStatic(3222, 3222, 0))
        assertNull(m.toStatic(m.baseX - 1, m.baseY, 0))
        assertNull(m.toStatic(m.baseX + TfaBlock.WIDTH_TILES, m.baseY, 0))
    }

    @Test
    fun blockRegionsAndChunks() {
        assertTrue(TfaBlock.containsRegion(16471))
        assertTrue(TfaBlock.containsRegion(16214))
        assertTrue(TfaBlock.containsRegion(16728))
        assertTrue(TfaBlock.containsRegion(16474))
        assertTrue(!TfaBlock.containsRegion((62 shl 8) or 86)) // west of the block
        assertTrue(!TfaBlock.containsRegion((64 shl 8) or 85)) // south of the block (temple side)
        // The nexus (64,87) starts 8 chunks east and 8 north of the block's SW chunk.
        assertEquals((m.baseX shr 3) + 8, m.instanceChunkX(16471))
        assertEquals((m.baseY shr 3) + 8, m.instanceChunkY(16471))
        // 8x8 chunks starting 13 chunks into a region overlap 2x2 instance map squares.
        assertEquals(4, m.instanceRegionsOf(16471).size)
    }

    @Test
    fun tenBlocksDoNotOverlap() {
        // MapBuilder hands out disjoint region ranges; the mapping of each stays inside its own range.
        val blocks = (0 until 10).map { i -> BlockMapping(((100 + 5 * i) shl 6) + 104, (1 shl 6) + 104) }
        for (i in blocks.indices) for (j in blocks.indices) {
            if (i == j) continue
            val a = blocks[i]
            val b = blocks[j]
            val ax = a.instanceX(4223)
            assertTrue(ax < b.baseX || a.baseX > b.baseX + TfaBlock.WIDTH_TILES - 1, "blocks $i and $j overlap")
        }
    }
}
