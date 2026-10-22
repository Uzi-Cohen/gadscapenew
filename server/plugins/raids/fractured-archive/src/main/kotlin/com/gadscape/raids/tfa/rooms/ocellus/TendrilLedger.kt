package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile

/**
 * Bookkeeping of one P1 tendril set (spec §4.8 Phase 1; wiki "These tendrils will channel energy into him, which
 * can be mitigated by standing on them. The player will take some damage but will be healed after stepping off
 * the last tendril"):
 * - a tendril is **blocked** when at least one player stands on one of its tiles when it resolves; each blocker
 *   takes [blockDmg];
 * - an unblocked tendril adds [meterPerUnblocked] to the energy meter (capped at [meterMax]);
 * - when the set ends ([settle]), every blocker is healed for the tendril damage they took in the set.
 *
 * Generic in the player type so it is testable without a world. The meter lives across sets (it is only reset
 * by the centre release), so it is passed in and returned.
 */
class TendrilLedger<P : Any>(
    private val blockDmg: Int,
    private val meterPerUnblocked: Int,
    private val meterMax: Int,
) {
    sealed interface Outcome {
        data class Blocked(val damageEach: Int, val blockers: Int) : Outcome
        data class Unblocked(val meterAfter: Int, val gained: Int) : Outcome
    }

    private val taken = LinkedHashMap<P, Int>()

    var resolved: Int = 0
        private set

    var blocked: Int = 0
        private set

    /** Resolves one tendril with the [blockers] standing on it; [meter] is the meter before. */
    fun resolve(blockers: Collection<P>, meter: Int): Outcome {
        resolved++
        if (blockers.isEmpty()) {
            val after = (meter + meterPerUnblocked).coerceAtMost(meterMax)
            return Outcome.Unblocked(after, after - meter)
        }
        blocked++
        for (p in blockers) taken.merge(p, blockDmg, Int::plus)
        return Outcome.Blocked(blockDmg, blockers.size)
    }

    /** Forgets [p] (died or left): they are not healed. */
    fun forget(p: P) {
        taken.remove(p)
    }

    /** Ends the set: heal amount per blocker, then clears. */
    fun settle(): Map<P, Int> {
        val out = LinkedHashMap(taken)
        taken.clear()
        return out
    }
}

/**
 * Which directions a tendril set uses (spec §4.8 P1: "7 in total, each along one of 8 directions ... one direction
 * skipped at random"). Directions whose line has no arena tile (at an anchor next to the octagon's diagonal wall)
 * are never used; if all eight have tiles, one is skipped at random. The set runs clockwise starting after the
 * skipped direction, so players can read the pattern [HOUSE].
 */
object TendrilPlan {

    /**
     * @param lines tiles of each direction's line ([ArenaGeometry.tendrilLine]).
     * @param count tendrils wanted (`p1.tendrilsPerAnchor`).
     * @param pick random index in `0 until n`.
     */
    fun anchorOrder(lines: Map<Compass, List<StaticTile>>, count: Int, pick: (Int) -> Int): List<Compass> {
        val clockwise = ArenaGeometry.CLOCKWISE
        val usable = clockwise.filter { !lines[it].isNullOrEmpty() }
        if (usable.isEmpty()) return emptyList()
        val skipped: Compass = if (usable.size == clockwise.size) clockwise[pick(clockwise.size)]
        else clockwise.first { lines[it].isNullOrEmpty() }
        val start = clockwise.indexOf(skipped)
        val ordered = (1..clockwise.size).map { clockwise[(start + it) % clockwise.size] }.filter { it in usable }
        return ordered.take(count)
    }

    /** The centre sequence: 4 rounds of 2 tendrils clockwise from north (N + NE, E + SE, S + SW, W + NW). */
    fun centreRounds(): List<Pair<Compass, Compass>> =
        ArenaGeometry.CLOCKWISE.chunked(2).map { it[0] to it[1] }
}
