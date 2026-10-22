package com.gadscape.raids.tfa.party

/**
 * The Grouping Board rules (spec §3.3), free of engine types so they can be unit-tested with any member type
 * ([TfaParty] uses `Player`, tests use names).
 *
 * Rules:
 * - [CACHE] at most [Limits.maxParties] parties listed (enum 6058, 45) and at most [Limits.maxApplicants] applicants
 *   (the 976 UI handles 7); [DOC] 1-8 members.
 * - [INF, ToB/ToA] one party per player; no applying while in a party; one application at a time (a new one replaces
 *   the old); the leader leaving hands leadership to slot 1 with the settings; an empty party is removed.
 * - Rejected applicants go on the party's block list until the leader uses Unblock.
 * - While the party is inside a raid ([PartyState.locked]) its line-up cannot change from the board.
 */
data class Limits(val maxParties: Int = 45, val maxMembers: Int = 8, val maxApplicants: Int = 7)

/** Outcome of a board action; [message] is the chat line for the acting player (null = none). */
enum class PartyResult(val message: String?) {
    OK(null),
    NOT_LEADER("Only the party leader can do that."),
    ALREADY_IN_PARTY("You are already in a party."),
    ALREADY_APPLIED("You have already applied to join this party."),
    PARTY_FULL("That party is full."),
    TOO_MANY_APPLICANTS("That party has too many applicants already."),
    BLOCKED("That party is not accepting applications from you."),
    LOCKED("You can't change a party that is already inside the Archive."),
    NO_SUCH_PARTY("That party is no longer recruiting."),
    NO_SUCH_SLOT(null),
    BOARD_FULL("The Grouping Board is full. Please come back later or apply to an existing party."),
    NOT_IN_PARTY("You are not in a party."),
    NOT_AN_APPLICANT(null),
    LEADER_STAYS_FIRST("The party leader must stay at the top of the list."),
}

/**
 * One party's line-up and settings. [M] is the member type; [nameOf] gives the display name (used for the block
 * list, which survives the blocked player logging out).
 */
class PartyState<M : Any>(
    val id: Int,
    leader: M,
    val creationTick: Long,
    private val limits: Limits,
    private val nameOf: (M) -> String,
) {
    /** Members in board order; index 0 is the leader. */
    val members: MutableList<M> = mutableListOf(leader)

    /** Applicants in arrival order (max [Limits.maxApplicants]). */
    val applicants: MutableList<M> = mutableListOf()

    /** Lower-case names the leader rejected. */
    val blocked: MutableSet<String> = linkedSetOf()

    /** Preferred size 1-8, 0 = not set (976 shows "Set Preferred Size" / "-"). */
    var preferredSize: Int = 0

    /** Preferred completions 0-99999, 0 = not set ("-"). */
    var preferredCompletions: Int = 0

    /** enum 6051: 0 Normal, 1 Practice. */
    var mode: Int = 0

    /** The eye icon of 976:7 [DOC]: true blocks new spectators. */
    var spectatingDisabled: Boolean = false

    /** True while the party has a raid (the raid owns the line-up). */
    var locked: Boolean = false

    val leader: M get() = members[0]

    val isFull: Boolean get() = members.size >= limits.maxMembers

    fun isLeader(m: M): Boolean = members.firstOrNull() == m

    fun isMember(m: M): Boolean = m in members

    fun isApplicant(m: M): Boolean = m in applicants

    fun isBlocked(m: M): Boolean = nameOf(m).lowercase() in blocked

    /** Adds [m] as an applicant (the caller has checked [m] is in no party). */
    fun apply(m: M): PartyResult = when {
        locked -> PartyResult.LOCKED
        isMember(m) -> PartyResult.ALREADY_IN_PARTY
        isApplicant(m) -> PartyResult.ALREADY_APPLIED
        isBlocked(m) -> PartyResult.BLOCKED
        isFull -> PartyResult.PARTY_FULL
        applicants.size >= limits.maxApplicants -> PartyResult.TOO_MANY_APPLICANTS
        else -> {
            applicants += m
            PartyResult.OK
        }
    }

    fun withdraw(m: M): Boolean = applicants.remove(m)

    /** Leader accepts applicant [index]; returns the applicant moved to the members, or null with [PartyResult]. */
    fun accept(index: Int): Pair<PartyResult, M?> {
        if (locked) return PartyResult.LOCKED to null
        val m = applicants.getOrNull(index) ?: return PartyResult.NO_SUCH_SLOT to null
        if (isFull) return PartyResult.PARTY_FULL to null
        applicants.removeAt(index)
        members += m
        return PartyResult.OK to m
    }

    /** Leader rejects applicant [index]: removed and blocked until [unblockAll]. */
    fun reject(index: Int): Pair<PartyResult, M?> {
        val m = applicants.getOrNull(index) ?: return PartyResult.NO_SUCH_SLOT to null
        applicants.removeAt(index)
        blocked += nameOf(m).lowercase()
        return PartyResult.OK to m
    }

    fun unblockAll(): Boolean {
        val had = blocked.isNotEmpty()
        blocked.clear()
        return had
    }

    /** Leader removes member [slot] >= 1 (slot 0, "Quit Party", is the leader leaving: see [PartyBook.leave]). */
    fun kick(slot: Int): Pair<PartyResult, M?> {
        if (locked) return PartyResult.LOCKED to null
        if (slot == 0) return PartyResult.NO_SUCH_SLOT to null
        val m = members.getOrNull(slot) ?: return PartyResult.NO_SUCH_SLOT to null
        members.removeAt(slot)
        return PartyResult.OK to m
    }

    /** [m] leaves; leadership passes to the next member (settings stay with the party). Returns true if [m] was a member. */
    fun leave(m: M): Boolean = members.remove(m)

    /** Moves member [slot] up (towards slot 1). The leader's slot 0 never changes this way. */
    fun moveUp(slot: Int): PartyResult {
        if (locked) return PartyResult.LOCKED
        if (slot !in members.indices) return PartyResult.NO_SUCH_SLOT
        if (slot <= 1) return PartyResult.LEADER_STAYS_FIRST
        members.add(slot - 1, members.removeAt(slot))
        return PartyResult.OK
    }

    /** Moves member [slot] down (towards the end). The leader's slot 0 never changes this way. */
    fun moveDown(slot: Int): PartyResult {
        if (locked) return PartyResult.LOCKED
        if (slot !in members.indices) return PartyResult.NO_SUCH_SLOT
        if (slot == 0) return PartyResult.LEADER_STAYS_FIRST
        if (slot >= members.size - 1) return PartyResult.NO_SUCH_SLOT
        members.add(slot + 1, members.removeAt(slot))
        return PartyResult.OK
    }

    val isEmpty: Boolean get() = members.isEmpty()
}

/**
 * The board: every listed party, in creation order, and each member's party / application. One instance per world
 * ([TfaPartyRegistry] wraps it with `Player`).
 */
class PartyBook<M : Any>(
    var limits: Limits = Limits(),
    private val nameOf: (M) -> String,
) {
    private var nextId = 1
    private val parties = LinkedHashMap<Int, PartyState<M>>()
    private val partyOf = HashMap<M, PartyState<M>>()
    private val applicationOf = HashMap<M, PartyState<M>>()

    /** Parties in board order (creation order). */
    val all: List<PartyState<M>> get() = parties.values.toList()

    val size: Int get() = parties.size

    operator fun get(id: Int): PartyState<M>? = parties[id]

    fun partyOf(m: M): PartyState<M>? = partyOf[m]

    fun applicationOf(m: M): PartyState<M>? = applicationOf[m]

    /** Creates a party led by [m]; any pending application of [m] is withdrawn. */
    fun create(m: M, tick: Long): Pair<PartyResult, PartyState<M>?> {
        if (partyOf[m] != null) return PartyResult.ALREADY_IN_PARTY to null
        if (parties.size >= limits.maxParties) return PartyResult.BOARD_FULL to null
        withdraw(m)
        val party = PartyState(nextId++, m, tick, limits, nameOf)
        parties[party.id] = party
        partyOf[m] = party
        return PartyResult.OK to party
    }

    /** [m] applies to [party]; a previous application elsewhere is withdrawn first. */
    fun apply(m: M, party: PartyState<M>): PartyResult {
        if (parties[party.id] !== party) return PartyResult.NO_SUCH_PARTY
        if (partyOf[m] != null) return PartyResult.ALREADY_IN_PARTY
        val previous = applicationOf[m]
        if (previous === party) return PartyResult.ALREADY_APPLIED
        val result = party.apply(m)
        if (result == PartyResult.OK) {
            previous?.withdraw(m)
            applicationOf[m] = party
        }
        return result
    }

    /** Withdraws [m]'s pending application; returns the party it was for. */
    fun withdraw(m: M): PartyState<M>? {
        val party = applicationOf.remove(m) ?: return null
        party.withdraw(m)
        return party
    }

    fun accept(party: PartyState<M>, index: Int): Pair<PartyResult, M?> {
        val applicant = party.applicants.getOrNull(index)
        if (applicant != null && partyOf[applicant] != null) {
            // Joined another party meanwhile: drop the stale application.
            party.applicants.removeAt(index)
            applicationOf.remove(applicant)
            return PartyResult.ALREADY_IN_PARTY to null
        }
        val (result, m) = party.accept(index)
        if (result == PartyResult.OK && m != null) {
            applicationOf.remove(m)
            partyOf[m] = party
        }
        return result to m
    }

    fun reject(party: PartyState<M>, index: Int): Pair<PartyResult, M?> {
        val (result, m) = party.reject(index)
        if (result == PartyResult.OK && m != null) applicationOf.remove(m)
        return result to m
    }

    /** Leader kicks member [slot] >= 1. Returns the removed member. */
    fun kick(party: PartyState<M>, slot: Int): Pair<PartyResult, M?> {
        val (result, m) = party.kick(slot)
        if (result == PartyResult.OK && m != null) partyOf.remove(m)
        return result to m
    }

    /**
     * What happened when a member left: the new leader (if the leader left), or [disbanded] with the applicants
     * that were released ("That party is no longer recruiting.").
     */
    data class Leave<M>(val party: PartyState<M>, val newLeader: M?, val disbanded: Boolean, val releasedApplicants: List<M>)

    /** [m] leaves their party. A new leader takes over, or the party is removed when it empties. */
    fun leave(m: M): Leave<M>? {
        val party = partyOf.remove(m) ?: return null
        val wasLeader = party.isLeader(m)
        party.leave(m)
        if (party.isEmpty) return Leave(party, null, disbanded = true, releasedApplicants = remove(party))
        return Leave(party, if (wasLeader) party.leader else null, disbanded = false, releasedApplicants = emptyList())
    }

    /** Disbands [party]: every member and applicant is released. Returns (members, applicants) as they were. */
    fun disband(party: PartyState<M>): Pair<List<M>, List<M>> {
        val members = party.members.toList()
        members.forEach { partyOf.remove(it) }
        party.members.clear()
        val applicants = remove(party)
        return members to applicants
    }

    /** Removes [party] from the board and releases its applicants (returned). */
    private fun remove(party: PartyState<M>): List<M> {
        parties.remove(party.id)
        val applicants = party.applicants.toList()
        applicants.forEach { if (applicationOf[it] === party) applicationOf.remove(it) }
        party.applicants.clear()
        return applicants
    }

    /** Releases everything [m] holds on the board (logout): application withdrawn, membership left. */
    fun forget(m: M): Leave<M>? {
        withdraw(m)
        return leave(m)
    }
}

/**
 * The viewer's role on 976, as script 10060 / 10053 read it [CACHE]: 2 leader (Disband, Unblock, kick and move
 * ops, editable size / completions), 1 member (Leave), 3 applicant (Withdraw), 4 greyed Apply, 0 Apply.
 */
object ViewerRole {
    const val APPLY = 0
    const val MEMBER = 1
    const val LEADER = 2
    const val APPLICANT = 3
    const val APPLY_DISABLED = 4

    fun <M : Any> of(book: PartyBook<M>, party: PartyState<M>, viewer: M): Int = when {
        party.isLeader(viewer) -> LEADER
        party.isMember(viewer) -> MEMBER
        party.isApplicant(viewer) -> APPLICANT
        book.partyOf(viewer) != null || party.isFull || party.locked || party.isBlocked(viewer) -> APPLY_DISABLED
        else -> APPLY
    }
}

/**
 * Sub ids of the 976:1 pause buttons [CACHE enum 6057 + scripts 10054/10056/10073]: 0-9 fixed buttons, then
 * `count(6057) + slot` kicks, `+ 8` move up, `+ 16` move down, `+ 24` accept, `+ 31` reject.
 */
sealed interface DetailsButton {
    data object Back : DetailsButton
    data object Refresh : DetailsButton
    data object Unblock : DetailsButton
    data object PreferredSize : DetailsButton
    data object PreferredCompletions : DetailsButton
    data object PreferredMode : DetailsButton
    data object Action : DetailsButton
    data object MembersTab : DetailsButton
    data object ApplicantsTab : DetailsButton
    data object SpectateToggle : DetailsButton
    data class Kick(val slot: Int) : DetailsButton
    data class MoveUp(val slot: Int) : DetailsButton
    data class MoveDown(val slot: Int) : DetailsButton
    data class Accept(val index: Int) : DetailsButton
    data class Reject(val index: Int) : DetailsButton

    companion object {
        /** Number of fixed buttons in enum 6057 (subs 0-9). */
        const val FIXED = 10
        const val LAST_SUB = 47

        fun of(sub: Int): DetailsButton? = when (sub) {
            0 -> Back
            1 -> Refresh
            2 -> Unblock
            3 -> PreferredSize
            4 -> PreferredCompletions
            5 -> PreferredMode
            6 -> Action
            7 -> MembersTab
            8 -> ApplicantsTab
            9 -> SpectateToggle
            in 10..17 -> Kick(sub - 10)
            in 18..25 -> MoveUp(sub - 18)
            in 26..33 -> MoveDown(sub - 26)
            in 34..40 -> Accept(sub - 34)
            in 41..47 -> Reject(sub - 41)
            else -> null
        }
    }
}

/** 977:1 pause subs [CACHE enum 6059]. */
enum class ListButton(val sub: Int) {
    REFRESH(0), MY_PARTY(1), FILTER(2);

    companion object {
        fun of(sub: Int): ListButton? = entries.firstOrNull { it.sub == sub }
    }
}

/** Per-viewer refresh rate limit (976 auto-refreshes every 500 client cycles; spec: 1 per 8 ticks). */
class RefreshLimiter(private val intervalTicks: () -> Int) {
    private val last = HashMap<Any, Long>()

    /** True if [key] may refresh at [tick] (and records it). */
    fun tryAcquire(key: Any, tick: Long): Boolean {
        val interval = intervalTicks()
        if (interval <= 0) return true
        val previous = last[key]
        if (previous != null && tick - previous < interval) return false
        last[key] = tick
        return true
    }

    fun forget(key: Any) {
        last.remove(key)
    }
}
