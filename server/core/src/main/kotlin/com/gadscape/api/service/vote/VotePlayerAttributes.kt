package com.gadscape.api.service.vote

import com.gadscape.api.model.Vote
import com.gadscape.api.model.VoteSite
import com.zenyte.game.world.entity.persistentAttribute
import com.zenyte.game.world.entity.player.Player

var Player.lastVoteClaimTime: Long by persistentAttribute("vote_claim_time", 0L)
var Player.totalVoteCredits: Int by persistentAttribute("vote_points", 0)

// Votes from the old vote API wait here until the player claims them on the vote screen (::vote, the site's Claim).
// Votes received while the player was offline are added through LoginManager's lightweight load() and saved; the
// rewards are only ever given to the player when they are genuinely online (VotePlayerHandler.claim).
internal var Player.pendingVotesToApplyOnNextLogin: MutableList<Vote> by persistentAttribute("pending_votes_to_apply_on_next_login", mutableListOf())

/** Votes claimed (each site's vote once): the vote screen's total, every 3rd one gives a vote mystery box. */
var Player.votesClaimed: Int by persistentAttribute("votes_claimed", 0)
/** When the last vote was claimed (Xamphur lets voters of the last 12 hours fight). */
var Player.lastVoteClaimedAt: Long by persistentAttribute("vote_last_claimed_at", 0L)
/** Days in a row with at least one claimed vote, and the last of them (epoch day, server time). */
var Player.voteStreakDays: Int by persistentAttribute("vote_streak_days", 0)
var Player.voteStreakLastDay: Long by persistentAttribute("vote_streak_last_day", 0L)
/** The +20% PvM drop rate from votes lasts until then. */
var Player.voteDropBoostUntil: Long by persistentAttribute("vote_drop_boost_until", 0L)
/** Set once the character's vote shards were turned into vote tickets. */
var Player.voteShardsConverted: Boolean by persistentAttribute("vote_shards_converted", false)

/** When the player last voted on [site] (vote received), for the screen's 12-hour wait when the API doesn't say. */
fun Player.lastVoteAt(site: VoteSite): Long = getNumericAttribute("vote_last_${site.name.lowercase()}").toLong()

fun Player.setLastVoteAt(site: VoteSite, time: Long) = addAttribute("vote_last_${site.name.lowercase()}", time)

/** Website votes on [site] the website told this server about (VOTE_RECEIVED) that the player hasn't claimed yet. */
fun Player.websiteVotesWaiting(site: VoteSite): Int = getNumericAttribute("vote_waiting_${site.name.lowercase()}").toInt()

fun Player.setWebsiteVotesWaiting(site: VoteSite, votes: Int) = addAttribute("vote_waiting_${site.name.lowercase()}", votes.coerceAtLeast(0))
