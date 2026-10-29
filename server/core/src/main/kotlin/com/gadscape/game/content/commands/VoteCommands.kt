package com.gadscape.game.content.commands

import com.gadscape.game.content.vote.VoteLottery
import com.gadscape.game.model.ui.vote.VoteScreen
import com.google.common.eventbus.Subscribe
import com.zenyte.game.util.Colour
import com.zenyte.game.world.entity.player.GameCommands.Command
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.privilege.PlayerPrivilege
import com.zenyte.plugins.events.ServerLaunchEvent
import mgi.types.config.items.ItemDefinitions

/**
 * Voting commands. Registered at server launch, after GameCommands' own, so ::vote here replaces the old one (which
 * only opened the website): it opens the vote screen, or the website's vote page when the cache has no vote screen.
 */
object VoteCommands {

    @Subscribe
    @JvmStatic
    fun onServerLaunch(event: ServerLaunchEvent) {
        register()
    }

    fun register() {
        Command(PlayerPrivilege.PLAYER, arrayOf("vote", "voting"), "Opens the vote screen: vote, claim your votes and see your vote perks.") { p: Player, _: Array<String?> ->
            VoteScreen.open(p)
        }

        Command(PlayerPrivilege.PLAYER, "votelottery", "Shows the weekly vote lottery. Admins: ::votelottery prize <itemId> <amount>") { p: Player, args: Array<String?> ->
            if (args.isNotEmpty() && args[0].equals("prize", ignoreCase = true)) {
                if (!p.privilege.eligibleTo(PlayerPrivilege.ADMINISTRATOR)) {
                    p.sendMessage("Only administrators can set the vote lottery prize.")
                    return@Command
                }
                val itemId = args.getOrNull(1)?.toIntOrNull()
                val amount = args.getOrNull(2)?.toIntOrNull() ?: 1
                val def = itemId?.let { ItemDefinitions.get(it) }
                if (itemId == null || def == null || amount <= 0) {
                    p.sendMessage("Usage: ::votelottery prize <itemId> <amount> (an item the cache has, amount at least 1).")
                    return@Command
                }
                VoteLottery.setPrize(itemId, amount)
                p.sendMessage(Colour.RS_GREEN.wrap("The vote lottery prize is now $amount x ${def.name}."))
                return@Command
            }
            VoteLottery.describe(p).forEach { p.sendMessage(it) }
        }
    }
}
