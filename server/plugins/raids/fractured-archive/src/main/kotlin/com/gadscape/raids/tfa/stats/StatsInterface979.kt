package com.gadscape.raids.tfa.stats

import com.gadscape.network.rsprot.IfScriptTriggerRouter
import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.model.ui.InterfacePosition
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.`object`.ObjectAction
import com.zenyte.game.world.`object`.WorldObject

/**
 * What the raid tells the Alliance Log about [player]'s current raid (fw-core installs it as [StatsInterface979.raid]).
 * The default treats a player with an active encounter context as "in an active raid" and has no party panel.
 */
interface RaidStatsSource {

    /** True while [player] is a member of a raid that has not ended (979 adds Party Stats and the Current column). */
    fun isInActiveRaid(player: Player): Boolean = TfaServices.raids.contextOf(player) != null

    /** The Party Stats tab (10131 / 10136 / 10137 / 10128 / 10139), or null if unknown. */
    fun partyStats(player: Player): PartyStatsSnapshot? = null

    /**
     * The Current column of [row] in [category] (time rows: for [partySize]); null = blank. A time value with
     * `frozen = false` makes the client count up from it.
     */
    fun current(player: Player, category: StatsCategory, row: StatsRow, partySize: Int): CurrentValue? = null

    companion object {
        @JvmField
        val DEFAULT: RaidStatsSource = object : RaidStatsSource {
            override fun toString() = "RaidStatsSource.DEFAULT"
        }
    }
}

data class CurrentValue(val value: Int, val frozen: Boolean = true)

/** One member line of the Party Stats table (script 10136). */
data class MemberStats(val name: String, val damageDone: Int, val damageTaken: Int, val deaths: Int, val mvpCount: Int)

/** One room split of 10139 (state 0/1 not started, 4 finished, other running) [CACHE script 10139/10141]. */
data class RoomSplit(val category: StatsCategory, val ticks: Int)

/** Everything the Party Stats tab shows. [overallState]: 0/1 not started, 4 finished, anything else running. */
data class PartyStatsSnapshot(
    val partySize: Int,
    val members: List<MemberStats>,
    val mvpName: String,
    val overallTicks: Int,
    val overallState: Int,
    val rooms: List<RoomSplit>,
)

/**
 * Interface 979 "Alliance Log" (spec §3.14) [CACHE scripts 10102-10147, dbtables 264/265].
 * - Open, then RUNCLIENTSCRIPT 10102(inActiveRaid): 0 lobby (All Stats only), 1 in a raid (Party Stats and Current).
 * - The client asks for a category with IF_SCRIPT_TRIGGER 2359217665 on 979:34 (dbrow category); the answer is one
 *   10125(category, row, partySize, current, currentFrozen, personal, global, globalToday, worldToday) per row (time
 *   rows: 8 lines, sizes 1..8, the first prints the header), then 10124(). Blank cells are `Int.MIN_VALUE`; a time of
 *   0 prints "-".
 * - In a raid the Party Stats tab is filled right after opening ([PartyStatsPanel]).
 * - 10110 ("You are currently viewing your party stats.") and 10115 ("You need to be in an active raid ...") are the
 *   two server-run messages [CACHE: no client caller].
 */
object StatsInterface979 {

    /** In-raid data; installed by fw-core (WU-12/WU-16). */
    @JvmStatic
    @Volatile
    var raid: RaidStatsSource = RaidStatsSource.DEFAULT

    /** Registers the category request trigger (at launch). */
    @JvmStatic
    fun install() {
        IfScriptTriggerRouter.register(
            TfaIds.Crc.STATS_CATEGORY, "dbrow",
            interfaceId = TfaIds.Interface.STATS, componentId = TfaIds.Component.STATS_CATEGORY,
        ) { player, trigger -> sendCategory(player, trigger.int(0)) }
    }

    /** Alliance records 62773 / Record Keeper: lobby mode ([inActiveRaid] = false) unless the player is in a raid. */
    @JvmStatic
    fun open(player: Player, inActiveRaid: Boolean) {
        val inRaid = inActiveRaid && raid.isInActiveRaid(player)
        player.interfaceHandler.sendInterface(InterfacePosition.CENTRAL, TfaIds.Interface.STATS)
        player.packetDispatcher.sendClientScript(TfaIds.Script.STATS_OPEN, if (inRaid) 1 else 0)
        if (inRaid) raid.partyStats(player)?.let { PartyStatsPanel.send(player, it) }
    }

    /**
     * An in-raid source (the Merit scrolls 62774 / 62775): Party Stats when in a raid, 10110 when 979 already shows
     * them, 10115 when [player] is not in an active raid.
     */
    @JvmStatic
    fun openFromRaid(player: Player) {
        if (!raid.isInActiveRaid(player)) {
            player.packetDispatcher.sendClientScript(TfaIds.Script.STATS_MSG_NOT_IN_RAID)
            return
        }
        if (player.interfaceHandler.isVisible(TfaIds.Interface.STATS)) {
            player.packetDispatcher.sendClientScript(TfaIds.Script.STATS_MSG_VIEWING_PARTY)
            return
        }
        open(player, inActiveRaid = true)
    }

    /** The rows of one category (IF_SCRIPT_TRIGGER 2359217665). */
    @JvmStatic
    fun sendCategory(player: Player, categoryDbrow: Int) {
        val category = StatsCategory.byDbrow(categoryDbrow) ?: return
        val inRaid = raid.isInActiveRaid(player)
        val personal = TfaStats.personal(player)
        val records = TfaRecordsStore.records
        for (line in StatsRows.lines(category, personal, records.allTime, records.today) { row, size ->
            if (inRaid) raid.current(player, category, row, size) else null
        }) {
            player.packetDispatcher.sendClientScript(
                TfaIds.Script.STATS_ROW,
                category.dbrow, line.row.dbrow, line.partySize, line.current, line.currentFrozen,
                line.personal, line.global, line.globalToday, line.worldToday,
            )
        }
        player.packetDispatcher.sendClientScript(TfaIds.Script.STATS_ROWS_DONE)
    }
}

/** The 10125 lines of one category (pure, unit-tested). */
object StatsRows {

    const val BLANK = Int.MIN_VALUE

    data class Line(
        val row: StatsRow, val partySize: Int, val current: Int, val currentFrozen: Int,
        val personal: Int, val global: Int, val globalToday: Int, val worldToday: Int,
    )

    fun lines(
        category: StatsCategory,
        personal: StatsTable,
        allTime: StatsTable,
        today: StatsTable,
        current: (StatsRow, Int) -> CurrentValue?,
    ): List<Line> {
        val out = ArrayList<Line>()
        for (row in category.rows) {
            if (row.isTime) {
                for (size in 1..StatsTable.MAX_PARTY) {
                    val cur = current(row, size)
                    out += Line(row, size, cur?.value ?: BLANK, if (cur == null || cur.frozen) 1 else 0,
                        personal.bestTime(category, size), allTime.bestTime(category, size),
                        today.bestTime(category, size), today.bestTime(category, size))
                }
            } else {
                val cur = current(row, 0)
                val todayCount = clamp(today.get(category, row))
                out += Line(row, 0, cur?.value ?: BLANK, 1, clamp(personal.get(category, row)), clamp(allTime.get(category, row)),
                    todayCount, todayCount)
            }
        }
        return out
    }

    private fun clamp(v: Long): Int = v.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
}

/**
 * The Party Stats tab [CACHE scripts 10131-10141]: 10131(partySize), 10136(row, done, taken, deaths, mvpCount, name)
 * per member, 10137(totals), 10128(mvpName, "" = "Unavailable"), 10139(overallTicks, state, room names, room ticks,
 * room icons) (arrays need E2, done).
 */
object PartyStatsPanel {

    @JvmStatic
    fun send(player: Player, s: PartyStatsSnapshot) {
        val d = player.packetDispatcher
        d.sendClientScript(TfaIds.Script.PARTY_STATS_TABLE, s.partySize.coerceIn(1, 8))
        s.members.take(8).forEachIndexed { i, m ->
            d.sendClientScript(TfaIds.Script.PARTY_STATS_MEMBER, i, m.damageDone, m.damageTaken, m.deaths, m.mvpCount, m.name)
        }
        d.sendClientScript(TfaIds.Script.PARTY_STATS_TOTAL,
            s.members.sumOf { it.damageDone }, s.members.sumOf { it.damageTaken }, s.members.sumOf { it.deaths })
        d.sendClientScript(TfaIds.Script.PARTY_STATS_MVP, s.mvpName)
        d.sendClientScript(TfaIds.Script.PARTY_STATS_SPLITS,
            s.overallTicks, s.overallState,
            s.rooms.map { it.category.room?.displayName ?: "Overall" }.toTypedArray(),
            s.rooms.map { it.ticks }.toIntArray(),
            s.rooms.map { it.category.icon }.toIntArray())
    }
}

/** "Read" on the Merit scrolls: 62774 (in-raid camp, spawned by fw-core) and 62775 (vault, WU-29) [INF]. Plugin-scanned. */
class MeritScrollAction : ObjectAction {
    override fun handleObjectAction(player: Player, `object`: WorldObject, name: String, optionId: Int, option: String) {
        if (option.equals("Read", ignoreCase = true)) StatsInterface979.openFromRaid(player)
    }

    override fun getObjects(): Array<Any> = arrayOf(TfaIds.Loc.MERIT_SCROLL_CAMP, TfaIds.Loc.MERIT_SCROLL_VAULT)
}
