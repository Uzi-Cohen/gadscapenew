package com.gadscape.raids.tfa.stats

import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.TfaRoomId

/**
 * Alliance Log categories (dbtable 264) in enum 6066 tab order [CACHE dbrows 17133-17141]: each lists its rows
 * (Overall: Attempts, Completions, Deaths, Wipes, Failures, Time; rooms: Attempts, Completions, Completions
 * (Practice), Deaths, Time) and its 25x25 icon (`icon_boss_25x25` frames 72-80).
 */
enum class StatsCategory(val tab: Int, val room: TfaRoomId?) {
    OVERALL(0, null),
    GUARDIAN(1, TfaRoomId.GUARDIAN),
    DREADNOTH(2, TfaRoomId.DREADNOTH),
    XARTH(3, TfaRoomId.XARTH),
    DAGONHAI(4, TfaRoomId.DAGONHAI),
    EZEKIEL(5, TfaRoomId.EZEKIEL),
    MOIA(6, TfaRoomId.MOIA),
    BORK(7, TfaRoomId.BORK),
    OCELLUS(8, TfaRoomId.OCELLUS),
    ;

    val dbrow: Int get() = room?.statsDbrow ?: TfaIds.Dbrow.STATS_OVERALL

    /** The rows the category's dbrow lists, in order. */
    val rows: List<StatsRow>
        get() = if (this == OVERALL) {
            listOf(StatsRow.ATTEMPTS, StatsRow.COMPLETIONS, StatsRow.DEATHS, StatsRow.WIPES, StatsRow.FAILURES, StatsRow.TIME)
        } else {
            listOf(StatsRow.ATTEMPTS, StatsRow.COMPLETIONS, StatsRow.COMPLETIONS_PRACTICE, StatsRow.DEATHS, StatsRow.TIME)
        }

    /** Graphic id of `icon_boss_25x25,(72 + tab)`. */
    val icon: Int get() = TfaIds.Sprite.STATS_CATEGORY_ICONS[tab]

    companion object {
        @JvmStatic
        fun of(room: TfaRoomId): StatsCategory = entries.first { it.room == room }

        @JvmStatic
        fun byDbrow(dbrow: Int): StatsCategory? = entries.firstOrNull { it.dbrow == dbrow }
    }
}

/** Rows of dbtable 265 [CACHE dbrows 17142-17148]; [TIME] is per party size 1-8 (`row_per_party_size`). */
enum class StatsRow {
    ATTEMPTS, COMPLETIONS, COMPLETIONS_PRACTICE, DEATHS, WIPES, FAILURES, TIME;

    val dbrow: Int
        get() = when (this) {
            ATTEMPTS -> TfaIds.Dbrow.STATS_ROW_ATTEMPTS
            COMPLETIONS -> TfaIds.Dbrow.STATS_ROW_COMPLETIONS
            COMPLETIONS_PRACTICE -> TfaIds.Dbrow.STATS_ROW_COMPLETIONS_PRACTICE
            DEATHS -> TfaIds.Dbrow.STATS_ROW_DEATHS
            WIPES -> TfaIds.Dbrow.STATS_ROW_WIPES
            FAILURES -> TfaIds.Dbrow.STATS_ROW_FAILURES
            TIME -> TfaIds.Dbrow.STATS_ROW_TIME
        }

    val isTime: Boolean get() = this == TIME
}

/**
 * Counters and best times of one owner (a player, the server all-time, or the server today). Plain fields so Gson
 * stores it as is: `counters[category][row]` and `best[category][size - 1]` (ticks, 0 = no record).
 */
class StatsTable {
    var counters: MutableMap<String, MutableMap<String, Long>> = HashMap()
    var best: MutableMap<String, IntArray> = HashMap()

    fun get(category: StatsCategory, row: StatsRow): Long = counters[category.name]?.get(row.name) ?: 0L

    fun add(category: StatsCategory, row: StatsRow, amount: Long = 1) {
        val map = counters.getOrPut(category.name) { HashMap() }
        map[row.name] = (map[row.name] ?: 0L) + amount
    }

    /** Best (lowest) time for [partySize] 1..8, 0 when none. */
    fun bestTime(category: StatsCategory, partySize: Int): Int =
        best[category.name]?.getOrNull(partySize.coerceIn(1, MAX_PARTY) - 1) ?: 0

    /** Records [ticks] for [partySize]; returns true if it is a new best. */
    fun recordTime(category: StatsCategory, partySize: Int, ticks: Int): Boolean {
        if (ticks <= 0) return false
        val arr = best.getOrPut(category.name) { IntArray(MAX_PARTY) }.let { if (it.size < MAX_PARTY) it.copyOf(MAX_PARTY) else it }
        best[category.name] = arr
        val i = partySize.coerceIn(1, MAX_PARTY) - 1
        if (arr[i] == 0 || ticks < arr[i]) {
            arr[i] = ticks
            return true
        }
        return false
    }

    fun isEmpty(): Boolean = counters.isEmpty() && best.isEmpty()

    companion object {
        const val MAX_PARTY = 8
    }
}

/** The server's records: all time, and today (UTC) for the "Global Today" / "World Today" columns. */
class GlobalRecords {
    var allTime: StatsTable = StatsTable()
    var today: StatsTable = StatsTable()
    /** `yyyy-MM-dd` (UTC) of [today]. */
    var todayDate: String = ""

    /** Clears [today] when [date] is a new day; returns true if it rolled over. */
    fun rollTo(date: String): Boolean {
        if (todayDate == date) return false
        today = StatsTable()
        todayDate = date
        return true
    }
}

/** Formatting shared by chat messages (ticks are 0.6 s). */
object StatsText {

    /** "m:ss.cc" or "h:mm:ss.cc" for [ticks] game ticks (0.6 s each). */
    @JvmStatic
    fun duration(ticks: Int): String {
        val centis = ticks.toLong() * 60
        val totalSeconds = centis / 100
        val cs = centis % 100
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d.%02d".format(h, m, s, cs) else "%d:%02d.%02d".format(m, s, cs)
    }
}
