package com.gadscape.raids.tfa.rooms.xarth

import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sign

/**
 * The three cover pillars, identified by tile (spec §4.7 "Arena"; `r16726_locs.txt`): 2x2 "Rubble" locs that block
 * walking and line of sight, each with an invisible helper NPC 16685 on the same south-west tile. The loc id is not
 * the pillar index (62712 is placed twice), so the server keys them by tile.
 */
enum class Pillar(val label: String, val sw: StaticTile, private val east: Boolean) {
    A("south-east", StaticTile(4193, 5530, 0), false),
    B("north-east", StaticTile(4193, 5540, 0), false),
    C("east", StaticTile(4196, 5536, 0), true);

    /** The Rubble loc placed at [sw]: 62712 for A and B, 62711 for C [CACHE r16726_locs]. */
    val locId: Int get() = if (east) XarthIds.LOC_RUBBLE_EAST else XarthIds.LOC_RUBBLE

    fun contains(x: Int, y: Int): Boolean = x >= sw.x && y >= sw.y && x <= sw.x + 1 && y <= sw.y + 1

    fun contains(t: StaticTile): Boolean = contains(t.x, t.y)

    /** Chebyshev distance from [t] to this pillar's 2x2. */
    fun distance(t: StaticTile): Int =
        maxOf(maxOf(sw.x - t.x, 0, t.x - (sw.x + 1)), maxOf(sw.y - t.y, 0, t.y - (sw.y + 1)))
}

/**
 * The eight corpse piles (spec §4.7; `r16726_locs.txt`, all type 10 rotation 0) in the wall band outside the
 * arena's invisible wall, and the arena tile where their corpses appear [INF]. [index] is the position in
 * `TfaIds.Loc.XARTH_PILES` / `XARTH_PILES_FIRE` (N, E, S, W, NW, NE, SE, SW).
 */
enum class Pile(val index: Int, val tile: StaticTile, val size: Int, val entry: StaticTile) {
    N(0, StaticTile(4191, 5545, 0), 1, StaticTile(4191, 5543, 0)),
    E(1, StaticTile(4201, 5535, 0), 1, StaticTile(4199, 5535, 0)),
    S(2, StaticTile(4191, 5524, 0), 1, StaticTile(4191, 5527, 0)),
    W(3, StaticTile(4181, 5535, 0), 1, StaticTile(4183, 5535, 0)),
    NW(4, StaticTile(4180, 5544, 0), 3, StaticTile(4183, 5543, 0)),
    NE(5, StaticTile(4200, 5544, 0), 3, StaticTile(4199, 5543, 0)),
    SE(6, StaticTile(4200, 5524, 0), 3, StaticTile(4199, 5527, 0)),
    SW(7, StaticTile(4180, 5524, 0), 3, StaticTile(4183, 5527, 0));

    val locId: Int get() = XarthIds.locPile(index)
    val fireLocId: Int get() = XarthIds.locPileFire(index)

    companion object {
        val ENTRY_TILES: Set<StaticTile> = entries.map { it.entry }.toSet()
    }
}

/** A turn seq to play (spec [C+] 14981 left / 14982 right), or none. */
enum class TurnSide { NONE, LEFT, RIGHT }

/**
 * Static geometry of the Xarth arena (spec §4.7 "Arena" [CACHE map 16726]; `raids4/xarth/arena_map.txt`): the sealed
 * 17x17 box x 4183-4199, y 5527-5543, his 3x3 on the scorch decal (centre 4191,5535; static all fight [HOUSE]), the
 * pillars and piles. Pure: unit tested without a world.
 */
object XarthArena {

    const val MIN_X = 4183
    const val MAX_X = 4199
    const val MIN_Y = 5527
    const val MAX_Y = 5543
    const val PLANE = 0

    /** His size [CACHE npc 16684 size=3]. */
    const val SIZE = 3

    /** Centre of the scorch decal 62746 and of his 3x3 when he stands on [SPAWN]. */
    val CENTRE = StaticTile(4191, 5535, PLANE)

    /** South-west tile of his 3x3 (house: static the whole fight). */
    val SPAWN = StaticTile(4190, 5534, PLANE)

    /** Client face angles (0 south, 512 west, 1024 north, 1536 east). */
    const val SOUTH = 0
    const val WEST = 512
    const val NORTH = 1024
    const val EAST = 1536

    fun inBox(t: StaticTile): Boolean = t.plane == PLANE && t.x in MIN_X..MAX_X && t.y in MIN_Y..MAX_Y

    /** Every tile of the 17x17 box. */
    val BOX: List<StaticTile> = (MIN_Y..MAX_Y).flatMap { y -> (MIN_X..MAX_X).map { x -> StaticTile(x, y, PLANE) } }

    fun isPillarTile(t: StaticTile): Boolean = Pillar.entries.any { it.contains(t) }

    fun inFootprint(t: StaticTile, sw: StaticTile = SPAWN): Boolean =
        t.x >= sw.x && t.y >= sw.y && t.x < sw.x + SIZE && t.y < sw.y + SIZE

    /** Chebyshev distance from [t] to his 3x3 (0 inside it). */
    fun footprintDistance(t: StaticTile, sw: StaticTile = SPAWN): Int {
        val dx = maxOf(sw.x - t.x, 0, t.x - (sw.x + SIZE - 1))
        val dy = maxOf(sw.y - t.y, 0, t.y - (sw.y + SIZE - 1))
        return maxOf(dx, dy)
    }

    /** Orthogonally adjacent to his 3x3, corners excluded: where his melee reaches (spec §4.7 melee auto). */
    fun isMeleeAdjacent(t: StaticTile, sw: StaticTile = SPAWN): Boolean {
        val xs = sw.x until sw.x + SIZE
        val ys = sw.y until sw.y + SIZE
        return (t.x in xs && (t.y == sw.y - 1 || t.y == sw.y + SIZE)) || (t.y in ys && (t.x == sw.x - 1 || t.x == sw.x + SIZE))
    }

    /** A corpse on [t] has reached him (Chebyshev [distance] from his 3x3, spec §4.7 "Arrival"). */
    fun isArrival(t: StaticTile, distance: Int, sw: StaticTile = SPAWN): Boolean = footprintDistance(t, sw) <= distance

    /** A tile a player can stand on: in the box, not under a standing pillar, not under him. */
    fun walkable(t: StaticTile, standing: Collection<Pillar>, sw: StaticTile = SPAWN): Boolean =
        inBox(t) && standing.none { it.contains(t) } && !inFootprint(t, sw)

    /** Chebyshev distance from his centre (projectile flight: Zenyte measures from his middle tile). */
    fun distanceFromCentre(t: StaticTile): Int = maxOf(abs(t.x - CENTRE.x), abs(t.y - CENTRE.y))

    // ----------------------------------------------------------------------------------------- line of sight

    /**
     * The standing pillar that covers [target] from his blast (spec §4.7 t+7): the first standing-pillar tile on the
     * Bresenham line from his centre to [target] (E18 `Lines.firstBlocked`), or null when the line is clear.
     */
    fun coverPillar(target: StaticTile, standing: Collection<Pillar>, from: StaticTile = CENTRE): Pillar? {
        if (standing.isEmpty()) return null
        val ray = Bridge.bresenham(from.x, from.y, target.x, target.y)
        for (i in 1 until ray.size) {
            val x = Bridge.tileX(ray[i])
            val y = Bridge.tileY(ray[i])
            val hit = standing.firstOrNull { it.contains(x, y) }
            if (hit != null) return hit
        }
        return null
    }

    /** Clear line from his centre to [target] past the standing pillars. */
    fun exposed(target: StaticTile, standing: Collection<Pillar>): Boolean = coverPillar(target, standing) == null

    /**
     * Heal-blast shield test (spec §4.7): true when another player stands on the line from his centre to [target],
     * strictly closer to him than [target] ("players behind another player on their line take 50%").
     */
    fun isShielded(target: StaticTile, others: Collection<StaticTile>, from: StaticTile = CENTRE): Boolean {
        if (others.isEmpty()) return false
        val ray = Bridge.bresenham(from.x, from.y, target.x, target.y)
        for (i in 1 until ray.size - 1) {
            val x = Bridge.tileX(ray[i])
            val y = Bridge.tileY(ray[i])
            if (others.any { it.x == x && it.y == y && it.plane == target.plane }) return true
        }
        return false
    }

    // --------------------------------------------------------------------------------------------------- facing

    /** The client face angle from [from] toward [to] (`DirectionUtil.getFaceDirection`: 0 south, 512 west). */
    fun faceAngle(from: StaticTile, to: StaticTile): Int {
        val dx = (to.x - from.x).toDouble()
        val dy = (to.y - from.y).toDouble()
        if (dx == 0.0 && dy == 0.0) return SOUTH
        return (atan2(-dx, -dy) * 325.949).toInt() and 2047
    }

    fun angleOf(facing: String): Int = when (facing) {
        "north" -> NORTH
        "east" -> EAST
        "south" -> SOUTH
        else -> WEST
    }

    /**
     * The turn seq for a face change from [from] to [to] (angles 0-2047, growing clockwise seen from above: 0 south,
     * 512 west): growing = turning to his right (14982), shrinking = to his left (14981); changes under [minAngle]
     * play nothing.
     */
    fun turnSide(from: Int, to: Int, minAngle: Int): TurnSide {
        val delta = (to - from) and 2047
        if (delta == 0) return TurnSide.NONE
        val magnitude = if (delta > 1024) 2048 - delta else delta
        if (magnitude < minAngle) return TurnSide.NONE
        return if (delta <= 1024) TurnSide.RIGHT else TurnSide.LEFT
    }

    /** True when [t] lies in the half-plane he faces (within 90° of [facing]): the `coverRule = "facing"` variant. */
    fun inFacingHalfPlane(facing: Int, t: StaticTile, from: StaticTile = CENTRE): Boolean {
        if (t.x == from.x && t.y == from.y) return true
        val delta = (faceAngle(from, t) - facing) and 2047
        val magnitude = if (delta > 1024) 2048 - delta else delta
        return magnitude <= 512
    }

    /** The face angle that puts the most of [tiles] in his half-plane (16 candidate directions; ties: first). */
    fun mostPlayersAngle(tiles: Collection<StaticTile>, current: Int): Int {
        if (tiles.isEmpty()) return current
        var best = current
        var bestCount = tiles.count { inFacingHalfPlane(current, it) }
        for (i in 0 until 16) {
            val angle = i * 128
            val n = tiles.count { inFacingHalfPlane(angle, it) }
            if (n > bestCount) {
                best = angle
                bestCount = n
            }
        }
        return best
    }

    // ---------------------------------------------------------------------------------------------- movement

    /** One step from [from] toward [to] (each axis −1/0/+1). */
    fun stepToward(from: StaticTile, to: StaticTile): Pair<Int, Int> = (to.x - from.x).sign to (to.y - from.y).sign

    // -------------------------------------------------------------------------------------------- placements

    /**
     * The bomb tiles of one bound player (spec §4.7 t+0, TD rule [DOC]): their own tile, then a random walkable tile
     * of their 3x3 other than it, [count] tiles at most.
     */
    fun bombTiles(player: StaticTile, standing: Collection<Pillar>, count: Int, random: Random): List<StaticTile> {
        if (count <= 0) return emptyList()
        val out = ArrayList<StaticTile>(count)
        out += player
        if (count >= 2) {
            val around = ArrayList<StaticTile>(8)
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val t = player.offset(dx, dy)
                if (walkable(t, standing)) around += t
            }
            if (around.isNotEmpty()) out += around[random.nextInt(around.size)]
        }
        return out
    }

    /**
     * Tiles a P2 flame may land on (spec §4.7 "Flames"): in the box, at least [minDistance] from his 3x3, never on a
     * corpse entry tile, never on a pillar footprint (the debris NPC stays there [HOUSE]), never in [exclude].
     */
    fun flameCandidates(minDistance: Int, exclude: Set<StaticTile>): List<StaticTile> =
        BOX.filter {
            footprintDistance(it) >= minDistance && it !in Pile.ENTRY_TILES && !isPillarTile(it) && it !in exclude
        }
}
