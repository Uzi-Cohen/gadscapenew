package com.gadscape.raids.tfa.rooms.moia

/**
 * Phase 1 attack grid (spec §4.4 PHASE1 / attack table). Pure: [MoiaRoom] asks [poll] every tick and performs the
 * returned attack.
 *
 * - An auto slot comes every `attackSpeed` ticks (5, cache param 14), anchored at the start of phase 1.
 * - Every `specialEveryNthAuto`-th slot (4th, 8th ...) is a ranged dagger followed by the teleport + blue special
 *   ("She shoots a Ranged attack right before the blue special", COMM). The special's own steps are timed by
 *   `moia.json special` from that slot's tick.
 * - Once `infect.interval` ticks have passed since phase 1 began (or since the last infect), the next ordinary slot
 *   is the infect cast instead of a dagger, provided someone can be infected; a special slot keeps its dagger and the
 *   infect waits for the next slot.
 * - Every slot counts towards the special cadence, so the special comes every `attackSpeed × N` ticks (20).
 */
class MoiaAttackPlan(
    private val attackSpeed: Int,
    private val specialEveryNthAuto: Int,
    private val infectInterval: Int,
) {

    enum class Attack { DAGGER, DAGGER_THEN_SPECIAL, INFECT }

    /** Auto slots used since [start]. */
    var autos: Int = 0
        private set

    /** Tick of the next auto slot. */
    var nextAutoTick: Int = Int.MAX_VALUE
        private set

    /** First tick at which an infect may replace an auto. */
    var nextInfectTick: Int = Int.MAX_VALUE
        private set

    /** Starts the grid: the first auto is on [tick]. */
    fun start(tick: Int) {
        autos = 0
        nextAutoTick = tick
        nextInfectTick = tick + infectInterval
    }

    /** Stops the grid (enrage, death). */
    fun stop() {
        nextAutoTick = Int.MAX_VALUE
        nextInfectTick = Int.MAX_VALUE
    }

    val running: Boolean get() = nextAutoTick != Int.MAX_VALUE

    /**
     * The attack of [tick], or null between slots. [canInfect] says whether an uninfected target exists right now.
     */
    fun poll(tick: Int, canInfect: Boolean): Attack? {
        if (tick < nextAutoTick) return null
        nextAutoTick = tick + attackSpeed
        autos++
        if (specialEveryNthAuto > 0 && autos % specialEveryNthAuto == 0) return Attack.DAGGER_THEN_SPECIAL
        if (tick >= nextInfectTick && canInfect) {
            nextInfectTick = tick + infectInterval
            return Attack.INFECT
        }
        return Attack.DAGGER
    }

    /** Debug: make the next slot (on [tick] or later) a special. */
    fun forceSpecialNext(tick: Int) {
        autos = (specialEveryNthAuto - 1).coerceAtLeast(0) + (autos / specialEveryNthAuto.coerceAtLeast(1)) * specialEveryNthAuto
        nextAutoTick = minOf(nextAutoTick, tick)
    }

    /** Debug: make an infect due now. */
    fun forceInfectDue(tick: Int) {
        nextInfectTick = tick
        nextAutoTick = minOf(nextAutoTick, tick)
        // keep the next slot an ordinary one
        if (specialEveryNthAuto > 0 && (autos + 1) % specialEveryNthAuto == 0) autos++
    }
}
