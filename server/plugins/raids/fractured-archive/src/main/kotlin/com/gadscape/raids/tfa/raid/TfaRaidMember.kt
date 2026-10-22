package com.gadscape.raids.tfa.raid

import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.player.Player
import java.lang.ref.WeakReference
import java.util.EnumMap
import java.util.EnumSet

/** Where a raid member is (spec §2.4 room flow). */
enum class Zone {
    /** Not in the instance (still in the lobby, or gone). */
    OUTSIDE,

    /** The nexus / atrium (also the Guardian's arena). */
    NEXUS,

    /** A paired room, between its Passageway and its transporter. */
    ANTECHAMBER,

    /** A paired room's sealed arena (entered by the transporter). */
    ARENA,

    /** Region 16472: the Ocellus corridor and arena. */
    OCELLUS,

    /** Region 16474: the reward vault. */
    VAULT,
}

/**
 * One raid member, in board order (the order fixes the HUD orb slot, spec §3.3/§3.7). Members who have not entered yet
 * keep their slot ([Zone.OUTSIDE]); members who leave for good are [left] and their slot shows empty.
 */
class TfaRaidMember(val slot: Int, player: Player) {

    /** Lower-case username (contribution, stats, KC keys). */
    val username: String = player.username.lowercase()

    /** Display name (HUD names, messages). */
    val name: String = player.name

    /** The live player object; replaced if the same account is handed over again. */
    var player: Player = player
        internal set

    var zone: Zone = Zone.OUTSIDE
        internal set

    /** The paired room of [Zone.ANTECHAMBER] / [Zone.ARENA]. */
    var room: TfaRoomId? = null
        internal set

    /** Entered the instance at least once. */
    var entered = false
        internal set

    /** Left the raid for good (logout, teleport out, lobby); the slot shows empty. */
    var left = false
        internal set

    /** Dead and spectating until the current encounter completes (spec §3.8). */
    var dead = false
        internal set

    /** Dead and past the death animation (the spectate state); a wipe waits for every death to get here. */
    var spectating = false
        internal set

    /** The encounter this member died in (revive / wipe bookkeeping). */
    var diedIn: TfaRoomId? = null
        internal set

    /** Static tile of the last death (gravestone fee zone, §3.8). */
    var deathTile: StaticTile? = null
        internal set

    private var killerRef: WeakReference<Entity>? = null

    /** Whoever dealt the killing blow of the last death, while it is still in memory. */
    var killer: Entity?
        get() = killerRef?.get()
        internal set(value) {
            killerRef = value?.let { WeakReference(it) }
        }

    /** Deaths per room this raid (stats; supply credits count rooms 1-3). */
    val deaths: MutableMap<TfaRoomId, Int> = EnumMap(TfaRoomId::class.java)

    /** Rooms whose seq prefetch this member already ran (§3.7 "once per room"). */
    val prefetched: EnumSet<TfaRoomId> = EnumSet.noneOf(TfaRoomId::class.java)

    /** Section numbers whose "commit" confirmation this member already answered (§3.5). */
    val confirmedSections: MutableSet<Int> = HashSet()

    /** The slot this member's camera follows while spectating (-1 = room view). */
    var spectatedSlot: Int = -1
        internal set

    /** Supply Credits granted (once, at room 3). */
    var creditsGranted = false
        internal set

    /** This member uses the failure shop (17151). */
    var failureShop = false
        internal set

    val isActive: Boolean get() = !left

    /** In the instance, not left. */
    val inRaid: Boolean get() = !left && zone != Zone.OUTSIDE

    /** In the instance, alive (not spectating), online. */
    val alive: Boolean get() = inRaid && !dead && !player.isFinished && !player.isDead

    fun deathsIn(rooms: Collection<TfaRoomId>): Int = rooms.sumOf { deaths[it] ?: 0 }

    override fun toString(): String =
        "$name#$slot(${zone.name.lowercase()}${room?.let { ":" + it.configKey } ?: ""}" +
            (if (dead) ",dead" else "") + (if (left) ",left" else "") + ")"
}
