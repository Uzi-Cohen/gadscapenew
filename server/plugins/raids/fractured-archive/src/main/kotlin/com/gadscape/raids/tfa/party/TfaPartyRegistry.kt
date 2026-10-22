package com.gadscape.raids.tfa.party

import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.lobby.LobbyConfig
import com.zenyte.game.world.World
import com.zenyte.game.world.WorldThread
import com.zenyte.game.world.entity.player.Player
import org.slf4j.LoggerFactory

/**
 * The world's Grouping Board (spec §3.3): every party, every application, and who is looking at which board page.
 * Runs on the world thread only (interface clicks, loc ops, login/logout events and raid callbacks all arrive there).
 *
 * Every change ends in [changed], which re-pushes 976 to every viewer of that party, the 45 rows of 977 to every
 * open board, and refreshes 975, varp 5913 and varbit 16386 for the members ([LobbyOverlay]).
 *
 * API for other units:
 * - [partyOf] / [byId] / [all] / [recentRaidParties]: lookups (spectating, raid creation).
 * - [removeMember]: the raid (fw-core) drops a member who left the raid for good.
 * - [TfaParty.onRaidCreated] / [TfaParty.onRaidEnded]: called by the raid ([com.gadscape.raids.tfa.raid.TfaPartyView]).
 */
object TfaPartyRegistry {

    private val log = LoggerFactory.getLogger(TfaPartyRegistry::class.java)

    internal val book = PartyBook<Player>(Limits()) { it.name }
    private val wrappers = HashMap<Int, TfaParty>()

    /** 976 auto-refresh and the Refresh buttons: at most one per `tfa.json party.refreshRateLimitTicks`. */
    internal val refreshLimiter = RefreshLimiter { TfaConfig.current.raid.party.refreshRateLimitTicks }

    @JvmStatic
    fun currentTick(): Long = WorldThread.getCurrentCycle()

    /** Applies `lobby.json party.*` (at launch and after `::tfareload`). */
    @JvmStatic
    fun configure(cfg: LobbyConfig.Party) {
        book.limits = Limits(cfg.maxParties, cfg.maxMembers, cfg.maxApplicants)
    }

    // ------------------------------------------------------------------------------------------------- lookups

    @JvmStatic
    fun partyOf(player: Player): TfaParty? = book.partyOf(player)?.let(::wrap)

    @JvmStatic
    fun applicationOf(player: Player): TfaParty? = book.applicationOf(player)?.let(::wrap)

    @JvmStatic
    fun byId(id: Int): TfaParty? = book[id]?.let(::wrap)

    /** Parties in board order. */
    @JvmStatic
    fun all(): List<TfaParty> = book.all.map(::wrap)

    /** Parties with a raid, most recently created first ("Spectate recent party", §3.13). */
    @JvmStatic
    fun recentRaidParties(): List<TfaParty> = all().filter { it.raid != null }.sortedByDescending { it.raidCreatedTick }

    private fun wrap(state: PartyState<Player>): TfaParty = wrappers.getOrPut(state.id) { TfaParty(state) }

    // ------------------------------------------------------------------------------------------------- actions

    /** "Make Party" (977 sub 1 without a party). */
    fun create(player: Player): TfaParty? {
        val previousApplication = book.applicationOf(player)
        val (result, state) = book.create(player, currentTick())
        if (result != PartyResult.OK || state == null) {
            result.message?.let(player::sendMessage)
            return null
        }
        state.mode = if (TfaConfig.current.raid.practice.enabled) {
            player.varManager.getBitValue(TfaIds.Varbit.PREFERRED_MODE).coerceIn(0, 1)
        } else 0
        val party = wrap(state)
        previousApplication?.let { changed(wrap(it)) }
        changed(party, player)
        return party
    }

    fun apply(player: Player, party: TfaParty) {
        val previous = book.applicationOf(player)
        val result = book.apply(player, party.state)
        if (result != PartyResult.OK) {
            result.message?.let(player::sendMessage)
            return
        }
        if (previous != null && previous !== party.state) changed(wrap(previous))
        if (TfaConfig.current[LobbyConfig].party.notifyLeaderOnApply) {
            party.leader.sendMessage("<col=ef1020>${player.name} has applied to join your party.</col>")
        }
        changed(party, player)
    }

    fun withdraw(player: Player) {
        val state = book.withdraw(player) ?: return
        changed(wrap(state), player)
    }

    /** [player] leaves their party ("Leave", "Quit Party", logout, or the raid dropping them). */
    fun leave(player: Player, message: String? = "You have left the party.") {
        val party = book.partyOf(player)?.let(::wrap) ?: return
        if (party.state.locked) {
            player.sendMessage(PartyResult.LOCKED.message!!)
            return
        }
        removeInternal(player, party, message)
    }

    /** The raid (fw-core) or logout drops [player] from their party even while it is inside a raid. */
    @JvmStatic
    fun removeMember(player: Player, message: String? = null) {
        val party = book.partyOf(player)?.let(::wrap) ?: return
        removeInternal(player, party, message)
    }

    private fun removeInternal(player: Player, party: TfaParty, message: String?) {
        val leave = book.leave(player) ?: return
        message?.let(player::sendMessage)
        if (leave.disbanded) {
            leave.releasedApplicants.forEach { it.sendMessage("That party is no longer recruiting.") }
            dropParty(party)
        } else if (leave.newLeader != null) {
            leave.newLeader.sendMessage("You are now the leader of your party.")
        }
        changed(if (leave.disbanded) null else party, player, *leave.releasedApplicants.toTypedArray())
    }

    /** Leader's "Disband". */
    fun disband(leader: Player, party: TfaParty) {
        if (!party.state.isLeader(leader)) return leader.sendMessage(PartyResult.NOT_LEADER.message!!)
        if (party.state.locked) return leader.sendMessage(PartyResult.LOCKED.message!!)
        val (members, applicants) = book.disband(party.state)
        members.forEach { it.sendMessage("Your party has disbanded.") }
        applicants.forEach { it.sendMessage("That party is no longer recruiting.") }
        dropParty(party)
        changed(null, *(members + applicants).toTypedArray())
    }

    fun accept(leader: Player, party: TfaParty, index: Int) {
        if (!party.state.isLeader(leader)) return leader.sendMessage(PartyResult.NOT_LEADER.message!!)
        val (result, member) = book.accept(party.state, index)
        if (result != PartyResult.OK || member == null) {
            result.message?.let(leader::sendMessage)
            changed(party)
            return
        }
        member.sendMessage("You have joined ${leader.name}'s party.")
        changed(party, member)
    }

    fun reject(leader: Player, party: TfaParty, index: Int) {
        if (!party.state.isLeader(leader)) return leader.sendMessage(PartyResult.NOT_LEADER.message!!)
        val (result, applicant) = book.reject(party.state, index)
        if (result != PartyResult.OK || applicant == null) return
        applicant.sendMessage("Your application to join ${leader.name}'s party was declined.")
        changed(party, applicant)
    }

    /** Leader's kick on member [slot]; slot 0 is "Quit Party". */
    fun kick(leader: Player, party: TfaParty, slot: Int) {
        if (!party.state.isLeader(leader)) return leader.sendMessage(PartyResult.NOT_LEADER.message!!)
        if (slot == 0) return leave(leader)
        val (result, member) = book.kick(party.state, slot)
        if (result != PartyResult.OK || member == null) {
            result.message?.let(leader::sendMessage)
            return
        }
        member.sendMessage("You have been removed from the party.")
        changed(party, member)
    }

    fun move(leader: Player, party: TfaParty, slot: Int, up: Boolean) {
        if (!party.state.isLeader(leader)) return leader.sendMessage(PartyResult.NOT_LEADER.message!!)
        val result = if (up) party.state.moveUp(slot) else party.state.moveDown(slot)
        if (result != PartyResult.OK) {
            result.message?.let(leader::sendMessage)
            return
        }
        changed(party)
    }

    fun unblock(leader: Player, party: TfaParty) {
        if (!party.state.isLeader(leader)) return leader.sendMessage(PartyResult.NOT_LEADER.message!!)
        if (party.state.unblockAll()) leader.sendMessage("You have unblocked every player your party had declined.")
        changed(party)
    }

    fun setPreferredSize(leader: Player, party: TfaParty, size: Int) {
        if (!party.state.isLeader(leader)) return
        party.state.preferredSize = size.coerceIn(0, book.limits.maxMembers)
        changed(party)
    }

    fun setPreferredCompletions(leader: Player, party: TfaParty, completions: Int) {
        if (!party.state.isLeader(leader)) return
        party.state.preferredCompletions = completions.coerceIn(0, 99_999)
        changed(party)
    }

    fun toggleSpectating(leader: Player, party: TfaParty) {
        if (!party.state.isLeader(leader)) return
        party.state.spectatingDisabled = !party.state.spectatingDisabled
        leader.sendMessage(if (party.state.spectatingDisabled) "Spectating of your party is now disabled." else "Spectating of your party is now enabled.")
        changed(party)
    }

    // ------------------------------------------------------------------------------------------- raid callbacks

    internal fun onRaidCreated(party: TfaParty) {
        changed(party)
    }

    internal fun onRaidEnded(party: TfaParty) {
        // Members who logged out during the raid leave the party now (the raid held them until it ended).
        party.members.filterNot(::isOnline).forEach { book.leave(it) }
        if (party.state.isEmpty) {
            dropParty(party)
            changed(null)
        } else changed(party)
    }

    /** Logout: applications are withdrawn; a member leaves unless the party is inside a raid (the raid decides). */
    @JvmStatic
    fun onLogout(player: Player) {
        refreshLimiter.forget(player)
        PartyViewers.forget(player)
        book.withdraw(player)?.let { changed(wrap(it)) }
        val party = book.partyOf(player)?.let(::wrap) ?: return
        if (party.raid == null) removeInternal(player, party, null)
    }

    // ---------------------------------------------------------------------------------------------- broadcast

    /**
     * Re-pushes everything that shows [party] (null when it no longer exists): 976 to its viewers, 977 rows to every
     * board, 975 / 5913 / 16386 to its members and to [touched] players.
     */
    internal fun changed(party: TfaParty?, vararg touched: Player) {
        try {
            val refresh = LinkedHashSet<Player>()
            party?.state?.members?.let(refresh::addAll)
            refresh.addAll(touched)
            refresh.forEach(LobbyOverlay::refresh)
            PartyViewers.pushAll(party)
        } catch (e: Exception) {
            log.error("Grouping Board update failed for {}", party, e)
        }
    }

    private fun dropParty(party: TfaParty) {
        wrappers.remove(party.id)
        PartyViewers.partyGone(party.id)
    }

    internal fun isOnline(player: Player): Boolean =
        !player.isFinished && World.getPlayer(player.username).orElse(null) === player

    /** `::tfa party add <name>`: puts [other] straight into [leader]'s party (apply + accept), for tests. */
    fun debugAdd(leader: Player, other: Player): String {
        val party = partyOf(leader) ?: create(leader) ?: return "Could not create a party."
        if (book.partyOf(other) != null) return "${other.name} is already in a party."
        val applied = book.apply(other, party.state)
        if (applied != PartyResult.OK) return applied.message ?: applied.name
        val (accepted, _) = book.accept(party.state, party.state.applicants.indexOf(other))
        if (accepted != PartyResult.OK) return accepted.message ?: accepted.name
        changed(party, other)
        return "${other.name} joined party #${party.id}."
    }

    /** Lines for `::tfa party list` and `::tfa dump`. */
    fun describe(): List<String> = all().map { p ->
        "#${p.id} ${p.members.joinToString { it.name }} | applicants ${p.applicants.joinToString { it.name }.ifEmpty { "-" }}" +
            " | size ${p.preferredSize} kc ${p.preferredCompletions} mode ${p.mode} spec ${if (p.spectatingDisabled) "off" else "on"}" +
            (if (p.raid != null) " | IN RAID" else "")
    }
}
