package com.gadscape.raids.tfa.points

import com.gadscape.raids.tfa.ContributionSink
import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.raid.RoomRuntime
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player

/**
 * WU-16 contribution (spec §3.10), installed as `TfaServices.contribution`. `TfaNpc` reports every point of damage a
 * player deals to any NPC of a room (boss and adds, capped at the target's remaining HP); this routes it to the raid's
 * [ContributionLedger] under the encounter's room. Banking at completion, the death penalty and the thresholds are in
 * the ledger; the raid calls them.
 *
 * For encounters: the room's pool is [poolOf] (`points.json pools.<room>`); `TfaEncounter.contributionPool` should
 * return it.
 */
object ContributionService : ContributionSink {

    override fun recordDamage(context: TfaEncounterContext, player: Player, target: NPC, amount: Int) {
        val rt = context as? RoomRuntime ?: return
        val raid = rt.raid
        if (raid.memberOf(player) == null) return
        raid.ledger.record(rt.room, player.username, amount)
    }

    /** The contribution pool of [context]'s room (`points.json`, from the raid's config snapshot). */
    @JvmStatic
    fun poolOf(context: TfaEncounterContext): Int = context.config[PointsConfig].pool(context.room)

    /** The pool of a room in the live config (tools, standalone debug runs). */
    @JvmStatic
    fun pool(room: com.gadscape.raids.tfa.raid.TfaRoomId): Int = TfaConfig.current[PointsConfig].pool(room)

    override fun toString(): String = "ContributionService"
}
