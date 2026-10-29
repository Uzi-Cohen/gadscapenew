package com.gadscape.api.service.vote

import com.gadscape.game.item.CustomItemId
import com.google.common.eventbus.Subscribe
import com.zenyte.game.item.Item
import com.zenyte.game.util.Colour
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.plugins.events.LoginEvent


/**
 * Represents a collection of hooks that are related to voting.
 *
 * @author Stan van der Bend
 */
object VoteHooks {

    /**
     * Votes that came in while the player was offline wait on the vote screen (Claim on the site's row); tells the
     * player about them. See [com.gadscape.api.service.vote.pendingVotesToApplyOnNextLogin].
     */
    @Subscribe
    @JvmStatic
    fun onPendingVotes(event: LoginEvent) {
        val player = event.player
        val waiting = player.pendingVotesToApplyOnNextLogin.size
        if (waiting == 0) return
        player.sendMessage(Colour.RS_GREEN.wrap("You have $waiting vote${if (waiting == 1) "" else "s"} waiting to be claimed. Type ::vote to claim ${if (waiting == 1) "it" else "them"}."))
    }

    /**
     * Registers a login event hook that will remind the player to vote if they haven't done so today.
     */
    @Subscribe
    @JvmStatic
    fun onLogin(event: LoginEvent) {
        val player = event.player
        if (player.getBooleanAttribute("registered")
            && System.currentTimeMillis() >= player.lastVoteClaimTime) {
            player.dialogue {
                item(Item(CustomItemId.VOTE_TICKET), "${Colour.RS_RED.wrap("Vote For A Reward!")}<br>" +
                        "It seems you have not voted today, vote now for ${Colour.DARK_BLUE.wrap("Vote Points")}, " +
                        "blood tokens, a vote mystery box every 3 votes and a ${Colour.DARK_BLUE.wrap("+20%")} PvM drop rate for 20 minutes per vote.")
            }
        }
    }
}
