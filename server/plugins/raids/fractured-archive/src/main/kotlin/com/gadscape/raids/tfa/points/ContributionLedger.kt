package com.gadscape.raids.tfa.points

import com.gadscape.raids.tfa.raid.TfaRoomId
import java.util.EnumMap

/**
 * Contribution points of one raid (spec §3.10, WU-16). Keyed by lower-case username so a member keeps their points
 * across reconnects of the same session object.
 *
 * - [record] collects damage per room: every hit on the boss or any add of the room, already capped at the target's
 *   remaining HP by `TfaNpc` (overkill capped; shield damage counts; heals never refund).
 * - [bank] fixes the room's shares at completion: `share = pool × damage / total`, distributed with the largest
 *   remainder so the shares add up to the pool exactly. A room nobody damaged banks nothing.
 * - [applyDeathPenalty] removes `deathPenaltyPct` % of the banked points at the moment of death.
 * - A wipe gives no points (the raid ends; nothing is banked for the room).
 *
 * Pure: unit-tested in `ContributionLedgerTest`.
 */
class ContributionLedger(val config: PointsConfig) {

    private val damage = EnumMap<TfaRoomId, LinkedHashMap<String, Long>>(TfaRoomId::class.java)
    private val shares = EnumMap<TfaRoomId, Map<String, Int>>(TfaRoomId::class.java)
    private val banked = LinkedHashMap<String, Int>()
    private val penalties = LinkedHashMap<String, Int>()

    /** Damage dealt by [username] in [room] (any target). */
    fun record(room: TfaRoomId, username: String, amount: Int) {
        if (amount <= 0 || isBanked(room)) return
        val map = damage.getOrPut(room) { LinkedHashMap() }
        val key = key(username)
        map[key] = (map[key] ?: 0L) + amount
    }

    fun damage(room: TfaRoomId, username: String): Long = damage[room]?.get(key(username)) ?: 0L

    fun totalDamage(room: TfaRoomId): Long = damage[room]?.values?.sum() ?: 0L

    /** Damage per player in [room] (lower-case usernames). */
    fun damageOf(room: TfaRoomId): Map<String, Long> = damage[room]?.toMap() ?: emptyMap()

    fun isBanked(room: TfaRoomId): Boolean = room in shares

    /** Fixes the shares of [room] (once) and adds them to the banked points; returns the shares. */
    fun bank(room: TfaRoomId): Map<String, Int> {
        shares[room]?.let { return it }
        val result = split(config.pool(room), damage[room] ?: emptyMap())
        shares[room] = result
        for ((name, share) in result) banked[name] = (banked[name] ?: 0) + share
        return result
    }

    /** The shares banked for [room], or an empty map. */
    fun sharesOf(room: TfaRoomId): Map<String, Int> = shares[room] ?: emptyMap()

    /** Removes `deathPenaltyPct` % of [username]'s banked points (rounded down); returns the points removed. */
    fun applyDeathPenalty(username: String): Int {
        val key = key(username)
        val current = banked[key] ?: 0
        val removed = current * config.deathPenaltyPct / 100
        if (removed > 0) {
            banked[key] = current - removed
            penalties[key] = (penalties[key] ?: 0) + removed
        }
        return removed
    }

    fun banked(username: String): Int = banked[key(username)] ?: 0

    fun penalties(username: String): Int = penalties[key(username)] ?: 0

    /** Every member's banked points. */
    fun bankedAll(): Map<String, Int> = banked.toMap()

    /** Sum of every member's banked points (the purple roll uses it, §3.15). */
    fun total(): Int = banked.values.sum()

    /** Poor performance (§3.10): final points below `poorPerformancePct` % of `max / raidSize` → cabbage only. */
    fun isPoorPerformance(username: String, raidSize: Int): Boolean =
        isBelow(banked(username), config.poorPerformancePct, config.max, raidSize)

    fun describe(): List<String> {
        val out = ArrayList<String>()
        for ((room, map) in damage) {
            out += "  ${room.configKey}: damage ${map.entries.joinToString { "${it.key} ${it.value}" }}" +
                (shares[room]?.let { s -> " | banked ${s.entries.joinToString { "${it.key} ${it.value}" }}" } ?: "")
        }
        out += "  banked: ${banked.entries.joinToString { "${it.key} ${it.value}" }.ifEmpty { "none" }} (total ${total()})"
        return out
    }

    companion object {

        fun key(username: String): String = username.lowercase()

        /**
         * `pool × damage / total` per player, largest remainder first so the parts sum to [pool]; ties broken by the
         * order the players first dealt damage. Empty when nobody dealt damage.
         */
        @JvmStatic
        fun split(pool: Int, damage: Map<String, Long>): Map<String, Int> {
            val total = damage.values.sum()
            if (pool <= 0 || total <= 0) return emptyMap()
            val entries = damage.entries.filter { it.value > 0 }
            val base = LinkedHashMap<String, Int>()
            val remainders = ArrayList<Triple<String, Long, Int>>()
            var given = 0
            for ((i, e) in entries.withIndex()) {
                val exact = pool.toLong() * e.value
                val part = (exact / total).toInt()
                base[e.key] = part
                given += part
                remainders += Triple(e.key, exact % total, i)
            }
            var left = pool - given
            for ((name, _, _) in remainders.sortedWith(compareByDescending<Triple<String, Long, Int>> { it.second }.thenBy { it.third })) {
                if (left <= 0) break
                base[name] = base.getValue(name) + 1
                left--
            }
            return base
        }

        /** `points < pct % of (basis / raidSize)` without rounding: `points × 100 × size < pct × basis`. */
        @JvmStatic
        fun isBelow(points: Int, pct: Int, basis: Int, raidSize: Int): Boolean =
            points.toLong() * 100L * raidSize.coerceAtLeast(1) < pct.toLong() * basis
    }
}
