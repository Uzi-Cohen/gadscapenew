package com.gadscape.raids.tfa.lobby

import com.gadscape.raids.tfa.entry.Miniquest
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.party.LobbyOverlay
import com.zenyte.game.model.music.Music
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.region.PolygonRegionArea
import com.zenyte.game.world.region.RSPolygon
import com.zenyte.game.world.region.area.plugins.LoginPlugin
import org.slf4j.LoggerFactory

/**
 * The lobby, static region 16470 (x 4096-4159, y 5504-5567) (spec §3.2) [DOC: safe, not instanced]. Plugin-scanned
 * (`RegionArea`).
 * - entering (and logging in inside): overlay 975, varp 5913 (-1 without a party), varbit 16386, 975:5 names
 *   ([LobbyOverlay]); the lobby track "Into the Archive" (midi 891, dbrow 17001, music varp index 27 bit 22) is
 *   played and unlocked [DOC + CACHE]; miniquest stage 2; the wheelbarrow varbit is refreshed (WU-29).
 * - leaving: 975 closes. Party membership is kept (the leader's start check lists who is not in the lobby).
 */
class TfaLobbyArea : PolygonRegionArea(), LoginPlugin {

    override fun polygons(): Array<RSPolygon> = arrayOf(RSPolygon(TfaIds.Region.LOBBY))

    override fun enter(player: Player) = LobbyArea.onEnter(player)

    override fun leave(player: Player, logout: Boolean) = LobbyArea.onLeave(player, logout)

    override fun login(player: Player) = LobbyArea.onEnter(player)

    override fun name(): String = "The Fractured Archive lobby"
}

object LobbyArea {

    private val log = LoggerFactory.getLogger(LobbyArea::class.java)

    /** Music track of dbrow 17001 / midi 891 [CACHE]. */
    private const val LOBBY_TRACK = "Into the Archive"

    @JvmStatic
    fun onEnter(player: Player) {
        try {
            LobbyOverlay.open(player)
            Miniquest.onLobbyEntered(player)
            LobbyServices.loot.refresh(player)
            playMusic(player)
        } catch (e: Exception) {
            log.error("Fractured Archive lobby entry failed for {}", player.username, e)
        }
    }

    @JvmStatic
    fun onLeave(player: Player, logout: Boolean) {
        if (!logout) LobbyOverlay.close(player)
        AscensionRocks.onLeaveLobby(player)
    }

    private fun playMusic(player: Player) {
        val music = Music.map[LOBBY_TRACK]
        if (music != null) player.music.unlock(music, true)
        else player.packetDispatcher.sendMusic(TfaIds.Midi.LOBBY)
    }
}
