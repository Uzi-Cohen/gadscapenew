package com.gadscape.raids.tfa.rooms.ezekiel

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The finale layout and patterns (spec §4.2 Finale, wiki lightning-pattern tables). */
class FinaleTest {

    private val cfg = TfaConfigTestKit.load(listOf(EzekielConfig), bundled = emptyMap())[EzekielConfig]
    private val pen = cfg.finale.penRect

    @Test
    fun sevenFenceCellsAroundThePenAndHisCell() {
        val fences = cfg.finale.fenceTiles.map { Rect.footprint(it.x, it.y, 3) }
        assertEquals(7, fences.size)
        val covered = fences.flatMap { it.tiles() }.toSet()
        assertEquals(63, covered.size, "7 distinct 3x3 cells")
        assertTrue(pen.tiles().none { it in covered }, "the pen stays open")
        val bossCell = Rect.footprint(cfg.finale.bossTile.x, cfg.finale.bossTile.y, 3)
        assertTrue(bossCell.tiles().none { it in covered }, "his cell stays open")
        // Every floor tile is the pen, his cell or a fence.
        assertEquals(81, covered.size + pen.tiles().size + bossCell.tiles().size)
        // He stands next to the pen: its east column (x 4064) touches his west edge (x 4065).
        assertEquals(pen.maxX + 1, cfg.finale.bossTile.x)
    }

    @Test
    fun eachStruckPairLeavesExactlyTheDocumentedSafeTiles() {
        // Struck shapes -> attack (the struck pair telegraphs the other side).
        assertEquals(listOf(FinaleSword.LEFT, FinaleSword.FRONT), FinaleAttack.RIGHT.struck)  // left + middle
        assertEquals(listOf(FinaleSword.FRONT, FinaleSword.RIGHT), FinaleAttack.LEFT.struck)  // middle + right
        assertEquals(listOf(FinaleSword.LEFT, FinaleSword.RIGHT), FinaleAttack.FRONT.struck)  // flat
        val expectedSafe = mapOf(
            FinaleAttack.RIGHT to setOf(Tile(4063, 5534), Tile(4062, 5534)),
            FinaleAttack.LEFT to setOf(Tile(4063, 5536), Tile(4062, 5536)),
            FinaleAttack.FRONT to setOf(Tile(4062, 5534), Tile(4062, 5535), Tile(4062, 5536)),
        )
        for ((attack, safe) in expectedSafe) {
            val struck = Finale.struckTiles(pen, cfg.finale.safe(attack)).toSet()
            assertEquals(pen.tiles().toSet() - safe, struck, "$attack")
            assertEquals(safe, pen.tiles().toSet() - struck)
            // The row next to him (x 4064) is never safe.
            assertTrue((5534..5536).all { Tile(4064, it) in struck })
        }
    }

    @Test
    fun impactSizeFollowsTheRow() {
        assertEquals(ImpactSize.LARGE, Finale.impactSize(pen, Tile(4064, 5535)))
        assertEquals(ImpactSize.MEDIUM, Finale.impactSize(pen, Tile(4063, 5535)))
        assertEquals(ImpactSize.SMALL, Finale.impactSize(pen, Tile(4062, 5535)))
    }

    @Test
    fun rotationsCycleTheirCombos() {
        val seen = HashSet<Int>()
        for (seed in 0L until 40L) {
            val schedule = Finale.Schedule(cfg.finale.combos, cfg.finale.rotations.values.toList(), Random(seed))
            seen += schedule.rotationIndex
            val rotation = Finale.DEFAULT_ROTATIONS[schedule.rotationIndex]
            val volleys = (0 until 8).map { schedule.next() }
            for (i in 0 until 8) assertEquals(Finale.DEFAULT_COMBOS[rotation[i % 4] - 1], volleys[i])
            assertEquals(8, schedule.volleys)
        }
        assertEquals(setOf(0, 1, 2), seen, "every rotation can be picked")
    }

    @Test
    fun penTilesForMovedPlayers() {
        val random = Random(3)
        val taken = HashSet<Tile>()
        repeat(9) {
            val t = Finale.penTileFor(pen, taken, random)
            assertTrue(t in pen)
            assertFalse(t in taken, "free pen tiles are used first")
            taken += t
        }
        assertTrue(Finale.penTileFor(pen, taken, random) in pen, "a full pen still gives a pen tile")
        val notCentre = Finale.penTileFor(pen, emptySet(), random) { it != Tile(4063, 5535) }
        assertFalse(notCentre == Tile(4063, 5535))
    }
}
