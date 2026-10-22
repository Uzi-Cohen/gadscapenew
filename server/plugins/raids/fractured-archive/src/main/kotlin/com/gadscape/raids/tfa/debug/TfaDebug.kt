package com.gadscape.raids.tfa.debug

import com.zenyte.game.GameConstants
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.SkillConstants
import java.util.Collections
import java.util.WeakHashMap

/**
 * Debug state set by `::tfa` and read by the raid code. Lives only in memory, per player session.
 *
 * Contracts for later units:
 * - **godmode**: damage code that hits players (boss attacks, hazards, death handling) should skip players for
 *   whom [isGodmode] is true. As a backstop the module restores their HP and prayer every tick ([restore]).
 * - **scale**: when an encounter starts, the raid (WU-12) passes `TfaDebug.forcedScale(members)` to
 *   `Scaling.partyScale(memberCount, forced)`.
 */
object TfaDebug {

    private val godmode: MutableSet<Player> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val forced: MutableMap<Player, Int> = Collections.synchronizedMap(WeakHashMap())

    /** True on development worlds (`WORLD_PROFILE.isDevelopment()`); `::tfa` is open to everyone there. */
    @JvmStatic
    fun isDevelopmentWorld(): Boolean = GameConstants.WORLD_PROFILE?.isDevelopment() == true

    @JvmStatic
    fun isGodmode(player: Player): Boolean = godmode.isNotEmpty() && player in godmode

    /** Toggles godmode for [player]; returns the new state. */
    @JvmStatic
    fun toggleGodmode(player: Player): Boolean = synchronized(godmode) {
        if (!godmode.remove(player)) {
            godmode += player
            true
        } else false
    }

    /** Called every tick for players in godmode: tops up HP and prayer. */
    @JvmStatic
    fun restore(player: Player) {
        val max = player.maxHitpoints
        if (player.hitpoints < max) player.setHitpoints(max)
        val prayer = player.skills.getLevelForXp(SkillConstants.PRAYER)
        val manager = player.prayerManager ?: return
        if (manager.prayerPoints < prayer) manager.setPrayerPoints(prayer)
    }

    /** Forces the party scale of encounters started by [player]'s raid (null clears it). */
    @JvmStatic
    fun forceScale(player: Player, scale: Int?) {
        if (scale == null) forced.remove(player) else forced[player] = scale
    }

    @JvmStatic
    fun forcedScale(player: Player): Int? = forced[player]

    /** The largest scale forced by any of [members], or null if none forced one. */
    @JvmStatic
    fun forcedScale(members: Collection<Player>): Int? {
        if (forced.isEmpty()) return null
        return members.mapNotNull { forced[it] }.maxOrNull()
    }
}
