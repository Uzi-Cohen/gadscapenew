package com.gadscape.game.world.entity.player.status

import com.google.common.eventbus.Subscribe
import com.zenyte.game.world.entity.player.Player
import com.zenyte.plugins.events.PlayerResetEvent

/**
 * The Raids 4 player statuses as a group (spec §2.7 E10/E11, WU-05): [PlayerBurn], [Weary], [PlayerBleed] and any
 * [VirusOutbreak] infection.
 *
 * They clear on death: `Player.reset()` posts [PlayerResetEvent] after a normal death, and the raid's own death
 * handler (deaths that the area handles itself) calls [clear].
 */
object PlayerStatuses {

    /** Clears burn, weary, bleed and every virus infection of [player]. */
    @JvmStatic
    fun clear(player: Player) {
        PlayerBurn.clear(player)
        Weary.clear(player)
        PlayerBleed.clear(player)
        VirusOutbreak.clearEverywhere(player)
    }

    @Subscribe
    @JvmStatic
    fun onPlayerReset(event: PlayerResetEvent) {
        clear(event.player)
    }
}
