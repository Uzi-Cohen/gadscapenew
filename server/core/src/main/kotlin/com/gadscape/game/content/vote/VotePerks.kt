package com.gadscape.game.content.vote

import com.gadscape.api.service.vote.lastVoteClaimedAt
import com.gadscape.api.service.vote.voteDropBoostUntil
import com.gadscape.api.service.vote.voteStreakDays
import com.gadscape.api.service.vote.voteStreakLastDay
import com.gadscape.api.service.vote.votesClaimed
import com.gadscape.game.content.currency.BloodTokens
import com.gadscape.game.item.CustomItemId
import com.zenyte.game.content.skills.afk.AfkSkilling
import com.zenyte.game.item.Item
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.SkillConstants
import com.zenyte.game.world.entity.player.Skills
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * What each claimed vote gives besides the vote points, tomes and clue rolls (VotePlayerHandler.giveVoteBenefits),
 * and the perks the vote screen lists:
 *
 * - [BLOOD_TOKENS] blood tokens.
 * - Every [VOTES_PER_BOX]rd claimed vote: a vote mystery box.
 * - One vote lottery entry for this week (VoteLottery).
 * - Skilling streak: +1% skilling XP per day in a row with at least one claimed vote, up to [MAX_STREAK_PERCENT]%;
 *   a day without one starts it again ([skillingXpMultiplier], Skills.addXp, not for combat skills or Slayer).
 * - +[DROP_BOOST_PERCENT]% PvM drop rate for 20 minutes, each vote adds 20 minutes ([dropRateBonus], Player.getDropRateBonus).
 * - AFK skilling time: 6 hours, +3 per donator rank (MemberRank id), at most 24.
 * - Xamphur: only players who claimed a vote in the last 12 hours can damage it ([mayFightXamphur]).
 */
object VotePerks {

    const val BLOOD_TOKENS = 15
    const val VOTES_PER_BOX = 3
    const val MAX_STREAK_PERCENT = 50
    const val DROP_BOOST_PERCENT = 20
    private val DROP_BOOST_PER_VOTE = TimeUnit.MINUTES.toMillis(20)
    private val XAMPHUR_WINDOW = TimeUnit.HOURS.toMillis(12)
    private const val AFK_HOURS = 6
    private const val AFK_HOURS_PER_RANK = 3
    private const val AFK_HOURS_MAX = 24

    private fun today(): Long = LocalDate.now(ZoneId.systemDefault()).toEpochDay()

    /** Gives one claimed vote's perks (game thread). The lines say what was given, for the vote's dialogue. */
    fun onVoteClaimed(player: Player): List<String> {
        val lines = ArrayList<String>()
        val now = System.currentTimeMillis()
        player.inventory.addOrDrop(Item(BloodTokens.TOKEN, BLOOD_TOKENS))
        lines += "$BLOOD_TOKENS blood tokens"

        player.votesClaimed += 1
        player.lastVoteClaimedAt = now
        if (player.votesClaimed % VOTES_PER_BOX == 0) {
            player.inventory.addOrDrop(Item(CustomItemId.VOTE_MYSTERY_BOX, 1))
            lines += "a vote mystery box"
        }

        VoteLottery.addEntry(player.username)

        val today = today()
        player.voteStreakDays = when (player.voteStreakLastDay) {
            today -> player.voteStreakDays.coerceAtLeast(1)
            today - 1 -> player.voteStreakDays + 1
            else -> 1
        }
        player.voteStreakLastDay = today

        player.voteDropBoostUntil = maxOf(player.voteDropBoostUntil, now) + DROP_BOOST_PER_VOTE

        val hours = afkHoursPerVote(player)
        AfkSkilling.addAfkTime(player, TimeUnit.HOURS.toMillis(hours.toLong()))
        lines += "$hours hours of AFK skilling"
        return lines
    }

    fun afkHoursPerVote(player: Player): Int =
        (AFK_HOURS + AFK_HOURS_PER_RANK * player.memberRank.id).coerceAtMost(AFK_HOURS_MAX)

    /** The streak's days, 0 once a day was missed. */
    fun streakDays(player: Player): Int = if (player.voteStreakLastDay >= today() - 1) player.voteStreakDays else 0

    fun streakPercent(player: Player): Int = streakDays(player).coerceAtMost(MAX_STREAK_PERCENT)

    /** The skilling streak's XP multiplier for [skill] (1.0 for combat skills and Slayer). */
    @JvmStatic
    fun skillingXpMultiplier(player: Player, skill: Int): Double {
        if (Skills.isCombatSkill(skill) || skill == SkillConstants.SLAYER) return 1.0
        val percent = streakPercent(player)
        return if (percent <= 0) 1.0 else 1.0 + percent / 100.0
    }

    fun dropBoostLeft(player: Player): Long = (player.voteDropBoostUntil - System.currentTimeMillis()).coerceAtLeast(0)

    /** The vote drop rate boost as a fraction (0.20 while it lasts), added to Player.getDropRateBonus. */
    @JvmStatic
    fun dropRateBonus(player: Player): Double = if (dropBoostLeft(player) > 0) DROP_BOOST_PERCENT / 100.0 else 0.0

    fun afkTimeLeft(player: Player): Long = (AfkSkilling.getAfkTime(player) - System.currentTimeMillis()).coerceAtLeast(0)

    /** Claimed votes until the next vote mystery box. */
    fun votesUntilBox(player: Player): Int = VOTES_PER_BOX - player.votesClaimed % VOTES_PER_BOX

    @JvmStatic
    fun mayFightXamphur(player: Player): Boolean = System.currentTimeMillis() - player.lastVoteClaimedAt < XAMPHUR_WINDOW

    /** "7h 41m" (or "41m"). */
    fun formatTime(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis + 59_999)
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
