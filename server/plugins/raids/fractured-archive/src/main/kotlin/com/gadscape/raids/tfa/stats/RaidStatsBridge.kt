package com.gadscape.raids.tfa.stats

import com.gadscape.raids.tfa.raid.RaidEndReason
import com.gadscape.raids.tfa.raid.RaidPhase
import com.gadscape.raids.tfa.raid.TfaRaid
import com.gadscape.raids.tfa.raid.TfaRaidListener
import com.gadscape.raids.tfa.raid.TfaRaidMember
import com.gadscape.raids.tfa.raid.TfaRaids
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.world.entity.player.Player

/**
 * Feeds [TfaStats] from the raid's lifecycle events (fw-core `TfaRaidEvents`, spec §3.14):
 * Guardian touch → Overall Attempts; room start → room Attempts; room complete → Completions + room time; death;
 * wipe; Ocellus's death → Overall Completions, KC, overall time; raid ended FAILED → Failures.
 */
object TfaStatsListener : TfaRaidListener {

    private fun players(members: List<TfaRaidMember>): List<Player> = members.map { it.player }.filter { !it.isFinished }

    override fun onEncounterStarted(raid: TfaRaid, room: TfaRoomId, present: List<TfaRaidMember>) {
        val players = players(present)
        if (room == TfaRoomId.GUARDIAN) TfaStats.raidStarted(players)
        TfaStats.roomStarted(room, players)
    }

    override fun onEncounterCompleted(raid: TfaRaid, room: TfaRoomId, ticks: Int, present: List<TfaRaidMember>) {
        val overall = if (raid.guardianTouchTick >= 0) raid.ticks - raid.guardianTouchTick else -1
        TfaStats.roomCompleted(room, players(present), ticks, overall, raid.raidSize.coerceIn(1, 8))
    }

    override fun onMemberDeath(raid: TfaRaid, member: TfaRaidMember, room: TfaRoomId?) {
        TfaStats.death(member.player, room)
    }

    override fun onWipe(raid: TfaRaid, room: TfaRoomId, members: List<TfaRaidMember>) {
        TfaStats.wiped(players(members))
    }

    override fun onRaidCompleted(raid: TfaRaid, overallTicks: Int, present: List<TfaRaidMember>) {
        TfaStats.raidCompleted(players(present), overallTicks, raid.raidSize.coerceIn(1, 8))
    }

    override fun onRaidEnded(raid: TfaRaid, reason: RaidEndReason) {
        if (reason == RaidEndReason.FAILED) TfaStats.failed(players(raid.members.filter { it.entered }))
    }
}

/**
 * The Alliance Log's view of a running raid ([RaidStatsSource]) built from fw-core's [TfaRaid]: the Current column,
 * and the Party Stats tab (damage done from the contribution ledger, deaths per member, MVP per room = highest damage
 * [HOUSE], room splits from the room runtimes). Damage taken is not tracked by the raid yet and shows 0.
 */
object RaidStatsFromRaid : RaidStatsSource {

    private fun raidOf(player: Player): TfaRaid? = TfaRaids.raidOf(player)?.takeIf { it.phase != RaidPhase.ENDED }

    override fun isInActiveRaid(player: Player): Boolean = raidOf(player) != null

    override fun current(player: Player, category: StatsCategory, row: StatsRow, partySize: Int): CurrentValue? {
        val raid = raidOf(player) ?: return null
        val member = raid.memberOf(player) ?: return null
        val room = category.room
        if (room == null) {
            val started = raid.guardianTouchTick >= 0
            return when (row) {
                StatsRow.ATTEMPTS -> CurrentValue(if (started) 1 else 0)
                StatsRow.COMPLETIONS -> CurrentValue(if (raid.completedTick >= 0) 1 else 0)
                StatsRow.DEATHS -> CurrentValue(member.deaths.values.sum())
                StatsRow.WIPES, StatsRow.FAILURES -> CurrentValue(0)
                StatsRow.TIME -> if (!started || partySize != raid.raidSize) null
                    else if (raid.completedTick >= 0) CurrentValue(raid.completedTick - raid.guardianTouchTick, frozen = true)
                    else CurrentValue(raid.ticks - raid.guardianTouchTick, frozen = false)
                StatsRow.COMPLETIONS_PRACTICE -> null
            }
        }
        val rt = raid.runtime(room) ?: return null
        if (!rt.started) return null
        return when (row) {
            StatsRow.ATTEMPTS -> CurrentValue(1)
            StatsRow.COMPLETIONS -> CurrentValue(if (rt.completeTick >= 0) 1 else 0)
            StatsRow.COMPLETIONS_PRACTICE -> CurrentValue(0)
            StatsRow.DEATHS -> CurrentValue(member.deaths[room] ?: 0)
            StatsRow.TIME -> if (partySize != raid.raidSize) null
                else if (rt.completeTick >= 0) CurrentValue(rt.roomTicks, frozen = true)
                else CurrentValue(raid.ticks - rt.startTick, frozen = false)
            StatsRow.WIPES, StatsRow.FAILURES -> null
        }
    }

    override fun partyStats(player: Player): PartyStatsSnapshot? {
        val raid = raidOf(player) ?: return null
        val rooms = TfaRoomId.ROUTE_ORDER.filter { raid.runtime(it)?.started == true }
        val members = raid.members.filter { it.entered }
        val mvpCounts = HashMap<String, Int>()
        for (room in rooms) {
            val damage = raid.ledger.damageOf(room)
            val best = damage.maxByOrNull { it.value }?.takeIf { it.value > 0 } ?: continue
            mvpCounts.merge(best.key.lowercase(), 1, Int::plus)
        }
        val lines = members.map { m ->
            val done = rooms.sumOf { raid.ledger.damage(it, m.username) }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            MemberStats(m.name, done, 0, m.deaths.values.sum(), mvpCounts[m.username] ?: 0)
        }
        val mvp = lines.filter { it.mvpCount > 0 }.maxWithOrNull(compareBy<MemberStats>({ it.mvpCount }, { it.damageDone }))?.name ?: ""
        val (overall, state) = when {
            raid.guardianTouchTick < 0 -> 0 to 0
            raid.completedTick >= 0 -> (raid.completedTick - raid.guardianTouchTick) to 4
            else -> (raid.ticks - raid.guardianTouchTick) to 2
        }
        val splits = rooms.map { room ->
            val rt = raid.runtime(room)!!
            RoomSplit(StatsCategory.of(room), if (rt.completeTick >= 0) rt.roomTicks else raid.ticks - rt.startTick)
        }
        return PartyStatsSnapshot(raid.raidSize.coerceIn(1, 8), lines, mvp, overall, state, splits)
    }
}
