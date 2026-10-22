package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The two cross-arm edges of a quadrant that carry P2 portal walls. */
enum class Edge {
    /** The N-S arm column next to the quadrant (x = 4126 for the west quadrants, 4128 for the east ones). */
    VERTICAL,
    /** The E-W arm row next to the quadrant (y = 5672 for the south quadrants, 5674 for the north ones). */
    HORIZONTAL,
}

/**
 * One 2x1 portal wall (loc 62685 purple / 62684 orange [CACHE `loc_ocellus.txt`: width 2, blockwalk, not
 * blockrange]) on an arm edge of a quadrant.
 *
 * @property index 0 = next to the arena centre, 4 = the outermost.
 * @property tiles the two arm tiles it covers, nearest the centre first.
 * @property locTile SW tile of the loc; [rotation] makes it 1x2 on vertical edges.
 * @property fire direction its shots travel (away from the arm, across the quadrant).
 */
data class PortalSegment(
    val quadrant: Quadrant,
    val edge: Edge,
    val index: Int,
    val tiles: List<StaticTile>,
    val locTile: StaticTile,
    val rotation: Int,
    val fire: Compass,
)

/**
 * Geometry of one P2 portal barrage (spec §4.8 "Phase 2"): portal walls on the two arm edges bordering
 * [quadrant], five 2-tile segments per edge, one orange segment per edge ([orangeVertical] = `attack_column` on
 * the vertical edge, [orangeHorizontal] = `attack_row` on the horizontal edge). Each purple segment fires along
 * its 2-tile lane across the quadrant; the only tiles in no purple lane are the intersection of the two orange
 * lanes (a 2x2 for indexes 0..3).
 *
 * The quadrant edge is 11 tiles long; the five segments cover the 10 nearest the centre. The outermost lane
 * (index 4) also covers the 11th row / column, and the 11th arm tile of each edge is closed with plain collision
 * ([blockTiles]) so the walls seal the quadrant [HOUSE: no 1-tile portal loc exists].
 *
 * Pure geometry; unit tested in `BarrageLayoutTest`.
 */
class BarrageLayout(
    val geo: ArenaGeometry,
    val quadrant: Quadrant,
    val orangeVertical: Int,
    val orangeHorizontal: Int,
) {
    /** Arm column of the vertical edge. */
    val verticalX: Int = ArenaGeometry.CX + quadrant.sx

    /** Arm row of the horizontal edge. */
    val horizontalY: Int = ArenaGeometry.CY + quadrant.sy

    val quadrantTiles: List<StaticTile> = geo.quadrantTiles(quadrant)

    val segments: List<PortalSegment> = buildList {
        val cx = ArenaGeometry.CX
        val cy = ArenaGeometry.CY
        val q = quadrant
        for (i in 0 until SEGMENTS) {
            val near = 2 + 2 * i
            val v = listOf(geo.tile(verticalX, cy + q.sy * near), geo.tile(verticalX, cy + q.sy * (near + 1)))
            add(PortalSegment(q, Edge.VERTICAL, i, v, geo.tile(verticalX, min(v[0].y, v[1].y)),
                rotation = if (q.sx < 0) 1 else 3, fire = if (q.sx < 0) Compass.W else Compass.E))
        }
        for (j in 0 until SEGMENTS) {
            val near = 2 + 2 * j
            val h = listOf(geo.tile(cx + q.sx * near, horizontalY), geo.tile(cx + q.sx * (near + 1), horizontalY))
            add(PortalSegment(q, Edge.HORIZONTAL, j, h, geo.tile(min(h[0].x, h[1].x), horizontalY),
                rotation = if (q.sy < 0) 0 else 2, fire = if (q.sy < 0) Compass.S else Compass.N))
        }
    }

    /** The 11th arm tile of each edge (the outermost), closed with collision while the walls stand. */
    val blockTiles: List<StaticTile> = listOf(
        geo.tile(verticalX, ArenaGeometry.CY + quadrant.sy * ArenaGeometry.RADIUS),
        geo.tile(ArenaGeometry.CX + quadrant.sx * ArenaGeometry.RADIUS, horizontalY),
    )

    fun isOrange(s: PortalSegment): Boolean =
        (s.edge == Edge.VERTICAL && s.index == orangeVertical) || (s.edge == Edge.HORIZONTAL && s.index == orangeHorizontal)

    /** Lane index (0..4) of [t] for the walls of [edge]: rows for the vertical edge, columns for the horizontal one. */
    fun laneIndex(edge: Edge, t: StaticTile): Int {
        val d = if (edge == Edge.VERTICAL) abs(t.y - ArenaGeometry.CY) else abs(t.x - ArenaGeometry.CX)
        return min(SEGMENTS - 1, max(0, (d - 2) / 2))
    }

    /** The quadrant tiles a shot of [s] crosses. */
    fun laneTiles(s: PortalSegment): List<StaticTile> = quadrantTiles.filter { laneIndex(s.edge, it) == s.index }

    /** Distance of a lane tile from its wall (1 = the tile next to the wall). */
    fun distance(s: PortalSegment, t: StaticTile): Int =
        if (s.edge == Edge.VERTICAL) abs(t.x - verticalX) else abs(t.y - horizontalY)

    fun laneLength(s: PortalSegment): Int = laneTiles(s).maxOfOrNull { distance(s, it) } ?: 0

    /**
     * The tick (1..[shotTicks]) after the volley on which the shot of [s] passes [t]: the shot crosses the lane at
     * constant speed in [shotTicks] (spot 4269's anim, 120 cycles = 4 t [CACHE]).
     */
    fun passTick(s: PortalSegment, t: StaticTile, shotTicks: Int): Int {
        val length = max(1, laneLength(s))
        val d = distance(s, t)
        return ((d * shotTicks + length - 1) / length).coerceIn(1, shotTicks)
    }

    /** Tiles in no purple lane (the 2x2 orange intersection). */
    fun safeTiles(): List<StaticTile> = quadrantTiles.filter {
        laneIndex(Edge.VERTICAL, it) == orangeVertical && laneIndex(Edge.HORIZONTAL, it) == orangeHorizontal
    }

    /** Lane tiles of every purple segment that [t] lies in. */
    fun purpleLanesAt(t: StaticTile): List<PortalSegment> =
        segments.filter { !isOrange(it) && laneIndex(it.edge, t) == it.index && quadrant.contains(t.x, t.y) }

    /** For each tile of [s], the far end of its row / column inside the quadrant (the shot's target tile). */
    fun shotTargets(s: PortalSegment): List<Pair<StaticTile, StaticTile>> = s.tiles.mapNotNull { from ->
        val far = if (s.edge == Edge.VERTICAL) {
            val hw = ArenaGeometry.halfWidth(from.y)
            if (hw < 2) null else geo.tile(ArenaGeometry.CX + quadrant.sx * hw, from.y)
        } else {
            val ys = (ArenaGeometry.MIN_Y..ArenaGeometry.MAX_Y).filter { quadrant.contains(from.x, it) }
            if (ys.isEmpty()) null else geo.tile(from.x, if (quadrant.sy < 0) ys.min() else ys.max())
        }
        far?.let { from to it }
    }

    companion object {
        const val SEGMENTS = 5
    }
}
