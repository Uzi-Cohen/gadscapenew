package com.gadscape.raids.tfa.points

import com.gadscape.raids.tfa.config.ConfigReader
import com.gadscape.raids.tfa.config.Evidence.HOUSE
import com.gadscape.raids.tfa.config.TfaConfigSpec
import com.gadscape.raids.tfa.raid.TfaRoomId

/**
 * `points.json` (WU-16, spec §3.10 / U4, U5): contribution pools and thresholds. Every number is [HOUSE] except the
 * rules tagged [DOC] (finite pool per room, paired rooms equal).
 *
 * @property pools points each room awards in total, independent of party size; paired rooms must be equal [DOC].
 * @property deathPenaltyPct each death removes this % of the player's banked points, at the moment of death.
 * @property poorPerformancePct final points below this % of `max / raidSize` → cabbage only (§3.15).
 * @property failureShopPct points after room 3 below this % of `tfa.json supplies.fairSharePoints / raidSize` →
 *   the Quartermaster opens the failure shop (dbrow 17151).
 */
data class PointsConfig(
    val pools: Map<TfaRoomId, Int>,
    val deathPenaltyPct: Int,
    val poorPerformancePct: Int,
    val failureShopPct: Int,
) {
    fun pool(room: TfaRoomId): Int = pools[room] ?: 0

    /** The most a raid can award: the Guardian, the larger room of each pair, Ocellus (35,000 by default). */
    val max: Int
        get() = pool(TfaRoomId.GUARDIAN) + (1..3).sumOf { s -> TfaRoomId.entries.filter { it.section == s }.maxOf { pool(it) } } +
            pool(TfaRoomId.OCELLUS)

    /** Points the first three rooms can award (Guardian + sections 1 and 2): 16,000 by default. */
    val firstThreeRooms: Int
        get() = pool(TfaRoomId.GUARDIAN) + (1..2).sumOf { s -> TfaRoomId.entries.filter { it.section == s }.maxOf { pool(it) } }

    companion object SPEC : TfaConfigSpec<PointsConfig>("points.json") {

        /** Spec §3.10 pools [HOUSE]: Guardian 4000; Ezekiel or Bork 6000; Moia or Dreadnoth 6000; Dagon'hai or Xarth 7000; Ocellus 12000. */
        val DEFAULT_POOLS: Map<TfaRoomId, Int> = linkedMapOf(
            TfaRoomId.GUARDIAN to 4000,
            TfaRoomId.EZEKIEL to 6000,
            TfaRoomId.BORK to 6000,
            TfaRoomId.MOIA to 6000,
            TfaRoomId.DREADNOTH to 6000,
            TfaRoomId.DAGONHAI to 7000,
            TfaRoomId.XARTH to 7000,
            TfaRoomId.OCELLUS to 12000,
        )

        @JvmField
        val DEFAULT = PointsConfig(DEFAULT_POOLS, 20, 10, 25)

        override fun read(r: ConfigReader): PointsConfig {
            val pools = r.obj("pools") {
                DEFAULT_POOLS.mapValues { (room, def) -> int(room.configKey, def, HOUSE, min = 0, max = 1_000_000) }
            }
            for (section in 1..3) {
                val pair = TfaRoomId.entries.filter { it.section == section }
                r.check("pools", pair.map { pools[it] }.distinct().size == 1) {
                    "paired rooms must award the same total [DOC]: ${pair.joinToString { "${it.configKey} ${pools[it]}" }}"
                }
            }
            return PointsConfig(
                pools = pools,
                deathPenaltyPct = r.int("deathPenaltyPct", 20, HOUSE, min = 0, max = 100),
                poorPerformancePct = r.int("poorPerformancePct", 10, HOUSE, min = 0, max = 100),
                failureShopPct = r.int("failureShopPct", 25, HOUSE, min = 0, max = 100),
            )
        }
    }
}
