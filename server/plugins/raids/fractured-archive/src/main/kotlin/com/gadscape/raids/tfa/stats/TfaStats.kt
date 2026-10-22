package com.gadscape.raids.tfa.stats

import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.entry.Miniquest
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.lobby.LobbyConfig
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.google.gson.Gson
import com.zenyte.game.world.entity.player.Player
import org.slf4j.LoggerFactory
import java.util.WeakHashMap

/**
 * Stats, KC and personal bests (spec §3.14): the API the raid (fw-core) and the encounters call. Every call records
 * the player's personal table (player save) and the server tables ([TfaRecordsStore]).
 *
 * Definitions [HOUSE unless tagged, spec §3.14]:
 * - Attempt (Overall): present at the Guardian touch ([raidStarted]); Attempt (room): in the raid when the encounter
 *   started ([roomStarted]).
 * - Completion: in the raid (alive or dead) when the encounter completed ([roomCompleted]); Overall: present at
 *   Ocellus's death ([raidCompleted]).
 * - Room time: encounter start to completion; Overall time: Guardian touch to Ocellus's death [INF].
 * - Party size: members at the Guardian touch (1-8); personal best = lowest time per (category, party size).
 * - KC = varp 5894 `total_completed_thefracturedarchive`, +1 for every member present at Ocellus's death, dead or
 *   alive [INF]; it feeds the CA / hiscores / KC-name enums client-side and the death-fee rule.
 * - Messages [HOUSE, ToB style]: "Your completed Fractured Archive count is: N.", "Room complete! Duration: x. Total:
 *   y.", "Fractured Archive completed! Duration: x (new personal best)". Jingle `tfa.json completionJingle` (886)
 *   [INF] at completion.
 */
object TfaStats {

    private val log = LoggerFactory.getLogger(TfaStats::class.java)

    /** Player attribute holding the personal [StatsTable] as JSON. */
    const val ATTRIBUTE = "tfa_stats"

    private val gson = Gson()
    private val cache = WeakHashMap<Player, StatsTable>()

    // ------------------------------------------------------------------------------------------------ queries

    @JvmStatic
    fun killCount(player: Player): Int = player.varManager.getValue(TfaIds.Varp.KC)

    /** Completions (Practice) over every room category. */
    @JvmStatic
    fun practiceCompletions(player: Player): Int =
        StatsCategory.entries.sumOf { personal(player).get(it, StatsRow.COMPLETIONS_PRACTICE) }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** The player's personal table (read from the save on first use). */
    @JvmStatic
    fun personal(player: Player): StatsTable = cache.getOrPut(player) {
        val json = player.attributes[ATTRIBUTE] as? String
        try {
            json?.let { gson.fromJson(it, StatsTable::class.java) }?.also(::repair) ?: StatsTable()
        } catch (e: Exception) {
            log.warn("Fractured Archive: unreadable personal stats of {}; starting fresh", player.username, e)
            StatsTable()
        }
    }

    // ------------------------------------------------------------------------------------------ raid lifecycle

    /** The Guardian is touched: Overall Attempts +1 for [members]. */
    @JvmStatic
    fun raidStarted(members: Collection<Player>) = count(members, StatsCategory.OVERALL, StatsRow.ATTEMPTS)

    /** [room] starts: its Attempts +1 for [members] (also in Practice Mode). */
    @JvmStatic
    fun roomStarted(room: TfaRoomId, members: Collection<Player>) = count(members, StatsCategory.of(room), StatsRow.ATTEMPTS)

    /**
     * [room] is complete: Completions (or Completions (Practice)) +1, the room time for [partySize], and the chat line
     * "Room complete! Duration: x. Total: y." ([overallTicks] since the Guardian touch; -1 hides "Total").
     */
    @JvmStatic
    @JvmOverloads
    fun roomCompleted(room: TfaRoomId, members: Collection<Player>, roomTicks: Int, overallTicks: Int, partySize: Int, practice: Boolean = false) {
        val category = StatsCategory.of(room)
        val row = if (practice) StatsRow.COMPLETIONS_PRACTICE else StatsRow.COMPLETIONS
        count(members, category, row)
        if (practice) return
        TfaRecordsStore.update { it.recordTime(category, partySize, roomTicks) }
        for (player in members) {
            val pb = edit(player) { it.recordTime(category, partySize, roomTicks) }
            val total = if (overallTicks >= 0) " Total: ${StatsText.duration(overallTicks)}." else ""
            player.sendMessage("Room complete! Duration: <col=ff0000>${StatsText.duration(roomTicks)}</col>.$total" +
                if (pb && announce()) " (new personal best)" else "")
        }
    }

    /** [player] died: Overall Deaths +1, and [room]'s Deaths +1 when known. */
    @JvmStatic
    fun death(player: Player, room: TfaRoomId?) {
        count(listOf(player), StatsCategory.OVERALL, StatsRow.DEATHS)
        if (room != null) count(listOf(player), StatsCategory.of(room), StatsRow.DEATHS)
    }

    /** The party wiped: Overall Wipes +1. */
    @JvmStatic
    fun wiped(members: Collection<Player>) = count(members, StatsCategory.OVERALL, StatsRow.WIPES)

    /** The raid ended without a completion or a wipe (left, logged out, watchdog): Overall Failures +1. */
    @JvmStatic
    fun failed(members: Collection<Player>) = count(members, StatsCategory.OVERALL, StatsRow.FAILURES)

    /**
     * Ocellus is dead: Overall Completions +1, KC +1, the overall time for [partySize], the miniquest's stage 6, the
     * completion jingle and the chat lines.
     */
    @JvmStatic
    fun raidCompleted(members: Collection<Player>, overallTicks: Int, partySize: Int) {
        count(members, StatsCategory.OVERALL, StatsRow.COMPLETIONS)
        TfaRecordsStore.update { it.recordTime(StatsCategory.OVERALL, partySize, overallTicks) }
        val jingle = TfaConfig.current.raid.completionJingle
        for (player in members) {
            val kc = killCount(player) + 1
            player.varManager.sendVar(TfaIds.Varp.KC, kc)
            val pb = edit(player) { it.recordTime(StatsCategory.OVERALL, partySize, overallTicks) }
            Miniquest.onOcellusKilled(player)
            if (jingle >= 0) player.music.playJingle(jingle)
            player.sendMessage("Your completed Fractured Archive count is: <col=ff0000>$kc</col>.")
            player.sendMessage("Fractured Archive completed! Duration: <col=ff0000>${StatsText.duration(overallTicks)}</col>" +
                if (pb && announce()) " (new personal best)" else "")
        }
    }

    // ------------------------------------------------------------------------------------------------ internals

    private fun count(members: Collection<Player>, category: StatsCategory, row: StatsRow) {
        if (members.isEmpty()) return
        for (player in members) edit(player) { it.add(category, row) }
        TfaRecordsStore.update { it.add(category, row, members.size.toLong()) }
    }

    /** Applies [change] to [player]'s table and saves it; returns what [change] returned. */
    private fun <T> edit(player: Player, change: (StatsTable) -> T): T {
        val table = personal(player)
        val result = change(table)
        player.addAttribute(ATTRIBUTE, gson.toJson(table))
        return result
    }

    private fun announce(): Boolean = TfaConfig.current[LobbyConfig].stats.announcePersonalBest

    @Suppress("SENSELESS_COMPARISON")
    private fun repair(t: StatsTable) {
        if (t.counters == null) t.counters = HashMap()
        if (t.best == null) t.best = HashMap()
    }
}
