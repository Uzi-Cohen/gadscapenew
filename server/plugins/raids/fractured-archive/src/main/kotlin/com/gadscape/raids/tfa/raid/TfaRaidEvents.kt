package com.gadscape.raids.tfa.raid

import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/** Why a member stopped being part of a raid. */
enum class LeaveReason { LOGOUT, TELEPORT, LOBBY, WIPE, RAID_ENDED, REMOVED }

/**
 * Raid lifecycle events for the units that only observe a raid: stats / KC / personal bests (WU-19), rewards and the
 * vault (WU-29), collection log (WU-30), Practice Mode (WU-31). Register once at server launch with
 * [TfaRaidEvents.register]; every method has a no-op default. Called on the world thread, in raid order.
 *
 * | Event | Spec | Typical use |
 * |---|---|---|
 * | [onRaidCreated] | §2.3 CREATED | "Step Inside Now!" |
 * | [onMemberEntered] | §3.4 | HUD extras |
 * | [onEncounterStarted] | §3.14 Attempts | Attempts +1 for [present] (Overall when the room is the Guardian: [present] = party at the touch) |
 * | [onEncounterCompleted] | §3.14 Completions, Time | Completions +1, room time [ticks] for party size [TfaRaid.raidSize] |
 * | [onMemberDeath] | §3.8, §3.14 Deaths | Deaths +1 (room and Overall) |
 * | [onWipe] | §3.8 | Wipes +1 (Overall) |
 * | [onRaidCompleted] | §3.14, §3.15 | KC +1 for [present] (alive or dead), overall time, roll rewards (points: [TfaRaid.ledger]) |
 * | [onRaidEnded] | §2.3 ENDED | Failures +1 when [reason] is FAILED |
 * | [onMemberLeft] | §3.8 | |
 */
interface TfaRaidListener {
    fun onRaidCreated(raid: TfaRaid) {}
    fun onMemberEntered(raid: TfaRaid, member: TfaRaidMember) {}
    fun onEncounterStarted(raid: TfaRaid, room: TfaRoomId, present: List<TfaRaidMember>) {}
    fun onEncounterCompleted(raid: TfaRaid, room: TfaRoomId, ticks: Int, present: List<TfaRaidMember>) {}
    fun onMemberDeath(raid: TfaRaid, member: TfaRaidMember, room: TfaRoomId?) {}
    fun onWipe(raid: TfaRaid, room: TfaRoomId, members: List<TfaRaidMember>) {}
    fun onRaidCompleted(raid: TfaRaid, overallTicks: Int, present: List<TfaRaidMember>) {}
    fun onRaidEnded(raid: TfaRaid, reason: RaidEndReason) {}
    fun onMemberLeft(raid: TfaRaid, member: TfaRaidMember, reason: LeaveReason) {}
}

object TfaRaidEvents {

    private val log = LoggerFactory.getLogger(TfaRaidEvents::class.java)
    private val listeners = CopyOnWriteArrayList<TfaRaidListener>()

    @JvmStatic
    fun register(listener: TfaRaidListener) {
        if (listeners.none { it === listener }) listeners += listener
    }

    @JvmStatic
    fun unregister(listener: TfaRaidListener) {
        listeners.removeIf { it === listener }
    }

    fun registered(): List<TfaRaidListener> = listeners.toList()

    internal fun fire(what: String, action: (TfaRaidListener) -> Unit) {
        for (l in listeners) {
            try {
                action(l)
            } catch (e: Exception) {
                log.error("Fractured Archive: raid listener {} failed on {}", l.javaClass.name, what, e)
            }
        }
    }
}
