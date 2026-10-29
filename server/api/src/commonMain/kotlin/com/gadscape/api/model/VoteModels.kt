package com.gadscape.api.model

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Represents the status of a specific vote site for an [GameAccount].
 *
 * @author Stan van der Bend
 *
 * @param type                  the [VoteSite] of which this is the status.
 * @param url                   the url at which players can vote.
 * @param voteReward          the number of voting credits rewarded for voting at this site.
 * @param secondsTillCanVote    the number of seconds until the player can vote at this site again.
 */
@Serializable
data class VoteSiteStatus(
    val type: VoteSite,
    val url: String,
    val voteReward: Int,
    val secondsTillCanVote: Int
)

/**
 * The toplists players can vote on, in the vote screen's order (::vote). Only add new sites at the end: the API's
 * vote_sites table keeps them by ordinal.
 *
 * @param fancyName     the site's name as players see it.
 * @param defaultPoints the vote points a vote on the site is worth when the vote itself doesn't say (website votes
 *                      carry their own amount, the API's from its vote_sites table).
 */
@Serializable
enum class VoteSite(val fancyName: String, val defaultPoints: Int) {
    RUNELOCUS("RuneLocus", 5),
    RSPS_LIST("RSPS-List", 4),
    TOPG("TopG", 2),
    TOP100ARENA("Top100Arena", 2),
    RUNELIST("RuneList", 2),
    MOPARSCAPE("MoparScape", 2);

    /** The site's name folded for matching names from elsewhere (website rewards): lowercase letters and digits only. */
    val key: String get() = fold(fancyName)

    companion object {
        fun fold(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

        /** The site [name] means ("RuneLocus", "rsps_list", "Top 100 Arena"...), or null. */
        fun find(name: String?): VoteSite? {
            if (name.isNullOrBlank()) return null
            val folded = fold(name)
            return entries.firstOrNull { it.key == folded || fold(it.name) == folded }
        }
    }
}

/**
 * Represents a vote by a player that may be claimed or unclaimed.
 *
 * @author Stan van der Bend
 */
@Serializable
data class Vote(
    val id: Int,
    val user: User,
    val userIp: String,
    val siteType: VoteSite,
    val voteTime: LocalDateTime,
    val claimed: Boolean,
    val votePointReward: Int
)
