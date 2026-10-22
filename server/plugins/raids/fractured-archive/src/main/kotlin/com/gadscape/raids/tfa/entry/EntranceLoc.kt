package com.gadscape.raids.tfa.entry

import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.StaticTile
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.`object`.ObjectAction
import com.zenyte.game.world.`object`.WorldObject

/**
 * The raid entrance (spec §3.1) [CACHE]: loc 62542 `luc2_raid_entry_multi` at (4050,4567,0), rot 3, 3x3, a multiloc on
 * varbit 16354: 0 → 53638 "Blocked cavity" (no ops), 1 → 62543 "Opening", op1 "Crawl-through". The click arrives
 * with the base id 62542 (the client resolves the multiloc), so both ids are bound.
 *
 * Crawl-through: the first time (miniquest below 2) the intro scene plays ([IntroScene]); then the player lands on
 * `tfa.json tiles.lobbyArrival` (4126,5508) [HOUSE: just inside the exit Opening 63009]. No crawl animation is in the
 * evidence, so the move is a plain teleport.
 */
class TfaEntranceAction : ObjectAction {

    override fun handleObjectAction(player: Player, `object`: WorldObject, name: String, optionId: Int, option: String) {
        if (option.equals("Crawl-through", ignoreCase = true)) EntranceFlow.crawlThrough(player)
    }

    override fun getObjects(): Array<Any> = arrayOf(TfaIds.Loc.ENTRANCE_MULTI, TfaIds.Loc.ENTRANCE_OPEN)
}

/** Entry flows shared by the entrance, the folio and the debug commands. */
object EntranceFlow {

    @JvmStatic
    fun crawlThrough(player: Player) {
        if (!TfaEntryGate.isUnlocked(player) && !TfaEntryGate.check(player)) {
            TfaEntryGate.refuse(player)
            return
        }
        Miniquest.onStarted(player)
        if (IntroScene.shouldPlay(player)) IntroScene.play(player) { toLobby(player) }
        else toLobby(player)
    }

    /** Puts [player] on the lobby arrival tile. The lobby area's enter() does the rest (975, 5913, music). */
    @JvmStatic
    fun toLobby(player: Player) {
        move(player, TfaConfig.current.raid.tiles.lobbyArrival)
    }

    /** Lobby exit Opening 63009 → the temple, south of the entrance (4051,4564,0) [HOUSE]. */
    @JvmStatic
    fun toTemple(player: Player) {
        move(player, TfaConfig.current.raid.tiles.templeExit)
    }

    private fun move(player: Player, tile: StaticTile) {
        player.stopAll()
        player.interfaceHandler.closeInterfaces()
        player.setLocation(Location(tile.x, tile.y, tile.plane))
    }
}
