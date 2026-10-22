package com.gadscape.raids.tfa.rooms.ezekiel

import com.zenyte.game.util.Direction
import java.util.Random

/** A sword's flight axis: horizontal swords fly along x (E/W), vertical swords along y (N/S). */
enum class SwordAxis { HORIZONTAL, VERTICAL }

/**
 * The 8 named sword-grid layouts of dbtable 136 `tfa_ezekiel_sword_grid` (rows 17028-17035, coords stripped), rebuilt
 * from their names (spec §4.2 Animated Swords [COMM rules, HOUSE numbers]): lanes are 3 wide (a size-3 sword) and
 * start at a floor edge. `center` = the middle lane, `flush` = both edge lanes, `offset` = lanes starting at +1 and +4
 * (y 5532/5535 or x 4060/4063), `full` = all three lanes.
 */
enum class SwordGrid(val axis: SwordAxis, private val laneOffsets: IntArray, val full: Boolean = false) {
    HORIZONTAL_CENTER(SwordAxis.HORIZONTAL, intArrayOf(3)),
    HORIZONTAL_FLUSH(SwordAxis.HORIZONTAL, intArrayOf(0, 6)),
    HORIZONTAL_OFFSET(SwordAxis.HORIZONTAL, intArrayOf(1, 4)),
    HORIZONTAL_FULL(SwordAxis.HORIZONTAL, intArrayOf(0, 3, 6), full = true),
    VERTICAL_CENTER(SwordAxis.VERTICAL, intArrayOf(3)),
    VERTICAL_FLUSH(SwordAxis.VERTICAL, intArrayOf(0, 6)),
    VERTICAL_OFFSET(SwordAxis.VERTICAL, intArrayOf(1, 4)),
    VERTICAL_FULL(SwordAxis.VERTICAL, intArrayOf(0, 3, 6), full = true);

    /** Lane origins: y of horizontal lanes, x of vertical lanes (static). */
    val lanes: List<Int>
        get() = laneOffsets.map { (if (axis == SwordAxis.HORIZONTAL) EzekielArena.FLOOR_MIN_Y else EzekielArena.FLOOR_MIN_X) + it }

    /** The config name, e.g. `h_center`. */
    val key: String get() = (if (axis == SwordAxis.HORIZONTAL) "h_" else "v_") + name.substringAfter('_').lowercase()

    companion object {
        @JvmStatic
        fun byKey(key: String): SwordGrid? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/**
 * One cast: one or two grids flown together (spec §4.2: "H_full, V_full, H_center + V_flush, V_center + H_flush,
 * H_offset + V_center, V_offset + H_center, H_flush + V_flush", 3 or 4 swords). Written `h_center+v_flush` in config.
 */
data class SwordCast(val grids: List<SwordGrid>) {
    val key: String get() = grids.joinToString("+") { it.key }
    val swordCount: Int get() = grids.sumOf { it.lanes.size }

    companion object {
        @JvmStatic
        fun parse(text: String): SwordCast? {
            val parts = text.split('+').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val grids = parts.map { SwordGrid.byKey(it) ?: return null }
            if (grids.distinct().size != grids.size) return null
            return SwordCast(grids)
        }

        /** The 7 casts of the spec. */
        @JvmField
        val DEFAULT_KEYS = listOf(
            "h_full", "v_full", "h_center+v_flush", "v_center+h_flush",
            "h_offset+v_center", "v_offset+h_center", "h_flush+v_flush",
        )
    }
}

/**
 * One Animated Sword of a cast: spawned with SW [start] facing its flight [direction] (npc 16653 has turnspeed 0, so
 * the spawn facing is the flight direction), flying to SW [end] along its lane, launched [chargeTicks] after it spawns.
 */
data class SwordSpec(
    val index: Int,
    val axis: SwordAxis,
    /** y of a horizontal lane, x of a vertical lane (the sword's SW line). */
    val lane: Int,
    val start: Tile,
    val end: Tile,
    val direction: Direction,
    val chargeTicks: Int,
) {
    /** +1 / -1 along the axis. */
    val step: Int get() = if (axis == SwordAxis.HORIZONTAL) Integer.signum(end.x - start.x) else Integer.signum(end.y - start.y)
}

/** Builds the swords of a cast (spec §4.2 "Spawn", "Launch bar"). */
object SwordPatterns {

    /** Uniform pick from the configured casts. */
    @JvmStatic
    fun pick(casts: List<SwordCast>, random: Random): SwordCast = casts[random.nextInt(casts.size)]

    /**
     * The swords of [cast]. Each sword takes a random end of its lane ("random end per sword"), avoiding a spawn cell
     * another sword of the cast already holds when the other end is free. Swords of a `full` grid charge
     * [fullStagger] ticks in a random order (so a gap always opens); every other sword charges [chargeTicks].
     */
    @JvmStatic
    fun plan(cast: SwordCast, random: Random, chargeTicks: Int, fullStagger: List<Int>): List<SwordSpec> {
        val out = ArrayList<SwordSpec>()
        val taken = HashSet<Rect>()
        val lo = 0
        val hi = EzekielArena.FLOOR.width - EzekielArena.SIZE // 6: far-end offset of a size-3 sword on a 9-tile floor
        for (grid in cast.grids) {
            val stagger = if (grid.full && fullStagger.size >= grid.lanes.size) fullStagger.shuffled(random) else null
            for ((i, lane) in grid.lanes.withIndex()) {
                val firstEnd = random.nextBoolean()
                fun startAt(atLow: Boolean): Tile = if (grid.axis == SwordAxis.HORIZONTAL) {
                    Tile(EzekielArena.FLOOR_MIN_X + if (atLow) lo else hi, lane)
                } else {
                    Tile(lane, EzekielArena.FLOOR_MIN_Y + if (atLow) lo else hi)
                }
                var atLow = firstEnd
                if (Rect.footprint(startAt(atLow).x, startAt(atLow).y, EzekielArena.SIZE) in taken &&
                    Rect.footprint(startAt(!atLow).x, startAt(!atLow).y, EzekielArena.SIZE) !in taken
                ) atLow = !atLow
                val start = startAt(atLow)
                val end = startAt(!atLow)
                taken += Rect.footprint(start.x, start.y, EzekielArena.SIZE)
                val direction = if (grid.axis == SwordAxis.HORIZONTAL) {
                    if (atLow) Direction.EAST else Direction.WEST
                } else {
                    if (atLow) Direction.NORTH else Direction.SOUTH
                }
                out += SwordSpec(out.size, grid.axis, lane, start, end, direction, stagger?.get(i) ?: chargeTicks)
            }
        }
        return out
    }
}
