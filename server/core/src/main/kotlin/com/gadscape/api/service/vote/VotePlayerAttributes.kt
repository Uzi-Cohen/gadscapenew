package com.gadscape.api.service.vote

import com.gadscape.api.model.Vote
import com.zenyte.game.world.entity.persistentAttribute
import com.zenyte.game.world.entity.player.Player

var Player.lastVoteClaimTime: Long by persistentAttribute("vote_claim_time", 0L)
var Player.totalVoteCredits: Int by persistentAttribute("vote_points", 0)

// Votes received while the player was offline can't be applied immediately - the
// Player object returned by LoginManager's lightweight load() is a bare
// deserialization with no packetDispatcher/BoonManager/etc wired up, and nothing
// saves it afterwards anyway. Queue here instead; VoteHooks.onLogin applies them
// for real once the player is fully online again.
internal var Player.pendingVotesToApplyOnNextLogin: MutableList<Vote> by persistentAttribute("pending_votes_to_apply_on_next_login", mutableListOf())
