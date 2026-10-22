package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

/**
 * Static geometry of the Dagon'hai arena (spec §4.6 "Arena"; region 16215, plane 0), from the cache map
 * (`raids4/dagonhai/arena_map2.txt` flood fill, `arena_grid.txt` lanes):
 * - the floor: x 4055-4074, y 5590-5608, 263 open tiles plus the two walkable 2x2 desks (NW 62562, SW 62558);
 * - the cauldron (npc 16649, size 3): SW (4064,5598), centre (4065,5599) (scorch decal 62576, the desk rays);
 * - eight lanes, one per compass direction from the cauldron centre, from the cauldron edge out to a desk or the
 *   Book of Life and Death ([RAYS]); a monk searching a desk stands on the lane's outer end, so the tiles a player
 *   can block a tome on are the ray tiles not under the monk ([laneTiles]).
 */
object DagonhaiArena {

    val CAULDRON_SW = StaticTile(4064, 5598)
    const val CAULDRON_SIZE = 3
    val CAULDRON_CENTRE = StaticTile(4065, 5599)

    /** The trio's size. */
    const val TRIO_SIZE = 2

    /** Bounding box of the floor. */
    const val MIN_X = 4055
    const val MAX_X = 4074
    const val MIN_Y = 5590
    const val MAX_Y = 5608

    /** Open floor per row (y → x ranges), [CACHE map] flood fill from (4066,5600). */
    private val ROWS: Map<Int, List<IntRange>> = mapOf(
        5608 to listOf(4062..4063, 4068..4069),
        5607 to listOf(4061..4063, 4068..4070),
        5606 to listOf(4060..4070),
        5605 to listOf(4060..4070),
        5604 to listOf(4060..4071),
        5603 to listOf(4055..4074),
        5602 to listOf(4056..4074),
        5601 to listOf(4055..4074),
        5600 to listOf(4055..4073),
        5599 to listOf(4055..4073),
        5598 to listOf(4055..4073),
        5597 to listOf(4055..4074),
        5596 to listOf(4056..4074),
        5595 to listOf(4055..4056, 4059..4074),
        5594 to listOf(4059..4073),
        5593 to listOf(4059..4070),
        5592 to listOf(4060..4069),
        5591 to listOf(4061..4062, 4067..4069),
        5590 to listOf(4062..4062, 4067..4069),
    )

    /** The 2x2 desks marked walkable in the cache (NOTES §5): NW 62562 at (4058,5604), SW 62558 at (4057,5594). */
    private val WALKABLE_DESKS: Set<StaticTile> = buildSet {
        for (dx in 0..1) for (dy in 0..1) {
            add(StaticTile(4058 + dx, 5604 + dy))
            add(StaticTile(4057 + dx, 5594 + dy))
        }
    }

    /** Every open floor tile (263), the cauldron's footprint included (it is an NPC, not a loc). */
    val OPEN_TILES: List<StaticTile> = ROWS.entries.sortedByDescending { it.key }
        .flatMap { (y, ranges) -> ranges.flatMap { r -> r.map { x -> StaticTile(x, y) } } }

    private val OPEN_SET: Set<StaticTile> = OPEN_TILES.toHashSet()

    /** Floor tiles hazards and rifts may use: open floor outside the cauldron. */
    val HAZARD_TILES: List<StaticTile> = OPEN_TILES.filter { !inCauldron(it) }

    /** True when a player may stand on [tile] in the fight (open floor or a walkable desk). */
    fun onFloor(tile: StaticTile): Boolean = tile.plane == 0 && (tile in OPEN_SET || tile in WALKABLE_DESKS)

    /** True for open floor (no desk). */
    fun isOpen(tile: StaticTile): Boolean = tile.plane == 0 && tile in OPEN_SET

    fun inCauldron(tile: StaticTile): Boolean = inFootprint(tile, CAULDRON_SW, CAULDRON_SIZE)

    fun inFootprint(tile: StaticTile, sw: StaticTile, size: Int): Boolean =
        tile.x >= sw.x && tile.x < sw.x + size && tile.y >= sw.y && tile.y < sw.y + size

    /** Chebyshev distance between two tiles. */
    fun chebyshev(a: StaticTile, b: StaticTile): Int = max(abs(a.x - b.x), abs(a.y - b.y))

    /** Chebyshev distance from a [size]x[size] footprint at [sw] to [tile] (0 = on it, 1 = adjacent). */
    fun footprintDistance(sw: StaticTile, size: Int, tile: StaticTile): Int {
        val dx = when {
            tile.x < sw.x -> sw.x - tile.x
            tile.x >= sw.x + size -> tile.x - (sw.x + size - 1)
            else -> 0
        }
        val dy = when {
            tile.y < sw.y -> sw.y - tile.y
            tile.y >= sw.y + size -> tile.y - (sw.y + size - 1)
            else -> 0
        }
        return max(dx, dy)
    }

    /** Distance from the cauldron's footprint to [tile]. */
    fun cauldronDistance(tile: StaticTile): Int = footprintDistance(CAULDRON_SW, CAULDRON_SIZE, tile)

    // ------------------------------------------------------------------------------------------------ lanes

    /**
     * The open tiles of each 8-way ray from the cauldron centre, from the cauldron's edge outwards, up to the desk (or
     * the walls in front of the Book of Life and Death for W) [CACHE map, `raids4/dagonhai/arena_grid.txt`].
     */
    val RAYS: Map<Compass, List<StaticTile>> = linkedMapOf(
        Compass.N to (5601..5606).map { StaticTile(4065, it) },                              // desk 62563 (4064,5607)
        Compass.NE to (0..3).map { StaticTile(4067 + it, 5601 + it) },                       // desk 62564 (4072,5604)
        Compass.E to (4067..4073).map { StaticTile(it, 5599) },                              // desk 62561 (4074,5598)
        Compass.SE to (0..3).map { StaticTile(4067 + it, 5597 - it) },                       // desk 62560 (4071,5591)
        Compass.S to (5597 downTo 5592).map { StaticTile(4065, it) },                        // desk 62559 (4063,5590)
        Compass.SW to (0..4).map { StaticTile(4063 - it, 5597 - it) },                       // desk 62558 (4057,5594)
        Compass.W to (4063 downTo 4055).map { StaticTile(it, 5599) },                        // book 62552 (4049,5597)
        Compass.NW to (0..3).map { StaticTile(4063 - it, 5601 + it) },                       // desk 62562 (4058,5604)
    )

    /** The tile a monk searching the desk of [direction] faces (the desk's or the book's centre) [CACHE locs]. */
    val DESK_FACE: Map<Compass, StaticTile> = linkedMapOf(
        Compass.N to StaticTile(4065, 5607),
        Compass.NE to StaticTile(4073, 5605),
        Compass.E to StaticTile(4074, 5599),
        Compass.SE to StaticTile(4072, 5592),
        Compass.S to StaticTile(4065, 5590),
        Compass.SW to StaticTile(4057, 5594),
        Compass.W to StaticTile(4051, 5599),
        Compass.NW to StaticTile(4058, 5605),
    )

    /**
     * The lane a tome thrown from the desk of [direction] crosses, in flight order (from the monk's end to the
     * cauldron): the ray tiles not under the monk's 2x2 at [stand]. These tiles get the floor gusts, and the first
     * player found on them (in this order) intercepts the tome.
     */
    fun laneTiles(direction: Compass, stand: StaticTile): List<StaticTile> =
        RAYS.getValue(direction).filter { !inFootprint(it, stand, TRIO_SIZE) }.reversed()

    // ----------------------------------------------------------------------------------------- tile picking

    /**
     * [count] distinct random tiles from [candidates], each at least [minApart] (Chebyshev) from the others. Fewer if the
     * candidates run out.
     */
    fun pickTiles(candidates: List<StaticTile>, count: Int, random: Random, minApart: Int = 0): List<StaticTile> {
        if (count <= 0 || candidates.isEmpty()) return emptyList()
        val pool = candidates.toMutableList()
        val out = ArrayList<StaticTile>(count)
        while (out.size < count && pool.isNotEmpty()) {
            val pick = pool.removeAt(random.nextInt(pool.size))
            if (minApart > 0 && out.any { chebyshev(it, pick) < minApart }) continue
            out += pick
        }
        return out
    }

    /** Hazard tiles within [radius] of [centre] (Chebyshev), the centre included. */
    fun hazardTilesAround(centre: StaticTile, radius: Int): List<StaticTile> =
        HAZARD_TILES.filter { chebyshev(it, centre) <= radius }

    /** Rift tiles: hazard tiles at least [minPlayerDistance] from every player tile in [players]. */
    fun riftCandidates(players: Collection<StaticTile>, minPlayerDistance: Int): List<StaticTile> =
        HAZARD_TILES.filter { t -> players.all { chebyshev(it, t) >= minPlayerDistance } }
}
