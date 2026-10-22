package com.gadscape.raids.tfa.party

import com.gadscape.raids.tfa.raid.TfaPartyView
import com.zenyte.game.world.entity.player.Player

/**
 * A Grouping Board party (spec §3.3), as the raid sees it ([TfaPartyView]). The line-up and settings live in
 * [state]; every change goes through [TfaPartyRegistry], which keeps the interfaces and varps in step.
 *
 * Fields of spec §3.3: [members] (max 8, ordered; the order sets the HUD orb slots), applicants (max 7), the block
 * list, [leader], preferred size and completions, mode (enum 6051), [spectatingDisabled], [creationTick], [raid].
 */
class TfaParty internal constructor(internal val state: PartyState<Player>) : TfaPartyView {

    override val id: Int get() = state.id

    override val leader: Player get() = state.leader

    override val members: List<Player> get() = state.members.toList()

    val applicants: List<Player> get() = state.applicants.toList()

    val preferredSize: Int get() = state.preferredSize

    val preferredCompletions: Int get() = state.preferredCompletions

    /** enum 6051: 0 Normal, 1 Practice (Practice only while `tfa.json practice.enabled`). */
    val mode: Int get() = state.mode

    /** The leader's eye toggle (976:7): WU-18 refuses new external spectators while it is set. */
    val spectatingDisabled: Boolean get() = state.spectatingDisabled

    val creationTick: Long get() = state.creationTick

    /** The raid created for this party, set by the raid through [onRaidCreated]; null in the lobby. */
    @Volatile
    var raid: Any? = null
        private set

    /** World tick of the last raid creation (for "Spectate recent party"). */
    @Volatile
    var raidCreatedTick: Long = -1
        private set

    fun isMember(player: Player): Boolean = state.isMember(player)

    fun slotOf(player: Player): Int = state.members.indexOf(player)

    override fun onRaidCreated(raid: Any) {
        this.raid = raid
        state.locked = true
        raidCreatedTick = TfaPartyRegistry.currentTick()
        TfaPartyRegistry.onRaidCreated(this)
    }

    override fun onRaidEnded() {
        raid = null
        state.locked = false
        TfaPartyRegistry.onRaidEnded(this)
    }

    override fun toString(): String =
        "TfaParty#$id(${state.members.joinToString { it.username }}${if (raid != null) ", in raid" else ""})"
}
