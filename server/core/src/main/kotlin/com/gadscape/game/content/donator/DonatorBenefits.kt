package com.gadscape.game.content.donator

import com.zenyte.game.content.achievementdiary.Diary
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.privilege.MemberRank

/**
 * The Donator Benefits page (::donator, ::benefits, the noticeboard): every rank with its price and what it
 * adds, like Roat Pkz's Donator Benefits interface, in the journal scroll
 * (dark text: the scroll is parchment).
 */
object DonatorBenefits {

    private const val HEADING = "800000"
    private const val VALUE = "000080"

    private fun col(text: String, hex: String) = "<col=$hex>$text</col>"

    /** What each rank adds on top of the ranks below it (the numbers that change are listed for every rank). */
    private fun unlocks(rank: MemberRank): List<String> = when (rank) {
        MemberRank.SUPER -> listOf("::bank (outside the Wilderness, instances and combat)", "Super Gad Donator Island (::die)",
            "The Donator Squire's home teleports")
        MemberRank.EXTREME -> listOf("Free God Wars instances (no instance portal fee)")
        MemberRank.LEGENDARY -> listOf("::spec and ::hp (health and prayer) every ${rank.specCooldownSeconds} seconds",
            "::unskull every ${rank.unskullCooldownSeconds} seconds", "Legendary Gad Donator Island (::rdi)")
        MemberRank.ROYAL -> listOf("No time limit in the AFK area", "Royal Gad Donator Island (::ldi)")
        MemberRank.DIVINE -> listOf("::settele 1-5 saves a spot, ::tele 1-5 takes you back", "Divine Gad Donator Island (::udi)")
        else -> emptyList()
    }

    private fun numbers(rank: MemberRank): List<String> {
        val lines = ArrayList<String>()
        lines += "${rank.dropRatePercent}% drop rate, ${rank.freeDonatorPoints} donator points with the rank"
        lines += "${rank.presets} presets, " + if (rank.yellDelay > 0) "::yell every ${rank.yellDelay * 6 / 10} seconds" else "::yell without a wait"
        lines += "${rank.slayerPointsPercent}% more Slayer points; cancel a task ${rank.cancelTaskCost}, block ${rank.blockTaskCost} points"
        lines += "${rank.coxPurplePercent}% better Chambers of Xeric purple chance"
        lines += "${rank.bountyTargetSkips} Bounty Hunter skips per 30 min, new target in ${rank.bountyTargetCooldownSeconds}s"
        if (rank.hasRestoreCommands()) lines += "::spec / ::hp every ${rank.specCooldownSeconds}s, ::unskull every ${rank.unskullCooldownSeconds}s"
        return lines
    }

    @JvmStatic
    fun lines(player: Player): List<String> {
        val lines = ArrayList<String>()
        val rank = player.memberRank
        val total = player.totalDonatedCents
        lines += if (rank.isDonator) "You are a ${rank.crown().crownTag}${col(rank.toString(), VALUE)}."
        else "You don't have a donator rank yet."
        lines += "Total donated: ${col(DonatorRanks.formatUsd(total), VALUE)}"
        val next = if (rank == MemberRank.AMASCUT) null else rank.next()
        if (next != null)
            lines += "Next: ${next.crown().crownTag}$next at ${DonatorRanks.formatUsd(next.priceCents.toLong())} " +
                    "(${DonatorRanks.formatUsd(maxOf(0L, next.priceCents - total))} to go)"
        lines += ""
        lines += "Buy a rank in the store (::donate) and type ::claim in game."
        lines += "Everything you donate counts: when your total reaches a"
        lines += "rank's price you get that rank. Ranks never go down."
        lines += ""
        lines += col("Every rank", HEADING)
        lines += "Your rank's icon before your name"
        lines += "::yell, the donator zone (::dzone, ::di), donator toggles"
        for (r in MemberRank.DONATOR_RANKS) {
            lines += ""
            lines += "${r.crown().crownTag}${col(r.toString(), HEADING)} - ${DonatorRanks.formatUsd(r.priceCents.toLong())}" +
                    if (r == rank) " ${col("(you)", VALUE)}" else ""
            if (r != MemberRank.DONATOR) lines += "Everything above, and:"
            unlocks(r).forEach { lines += "- $it" }
            numbers(r).forEach { lines += "- $it" }
        }
        lines += ""
        lines += col("Also", HEADING)
        lines += "GadScape's own donator perks stay, by rank: bigger coal bag,"
        lines += "more cannonballs, farming, Zahur, pet insurance and more."
        return lines
    }

    @JvmStatic
    fun open(player: Player) {
        if (player.isLocked) return
        Diary.sendJournal(player, "Gad Donator Benefits", lines(player))
    }
}
