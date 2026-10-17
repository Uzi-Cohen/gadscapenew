package com.gadscape.game.content.tournament.spectating

import com.gadscape.game.world.PlayerEvent
import com.gadscape.game.world.WorldEventListener

object TournamentSpectatorUpdateHook : WorldEventListener<PlayerEvent.Update> {

    override fun on(event: PlayerEvent.Update) {
        val player = event.player
        if (player.temporaryAttributes.containsKey("tournament_spectating"))
            TournamentViewerInterface.refreshSpectator(player)
    }
}
