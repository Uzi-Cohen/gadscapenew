package com.gadscape.raids.tfa.rooms.guardian

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec WU-20 acceptance: "collision confirms the SW / NE alcove gaps and that SE is unreachable".
 *
 * Fixture: the atrium of region 16471 rendered from cache 2746 (`raids4/archive-guardian/arena_map.txt`, plane 0;
 * the instance copies variant plane 1, whose locs in this box are identical except one decoration at 4138,5588,
 * checked with `audit/raids4/tools/MapLocs`). Legend: `#` wall, `F` fence01 62582 (blocks walking and range),
 * `f` fence03 62585/62586 (blocks walking, blockrange=no), `P` pillar, `B` battery base 62581 (blockwalk), `D` door,
 * `r`/`C` pedestal (walkable), `*` flowers and `o` decoration (walkable).
 */
class GuardianArenaTest {

    private val minX = 4108
    private val maxY = 5614

    private val rows = listOf(
        "o....D#.........................#D.....", // 5614
        "o....D...........................D.....", // 5613
        ".....D...........................D.....", // 5612
        "oo...D...........................D....o", // 5611
        "oo...D#.........................#D.....", // 5610
        "######fffPP.................PPFFFF#####", // 5609
        "######.ffPP..............*..PP..*######", // 5608
        "######BB..f.................F..BB######", // 5607
        "######BB..f..............*..F..BB######", // 5606
        "######BB.ff..*..............F..BB######", // 5605
        "######BB.ff..............*..F..BB######", // 5604
        "######.ffPP..*...........*..PP...######", // 5603
        "######fffPP..*...*.......*..PP.ff######", // 5602
        ".o...D#.........................#D.....", // 5601
        ".....D............rrr............D....o", // 5600
        ".....D............rCr.*..........D.....", // 5599
        ".....D............rrr............D.....", // 5598
        "o....D#.........*.....*.........#D.....", // 5597
        "######ff.PP..*.....*.....*..PPFFFF#####", // 5596
        "######*..PP..*....*.........PP...######", // 5595
        "######BB..F..*..............F..BB######", // 5594
        "######BB..F..............*..F..BB######", // 5593
        "######BB..F..............*..F..BB######", // 5592
        "######BB..F..............*..F..BB######", // 5591
        "######*..PP..*...........*..PP...######", // 5590
        "#####FFFFPP..*..............PPFFFF#####", // 5589
        "o....D#.........................#D...oo", // 5588
        "o....D...........................D...oo", // 5587
        ".....D...........................D.....", // 5586
        "oo...D...........................D..oo.", // 5585
        "oo...D#.........................#D..oo.", // 5584
    )

    private fun at(x: Int, y: Int): Char {
        val row = maxY - y
        if (row !in rows.indices) return '#'
        val col = x - minX
        return rows[row].getOrElse(col) { '#' }
    }

    private fun walkable(x: Int, y: Int) = at(x, y) in ".*rCo"

    private fun blocksRange(x: Int, y: Int) = at(x, y) in "#FPD"

    /** OSRS stepping: diagonals need both orthogonal neighbours free. */
    private fun reachable(fromX: Int, fromY: Int): Set<Tile> {
        val seen = HashSet<Tile>()
        val queue = ArrayDeque<Tile>()
        val start = Tile(fromX, fromY)
        seen += start
        queue += start
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = t.x + dx
                val ny = t.y + dy
                if (!walkable(nx, ny)) continue
                if (dx != 0 && dy != 0 && (!walkable(t.x + dx, t.y) || !walkable(t.x, t.y + dy))) continue
                val n = Tile(nx, ny)
                if (seen.add(n)) queue += n
            }
        }
        return seen
    }

    /** Tiles from which a player can melee a 2x2 battery (orthogonally adjacent). */
    private fun meleeTiles(swX: Int, swY: Int): List<Tile> =
        (0..1).flatMap { listOf(Tile(swX + it, swY - 1), Tile(swX + it, swY + 2), Tile(swX - 1, swY + it), Tile(swX + 2, swY + it)) }

    private val floor = reachable(4127, 5599)

    @Test
    fun batteriesStandOnTheirBases() {
        for (a in Alcove.entries) {
            val t = a.defaultTile
            for (dx in 0..1) for (dy in 0..1) assertEquals('B', at(t.x + dx, t.y + dy), "$a battery tile ${t.x + dx},${t.y + dy}")
        }
    }

    @Test
    fun southWestAndNorthEastAreMeleeAlcoves() {
        assertTrue(Tile(4116, 5596) in floor, "SW gap at 4116,5596")
        assertTrue(meleeTiles(4114, 5592).any { it in floor }, "SW battery can be meleed")
        assertTrue(Tile(4138, 5602) in floor, "NE gap at 4138,5602")
        assertTrue(meleeTiles(4139, 5605).any { it in floor }, "NE battery can be meleed")
    }

    @Test
    fun southEastIsBeamOnly() {
        assertFalse(meleeTiles(4139, 5592).any { it in floor }, "SE battery cannot be reached on foot")
        // Every straight line from the floor toward it crosses fence01 (blocks range).
        val crossesFence = listOf(Tile(4135, 5592), Tile(4135, 5593), Tile(4137, 5597)).all { from ->
            GuardianGeometry.bresenham(from.x, from.y, 4139, 5592).drop(1).dropLast(1).any { blocksRange(it.x, it.y) }
        }
        assertTrue(crossesFence)
    }

    @Test
    fun northWestIsRangedOnly() {
        assertFalse(meleeTiles(4114, 5605).any { it in floor }, "NW battery cannot be reached on foot")
        // ... but fence03 has blockrange=no: a straight shot from the floor reaches it.
        assertTrue(Tile(4119, 5605) in floor)
        assertTrue(GuardianGeometry.bresenham(4119, 5605, 4115, 5605).none { blocksRange(it.x, it.y) })
    }

    @Test
    fun bossFloorBoxIsWalkableAroundThePedestal() {
        assertTrue(Tile(4127, 5590) in floor)
        assertTrue(Tile(4127, 5608) in floor)
        assertTrue(GuardianGeometry.under(4125, 5597, 5, 4127, 5599))
        for (x in 4125..4129) for (y in 5597..5601) assertTrue(Tile(x, y) in floor, "boss footprint $x,$y")
    }
}
