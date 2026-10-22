package com.gadscape.game.content.tournament.spectating

import com.gadscape.game.content.tournament.TournamentPair
import com.gadscape.util.weakMutableSetOf
import com.zenyte.game.world.entity.player.Player

class TournamentSpectatorController(
    private val pair: TournamentPair
) {
    private val spectators = weakMutableSetOf<Player>()

    fun onFightOver() {
        spectators.forEach { it.interfaceHandler?.closeInterfaces() }
    }

    fun remove(player: Player) {
        TODO("Not yet implemented")
    }

    fun add(player: Player) {
        TODO("Not yet implemented")
    }
}
