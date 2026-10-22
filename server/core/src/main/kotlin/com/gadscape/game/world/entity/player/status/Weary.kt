package com.gadscape.game.world.entity.player.status

import com.zenyte.game.world.WorldThread
import com.zenyte.game.world.entity.player.Player

/**
 * Weary (Archive Guardian mushrooms; spec §4.1 [HOUSE], a copy of the Nightmare's drowsy spores):
 * - +1 tick on the player's attack delay ([adjustAttackSpeed], applied in `PlayerCombat.adjustAttackSpeed`);
 * - running is switched off and refused (`Player.setRun`);
 * - lasts [DEFAULT_TICKS] ticks unless the caller passes its config value; re-application refreshes the timer;
 *   not contagious; cleared on death ([PlayerStatuses]).
 *
 * The state is an expiry tick in the player's temporary attributes, so nothing has to tick it.
 */
object Weary {

    /** guardian.json `mushrooms.wearyTicks` default (spec §4.1). */
    const val DEFAULT_TICKS = 25

    private const val KEY = "gadscape_weary_until"

    /** Makes [player] weary for [ticks] ticks from now (refreshing a running timer) and switches running off. */
    @JvmStatic
    @JvmOverloads
    fun apply(player: Player, ticks: Int = DEFAULT_TICKS) {
        if (ticks <= 0 || player.isDead || player.isFinished) return
        player.temporaryAttributes[KEY] = WorldThread.getCurrentCycle() + ticks
        if (player.isRun) player.setRun(false)
    }

    @JvmStatic
    fun isWeary(player: Player): Boolean = remainingTicks(player) > 0

    /** Ticks of Weary left (0 when not weary). */
    @JvmStatic
    fun remainingTicks(player: Player): Int {
        val until = player.temporaryAttributes[KEY] as? Long ?: return 0
        return remaining(until, WorldThread.getCurrentCycle())
    }

    @JvmStatic
    fun clear(player: Player) {
        player.temporaryAttributes.remove(KEY)
    }

    /** The attack delay while weary: one tick slower (a whip's 4 becomes 5). */
    @JvmStatic
    fun adjustAttackSpeed(speed: Int, weary: Boolean): Int = if (weary) speed + 1 else speed

    /** Ticks left of a Weary that expires on tick [untilTick], seen on tick [nowTick]. */
    @JvmStatic
    fun remaining(untilTick: Long, nowTick: Long): Int = (untilTick - nowTick).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
