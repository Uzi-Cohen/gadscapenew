package com.gadscape.raids.tfa.rooms.moia

import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random

/**
 * Where the undead binders stand (spec §4.4 "Binders" [COMM rules, HOUSE numbers]). Pure positions in static
 * coordinates, so the rules are unit-tested without a world; [MoiaRoom] moves the NPCs to match.
 *
 * Phase 1 ("close but never adjacent"):
 * - Binders stand on one of two lattices with a step of 2 tiles inside the 9x9 graveyard:
 *   lattice A = x, y ∈ {4059, 4061, 4063, 4065, 4067} (25 points), lattice B = x, y ∈ {4060, 4062, 4064, 4066} (16).
 *   All binders are always on the same lattice and on distinct points, and two distinct points of one lattice are
 *   at Chebyshev distance ≥ 2, so no two binders are ever adjacent.
 * - Every shuffle the whole formation steps one diagonal (NE, SE, SW, NW in turn), which moves it from one
 *   lattice to the other. A binder that would leave the floor reflects on that axis. If two binders would end on the
 *   same point, the later one takes another diagonal; if no full assignment exists, nobody moves this time (the
 *   invariant is never broken).
 * - New binders take a free point of the current lattice at least `minPlayerDistance` from every player.
 *
 * Enrage: the formation switches to the fixed diamond (8 tiles around the centre); new binders refill its free
 * tiles, and there is no shuffle.
 */
class BinderFormation<T : Any> {

    enum class Lattice { A, B;
        val other: Lattice get() = if (this == A) B else A
    }

    private val slots = LinkedHashMap<T, StaticTile>()

    /** The lattice the phase 1 formation is on. */
    var lattice: Lattice = Lattice.A
        private set

    /** The enrage diamond, or null in phase 1. */
    var diamond: List<StaticTile>? = null
        private set

    val isDiamond: Boolean get() = diamond != null

    val size: Int get() = slots.size

    fun members(): List<T> = slots.keys.toList()

    fun tiles(): List<StaticTile> = slots.values.toList()

    fun tileOf(binder: T): StaticTile? = slots[binder]

    operator fun contains(binder: T): Boolean = slots.containsKey(binder)

    fun add(binder: T, tile: StaticTile) {
        slots[binder] = tile
    }

    fun remove(binder: T): StaticTile? = slots.remove(binder)

    fun clear() = slots.clear()

    /** Phase 1 formation on [lattice] (the default after construction). */
    fun usePhase1(lattice: Lattice = Lattice.A) {
        diamond = null
        this.lattice = lattice
    }

    /** Enrage formation on the [tiles] diamond. */
    fun useDiamond(tiles: List<StaticTile>) {
        diamond = tiles.toList()
    }

    /**
     * Points a new binder may take: on the current lattice (or a free diamond tile), not held by a binder, not in
     * [reserved] (e.g. corpses), and, in phase 1, at least [minPlayerDistance] from every tile in [players].
     */
    fun freePoints(players: Collection<StaticTile>, minPlayerDistance: Int, reserved: Collection<StaticTile> = emptySet()): List<StaticTile> {
        val taken = slots.values.toHashSet()
        val d = diamond
        if (d != null) return d.filter { it !in taken && it !in reserved }
        return latticePoints(lattice).filter { p ->
            p !in taken && p !in reserved && players.all { MoiaGeometry.chebyshev(it, p) >= minPlayerDistance }
        }
    }

    /** Up to [count] distinct random points from [freePoints]. */
    fun pickPoints(
        count: Int, random: Random, players: Collection<StaticTile>, minPlayerDistance: Int,
        reserved: Collection<StaticTile> = emptySet(),
    ): List<StaticTile> {
        if (count <= 0) return emptyList()
        val free = freePoints(players, minPlayerDistance, reserved).toMutableList()
        val out = ArrayList<StaticTile>(minOf(count, free.size))
        while (out.size < count && free.isNotEmpty()) out += free.removeAt(random.nextInt(free.size))
        return out
    }

    /**
     * Plans one shuffle step towards [direction] (a diagonal) for every binder, all or nothing. Returns the target
     * of each binder, or null when the formation is the diamond or no collision-free assignment exists (then nobody
     * moves). Does not change the formation; see [applyShuffle].
     */
    fun planShuffle(direction: Compass, reserved: Collection<StaticTile> = emptySet()): Map<T, StaticTile>? {
        require(direction.dx != 0 && direction.dy != 0) { "the shuffle steps diagonally, got $direction" }
        if (diamond != null) return null
        val binders = slots.keys.toList()
        val candidates = binders.map { b -> candidates(slots.getValue(b), direction.dx, direction.dy, reserved) }
        val chosen = arrayOfNulls<StaticTile>(binders.size)
        val used = HashSet<StaticTile>()
        fun assign(i: Int): Boolean {
            if (i == binders.size) return true
            for (c in candidates[i]) {
                if (c in used) continue
                used += c
                chosen[i] = c
                if (assign(i + 1)) return true
                used -= c
            }
            return false
        }
        if (!assign(0)) return null
        val plan = LinkedHashMap<T, StaticTile>()
        binders.forEachIndexed { i, b -> plan[b] = chosen[i]!! }
        return plan
    }

    /** Applies a plan from [planShuffle]: every binder takes its target and the formation is on the other lattice. */
    fun applyShuffle(plan: Map<T, StaticTile>) {
        check(diamond == null) { "no shuffle in the enrage diamond" }
        for ((b, t) in plan) if (slots.containsKey(b)) slots[b] = t
        lattice = lattice.other
    }

    /** [planShuffle] then [applyShuffle]; null (and nothing changes) when the step is impossible. */
    fun shuffle(direction: Compass, reserved: Collection<StaticTile> = emptySet()): Map<T, StaticTile>? {
        val plan = planShuffle(direction, reserved) ?: return null
        applyShuffle(plan)
        return plan
    }

    /** The diagonal targets of a binder at [p] for a step (dx, dy): the reflected preferred one first. */
    private fun candidates(p: StaticTile, dx: Int, dy: Int, reserved: Collection<StaticTile>): List<StaticTile> {
        val rx = if (MoiaGeometry.onFloor(p.x + dx, p.y)) dx else -dx
        val ry = if (MoiaGeometry.onFloor(p.x, p.y + dy)) dy else -dy
        return listOf(rx to ry, rx to -ry, -rx to ry, -rx to -ry)
            .map { (sx, sy) -> StaticTile(p.x + sx, p.y + sy, p.plane) }
            .filter { MoiaGeometry.onFloor(it) && it !in reserved }
    }

    companion object {
        /** The shuffle direction order (spec: NE, SE, SW, NW in turn). */
        @JvmField
        val SHUFFLE_ORDER: List<Compass> = listOf(Compass.NE, Compass.SE, Compass.SW, Compass.NW)

        private val LATTICE_A: List<StaticTile> = grid(MoiaGeometry.FLOOR_MIN_X, MoiaGeometry.FLOOR_MIN_Y)
        private val LATTICE_B: List<StaticTile> = grid(MoiaGeometry.FLOOR_MIN_X + 1, MoiaGeometry.FLOOR_MIN_Y + 1)

        private fun grid(x0: Int, y0: Int): List<StaticTile> {
            val out = ArrayList<StaticTile>()
            var y = y0
            while (y <= MoiaGeometry.FLOOR_MAX_Y) {
                var x = x0
                while (x <= MoiaGeometry.FLOOR_MAX_X) {
                    out += StaticTile(x, y, MoiaGeometry.PLANE)
                    x += 2
                }
                y += 2
            }
            return out
        }

        @JvmStatic
        fun latticePoints(lattice: Lattice): List<StaticTile> = if (lattice == Lattice.A) LATTICE_A else LATTICE_B

        /** True when no two of [tiles] are within Chebyshev distance 1 (and none repeats). */
        @JvmStatic
        fun noTwoAdjacent(tiles: Collection<StaticTile>): Boolean {
            val list = tiles.toList()
            for (i in list.indices) for (j in i + 1 until list.size) {
                if (MoiaGeometry.chebyshev(list[i], list[j]) <= 1) return false
            }
            return true
        }
    }
}
