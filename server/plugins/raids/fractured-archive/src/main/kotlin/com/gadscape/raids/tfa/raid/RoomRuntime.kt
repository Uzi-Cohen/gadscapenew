package com.gadscape.raids.tfa.raid

import com.gadscape.raids.tfa.config.TfaConfigSnapshot
import com.gadscape.raids.tfa.encounter.Scaling
import com.gadscape.raids.tfa.encounter.TfaEncounter
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.zenyte.game.world.entity.player.Player
import java.util.Random

/** Life of one room inside a raid (spec §2.2 lifecycle as driven by WU-12). */
enum class RoomState {
    /** Created and built (region copied, `build()` done); waiting for its trigger. */
    BUILT,

    /** `start()` called; ticked every tick. */
    ACTIVE,

    /** `isComplete()` seen and `onComplete()` called. */
    COMPLETE,

    /** Wiped (`onWipe()` called). */
    WIPED,

    /** `teardown()` called. */
    TORN_DOWN,
}

/**
 * The raid side of one encounter: the [TfaEncounterContext] given to the room's factory, plus the bookkeeping the raid
 * needs (state, start / completion ticks, participants, the scale fixed at start).
 *
 * Encounter units receive this object as their `context`; [TfaRaids.raidOf] maps it back to its raid (e.g. to ask
 * for the start with [TfaRaids.requestStart]).
 */
class RoomRuntime internal constructor(val raid: TfaRaid, override val room: TfaRoomId) : TfaEncounterContext {

    var encounter: TfaEncounter? = null
        internal set

    var state: RoomState = RoomState.BUILT
        internal set

    /** Raid tick of `start()`, or -1. */
    var startTick: Int = -1
        internal set

    /** Raid tick of completion, or -1. */
    var completeTick: Int = -1
        internal set

    /** Party scale fixed when the encounter started (null before). */
    var fixedScale: Int? = null
        internal set

    /** Members who entered this encounter's arena while it was built or running (wipe rule, §3.8). */
    val participants: MutableSet<TfaRaidMember> = LinkedHashSet()

    override val config: TfaConfigSnapshot get() = raid.config

    override val scaling: Scaling get() = raid.scaling

    /** Fixed at start; before that, the scale the raid would use now (members inside the instance). */
    override val scale: Int get() = fixedScale ?: raid.currentScale()

    override val instance: TfaInstance get() = raid.instance

    override val random: Random get() = raid.random

    override val members: List<Player> get() = raid.activeMembers().map { it.player }

    override fun playersInArena(): List<Player> = raid.membersInArena(room).map { it.player }

    override val encounterTicks: Int get() = if (startTick < 0) 0 else raid.ticks - startTick

    val started: Boolean get() = startTick >= 0

    val isActive: Boolean get() = state == RoomState.ACTIVE

    /** Ticks from start to completion, or -1. */
    val roomTicks: Int get() = if (startTick >= 0 && completeTick >= 0) completeTick - startTick else -1

    override fun toString(): String = "RoomRuntime(${room.configKey}, $state, start=$startTick, scale=$scale)"
}
