package com.gadscape.raids.tfa.hud

import kotlin.math.roundToInt

/**
 * HUD orb values of interface 974 (varbits 16387-16394, 5 bits each) [CACHE script 10039, confirmed against ToB's
 * `RaidingParty.getStatus`, spec §3.7 / §5.4]:
 * - 0: empty slot (hidden);
 * - 1..27: in the active room and alive, `1 + round(26 × hp / maxHp)` with both HP values capped at `hud.orbHpCap` (99);
 * - 30: dead (or dead and spectating);
 * - 31: not in the active room (white, full).
 *
 * Pure: unit-tested in `OrbCodecTest`.
 */
object OrbCodec {

    const val EMPTY = 0
    const val DEAD = 30
    const val ELSEWHERE = 31

    /** The fill value for a living member in the active room. */
    @JvmStatic
    fun health(hp: Int, maxHp: Int, cap: Int = 99): Int {
        val max = maxHp.coerceAtMost(cap).coerceAtLeast(1)
        val cur = hp.coerceAtMost(cap).coerceIn(0, max)
        return 1 + (26.0 * cur / max).roundToInt()
    }

    /** The orb of one slot. */
    @JvmStatic
    fun orb(occupied: Boolean, dead: Boolean, inActiveRoom: Boolean, hp: Int, maxHp: Int, cap: Int = 99): Int = when {
        !occupied -> EMPTY
        dead -> DEAD
        !inActiveRoom -> ELSEWHERE
        else -> health(hp, maxHp, cap)
    }

    /**
     * Timer arguments of RUNCLIENTSCRIPT 10036 (`elapsedTicks or -1`, `frozen`) [CACHE script 10036]:
     * -1 shows "-"; frozen = 1 shows a static value; frozen = 0 counts up client-side.
     */
    data class TimerArgs(val elapsed: Int, val frozen: Int)

    val TIMER_NOT_STARTED = TimerArgs(-1, 1)

    @JvmStatic
    fun running(elapsedTicks: Int) = TimerArgs(elapsedTicks.coerceAtLeast(0), 0)

    @JvmStatic
    fun frozen(ticks: Int) = TimerArgs(ticks.coerceAtLeast(0), 1)
}
