package com.gadscape.raids.tfa.rooms.moia

/**
 * The enrage charge loop (spec §4.4 CHARGING / RESOLVE, "Enrage diamond" [HOUSE]). Pure; [MoiaRoom] drives it.
 *
 * - A charge lasts `chargeTicks` (25). While it runs Moia chants and does not attack.
 * - When it completes ([resolve]):
 *   - binders alive: each survivor releases (spot 4222), every player in the graveyard takes typeless
 *     `min(cap, perSurvivor × survivors)` (spot 4225, prayer ignored), then the diamond is refilled to `set`;
 *   - none alive: no explosion, a new diamond set spawns.
 * - Either way the next charge starts; this repeats until Moia dies.
 */
class MoiaEnrage(private val config: MoiaConfig.Enrage) {

    /** Result of one completed charge. */
    data class Outcome(val survivors: Int, val damage: Int, val refill: Int) {
        val exploded: Boolean get() = survivors > 0
    }

    /** Charges completed so far. */
    var completed: Int = 0
        private set

    /** Tick the current charge completes on, or -1 when not charging. */
    var chargeEnd: Int = -1
        private set

    var chargeStart: Int = -1
        private set

    val charging: Boolean get() = chargeEnd >= 0

    /** Starts a charge on [tick]; returns its completion tick. */
    fun begin(tick: Int): Int {
        chargeStart = tick
        chargeEnd = tick + config.chargeTicks
        return chargeEnd
    }

    fun isDue(tick: Int): Boolean = charging && tick >= chargeEnd

    /** Ticks left in the current charge (0 when none). */
    fun remaining(tick: Int): Int = if (!charging) 0 else maxOf(0, chargeEnd - tick)

    /** Ends the current charge with [survivors] binders alive. */
    fun resolve(survivors: Int): Outcome {
        chargeEnd = -1
        completed++
        val alive = survivors.coerceAtLeast(0)
        return Outcome(alive, config.explosion(alive), (config.set - alive).coerceAtLeast(0))
    }

    /** Debug: complete the current charge on [tick]. */
    fun forceDue(tick: Int) {
        if (charging) chargeEnd = tick
    }

    fun stop() {
        chargeEnd = -1
    }
}
