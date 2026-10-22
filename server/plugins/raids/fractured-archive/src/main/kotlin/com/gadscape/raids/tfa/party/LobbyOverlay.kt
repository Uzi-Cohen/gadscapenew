package com.gadscape.raids.tfa.party

import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.TfaRaid
import com.zenyte.game.model.ui.InterfacePosition
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.player.Player

/**
 * Overlay 975 `tfa_lobby` and the party varps (spec §3.2, §3.3) [CACHE scripts 10045-10047]:
 * - header from varbit 16386 `tfa_client_partystatus`: 0 "No Party", 1 "Party", 2 "Step Inside Now!" (flashing);
 * - 975:5 = the eight member names joined with `<br>`, "-" for empty slots;
 * - varp 5913 `tfa_mycontroller` = -1 without a party (the varp default 0 would make 977's button read
 *   "My Party"); the party id otherwise (the client only compares it with -1).
 */
object LobbyOverlay {

    /** Static region 16470. */
    @JvmStatic
    fun isInLobby(location: Location): Boolean =
        (((location.x shr 6) shl 8) or (location.y shr 6)) == TfaIds.Region.LOBBY

    @JvmStatic
    fun isInLobby(player: Player): Boolean = isInLobby(player.location)

    /** Opens 975 and pushes the party state (lobby entry and login inside the lobby). */
    @JvmStatic
    fun open(player: Player) {
        player.interfaceHandler.sendInterface(InterfacePosition.OVERLAY, TfaIds.Interface.LOBBY)
        refresh(player)
    }

    /** Closes 975 (leaving the lobby). Varp 5913 / 16386 stay, so the raid HUD and the board keep working. */
    @JvmStatic
    fun close(player: Player) {
        if (player.interfaceHandler.isVisible(TfaIds.Interface.LOBBY)) player.interfaceHandler.closeInterface(TfaIds.Interface.LOBBY)
    }

    /**
     * Pushes varp 5913, varbit 16386 and (in the lobby) 975:5 for [player]. While [player]'s party is inside a raid
     * and [player] is not in the lobby, 16386 belongs to the raid HUD (it sets 1 on entry) and is left alone.
     */
    @JvmStatic
    fun refresh(player: Player) {
        val party = TfaPartyRegistry.partyOf(player)
        val inLobby = isInLobby(player)
        player.varManager.sendVar(TfaIds.Varp.MY_CONTROLLER, party?.id ?: -1)
        when {
            party == null -> player.varManager.sendBit(TfaIds.Varbit.PARTY_STATUS, 0)
            party.raid == null -> player.varManager.sendBit(TfaIds.Varbit.PARTY_STATUS, 1)
            // "Step Inside Now!" only while the raid still takes members (until the Guardian is touched).
            inLobby -> player.varManager.sendBit(TfaIds.Varbit.PARTY_STATUS, if ((party.raid as? TfaRaid)?.joinsOpen == true) 2 else 1)
        }
        if (inLobby) {
            player.packetDispatcher.sendComponentText(
                TfaIds.Interface.LOBBY, TfaIds.Component.LOBBY_NAMES,
                PartyRowCodec.overlayNames(party?.members?.map { it.name } ?: emptyList()),
            )
        }
    }
}
