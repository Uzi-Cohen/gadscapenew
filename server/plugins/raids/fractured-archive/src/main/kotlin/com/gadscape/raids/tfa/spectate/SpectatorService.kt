package com.gadscape.raids.tfa.spectate

import com.gadscape.network.rsprot.CamTarget
import com.gadscape.network.rsprot.IfScriptTriggerRouter
import com.gadscape.raids.tfa.death.TfaDeathHandler
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.party.TfaParty
import com.gadscape.raids.tfa.raid.RaidGeometry
import com.gadscape.raids.tfa.raid.RaidPhase
import com.gadscape.raids.tfa.raid.TfaRaid
import com.gadscape.raids.tfa.raid.TfaRaids
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.game.world.entity.player.dialogue.options
import org.slf4j.LoggerFactory

/**
 * Spectating (spec §3.13, WU-18): the Strategy board flow and the HUD triggers.
 *
 * - [strategyBoard]: the dialog of the Strategy board 63083 "Spectate" (the lobby's loc handler calls it):
 *   "Spectate recent party" (the most recently started raid) or "Spectate specific party" (a participant's name; you
 *   must be on that participant's friends list [DOC]). Refused when the leader disabled spectating (976 sub 9) or
 *   the raid has 100 spectators [DOC].
 * - IF_SCRIPT_TRIGGER **2481737999** on 974:5 `(int slot, int)`: an orb click (slot) or "Spectate Room" (-1), for
 *   dead party members (spectate mode 1) and external spectators (mode 2) [CACHE script 10040].
 * - IF_SCRIPT_TRIGGER **1373305897** on 974:1 `(int)`: 2 = Leave (external spectators); 1 = Skip Phase (Practice
 *   Mode, flag off: ignored here; WU-31 may re-register the crc) [CACHE script 10035].
 */
object SpectatorService {

    private val log = LoggerFactory.getLogger(SpectatorService::class.java)

    /** Registers the two 974 triggers (once, at launch). */
    @JvmStatic
    fun registerTriggers() {
        IfScriptTriggerRouter.register(TfaIds.Crc.SPECTATE_SLOT, "int int", TfaIds.Interface.HUD, TfaIds.Component.HUD_ORBS) { player, trigger ->
            onOrbClick(player, trigger.int(0))
        }
        IfScriptTriggerRouter.register(TfaIds.Crc.HUD_ACTION, "int", TfaIds.Interface.HUD, TfaIds.Component.HUD_ACTIONS) { player, trigger ->
            when (trigger.int(0)) {
                2 -> leave(player)
                1 -> player.sendMessage("Practice Mode is not available yet.")
            }
        }
    }

    /** An orb / "Spectate Room" click: dead party members and external spectators switch the camera. */
    @JvmStatic
    fun onOrbClick(player: Player, slot: Int) {
        val ghostRaid = TfaRaids.all().firstOrNull { it.spectators.isSpectating(player) }
        if (ghostRaid != null) {
            ghostRaid.spectators.view(player, slot)
            return
        }
        val raid = TfaRaids.raidOf(player) ?: return
        val me = raid.memberOf(player) ?: return
        if (!me.dead) return
        val target = raid.members.getOrNull(slot)
        if (slot < 0 || target == null || !target.alive) {
            me.spectatedSlot = -1
            val room = me.diedIn ?: TfaRoomId.GUARDIAN
            CamTarget.tile(player, raid.translate(RaidGeometry.spectateCentre(room)))
            restoreInventory(player)
        } else {
            me.spectatedSlot = slot
            CamTarget.player(player, target.player)
            SpectateInventory.open(player, raid, target, pings = true)
        }
        raid.hud.markDirty()
    }

    private fun restoreInventory(player: Player) {
        if (player.interfaceHandler.isVisible(TfaIds.Interface.SPECTATE_INV)) {
            com.zenyte.game.GameInterface.INVENTORY_TAB.open(player)
        }
    }

    /** HUD "Leave" for an external spectator. */
    @JvmStatic
    fun leave(player: Player) {
        TfaRaids.all().firstOrNull { it.spectators.isSpectating(player) }?.spectators?.leave(player)
    }

    // ======================================================================================== Strategy board

    /** The Strategy board 63083 "Spectate" dialog (spec §3.2 / §3.13). */
    @JvmStatic
    fun strategyBoard(player: Player) {
        if (TfaRaids.raidOf(player) != null) {
            player.sendMessage("You are already in a raid.")
            return
        }
        player.dialogue {
            options("Spectate a raid?") {
                "Spectate recent party." { spectateRecent(player) }
                "Spectate specific party." {
                    player.sendInputName("Whose party would you like to spectate?") { name -> spectateSpecific(player, name) }
                }
                "Cancel." {}
            }
        }
    }

    /** "Spectate recent party": the most recently started raid that is still running. */
    @JvmStatic
    fun spectateRecent(player: Player): Boolean {
        val raid = TfaRaids.mostRecent()
        if (raid == null) {
            player.sendMessage("There are no raids to spectate right now.")
            return false
        }
        return join(player, raid)
    }

    /** "Spectate specific party": [name] must be a participant who has the viewer on their friends list [DOC]. */
    @JvmStatic
    fun spectateSpecific(player: Player, name: String): Boolean {
        val key = name.trim().replace(' ', '_').lowercase()
        val raid = TfaRaids.all().firstOrNull { r -> r.phase != RaidPhase.ENDED && r.members.any { !it.left && it.username.replace(' ', '_') == key } }
        if (raid == null) {
            player.sendMessage("That player is not in a raid.")
            return false
        }
        if (raid.framework.spectate.specificNeedsFriend) {
            val participant = raid.members.first { !it.left && it.username.replace(' ', '_') == key }.player
            if (!participant.socialManager.containsFriend(player.username)) {
                player.sendMessage("You must be on ${participant.name}'s friends list to spectate their party.")
                return false
            }
        }
        return join(player, raid)
    }

    private fun join(player: Player, raid: TfaRaid): Boolean {
        val party = raid.party as? TfaParty
        if (party != null && party.spectatingDisabled) {
            player.sendMessage("That party has disabled spectating.")
            return false
        }
        val ok = raid.spectators.add(player)
        if (ok) log.debug("{} spectates raid {}", player.username, raid.id)
        return ok
    }

    /** Dead members watching [raid] re-follow after the room view changes (death handler helper). */
    @JvmStatic
    fun refollow(raid: TfaRaid) {
        for (m in raid.members) if (m.dead && !m.left) TfaDeathHandler.follow(raid, m, m.diedIn)
    }
}
