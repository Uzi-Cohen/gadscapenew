package com.gadscape.game.world.entity.player.status

import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.variables.TickVariable

/**
 * Bleed with the Vardorvis rules (OSRS wiki "Bleed"; Ezekiel's cleave, spec §4.2): 3 damage every 3 ticks for 15
 * ticks, or every tick while the player keeps moving (up to 42), hitsplat BLEED 67. It reuses
 * [TickVariable.VARDORVIS_BLEED]; Vardorvis's own room marks movement itself (`player.bleedNextTick`), every other
 * source applies it through [apply], which turns on movement tracking in the tick variable.
 * Re-application restarts the 15 ticks. Cleared on death (tick variables reset) and by [clear].
 */
object PlayerBleed {

    /** The Vardorvis bleed length in ticks (`VardorvisInstance` schedules the same 15). */
    const val DURATION = 15

    /** Temporary attribute: this bleed hurts on every tick the player moved. */
    internal const val MOVE_KEY = "gadscape_bleed_on_move"
    private const val LAST_TILE_KEY = "gadscape_bleed_last_tile"

    @JvmStatic
    fun apply(player: Player) {
        if (player.isDead || player.isFinished) return
        player.temporaryAttributes[MOVE_KEY] = true
        player.temporaryAttributes[LAST_TILE_KEY] = player.location.positionHash
        player.variables.schedule(DURATION, TickVariable.VARDORVIS_BLEED)
    }

    @JvmStatic
    fun isBleeding(player: Player): Boolean = player.variables.getTime(TickVariable.VARDORVIS_BLEED) > 0

    @JvmStatic
    fun clear(player: Player) {
        player.variables.cancel(TickVariable.VARDORVIS_BLEED)
        player.temporaryAttributes.remove(MOVE_KEY)
        player.temporaryAttributes.remove(LAST_TILE_KEY)
    }

    /**
     * Called by the tick variable every tick: true when the player moved since the previous tick and the bleed came
     * from [apply]. On the last tick ([ticksLeft] 0) the tracking is removed.
     */
    @JvmStatic
    fun movedSinceLastPulse(player: Player, ticksLeft: Int): Boolean {
        if (player.temporaryAttributes[MOVE_KEY] != true) return false
        val hash = player.location.positionHash
        val last = player.temporaryAttributes.put(LAST_TILE_KEY, hash) as? Int
        if (ticksLeft <= 0) {
            player.temporaryAttributes.remove(MOVE_KEY)
            player.temporaryAttributes.remove(LAST_TILE_KEY)
        }
        return last != null && last != hash
    }
}
