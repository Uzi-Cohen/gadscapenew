package com.gadscape.raids.tfa.raid

import java.util.EnumSet

/** The raid lifecycle states of spec §2.3 (LOBBY_PARTY lives on the Grouping Board, before a raid exists). */
enum class RaidPhase {
    /** Instance allocated, nexus built; members may still enter (until the Guardian is touched). */
    CREATED,

    /** An encounter is running ([TfaRaid.activeRoom]). */
    ROOM_ACTIVE,

    /** A room is complete; the party regroups in the nexus until the Navigator's "Yes". */
    INTERMISSION,

    /** A section's doors are open; the first Enter commits the side. */
    SECTION_OPEN,

    /** Section 3 done: Ivy and the Ocellus door wait in the nexus. */
    NEXUS_FINAL,

    /** Ocellus is dead: The Blade, the vault, the Rift. */
    VAULT,

    /** Over ([TfaRaid.endReason]); the instance is released 2 t after the last member leaves. */
    ENDED,
}

/** How a raid ended (stats rows Completions / Wipes / Failures, spec §2.3, §3.14). */
enum class RaidEndReason { COMPLETED, WIPED, FAILED }

/**
 * The path through the three sections (spec §2.3, §3.5): which side each section committed, and which rooms are done.
 * Varbit 16417 `tfa_client_current_path` holds bit (section − 1) = side ([Side.pathBit]) [INF].
 *
 * Pure: unit-tested in `TfaRouteTest`.
 */
class TfaRoute {

    private val sides = arrayOfNulls<Side>(3)
    private val done: EnumSet<TfaRoomId> = EnumSet.noneOf(TfaRoomId::class.java)

    fun side(section: Int): Side? = sides[section - 1]

    /** The committed room of [section], or null. */
    fun room(section: Int): TfaRoomId? = side(section)?.let { TfaRoomId.of(section, it) }

    /** Commits [section] to [side]; false if it was already committed (to either side). */
    fun commit(section: Int, side: Side): Boolean {
        require(section in 1..3) { "section $section" }
        if (sides[section - 1] != null) return false
        sides[section - 1] = side
        return true
    }

    fun isCommitted(section: Int): Boolean = sides[section - 1] != null

    /** Value of varbit 16417: one bit per committed section (0 west, 1 east). */
    val pathValue: Int
        get() {
            var v = 0
            for (i in 0..2) if (sides[i] == Side.EAST) v = v or (1 shl i)
            return v
        }

    fun markComplete(room: TfaRoomId) {
        done += room
    }

    fun isComplete(room: TfaRoomId): Boolean = room in done

    val completedRooms: Set<TfaRoomId> get() = done.clone()

    /** Rooms finished so far (the Guardian is room 1, the section-2 room is room 3, Ocellus room 5). */
    val roomsDone: Int get() = done.size

    /**
     * The section whose doors open next, or null: none before the Guardian is dead, and none once section 3 is done
     * (then the Ocellus door is next).
     */
    fun nextSection(): Int? {
        if (TfaRoomId.GUARDIAN !in done) return null
        return (1..3).firstOrNull { s -> done.none { it.section == s } }
    }

    /** True when the Guardian and all three sections are done (NEXUS_FINAL). */
    val sectionsDone: Boolean get() = TfaRoomId.GUARDIAN in done && (1..3).all { s -> done.any { it.section == s } }

    /** Route order of the rooms done (stats splits, dumps). */
    fun doneInOrder(): List<TfaRoomId> = TfaRoomId.ROUTE_ORDER.filter { it in done }

    fun describe(): String =
        (1..3).joinToString(", ") { s -> "s$s=${side(s)?.name?.lowercase() ?: "-"}" } + "; done ${doneInOrder().joinToString { it.configKey }}"
}
