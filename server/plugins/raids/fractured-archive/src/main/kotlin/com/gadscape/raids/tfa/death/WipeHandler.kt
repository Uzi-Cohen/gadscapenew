package com.gadscape.raids.tfa.death

import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.FrameworkConfig
import com.gadscape.raids.tfa.raid.LeaveReason
import com.gadscape.raids.tfa.raid.RoomRuntime
import com.gadscape.raids.tfa.raid.TfaRaid
import com.gadscape.raids.tfa.raid.TfaRaidMember
import org.slf4j.LoggerFactory

/**
 * A wipe (spec §3.8, WU-15; "identical to Theatre of Blood ... normal gravestones" [DOC EYNTK]): every member who
 * entered the active encounter is dead.
 * 1. Each member who died in this encounter gets a **normal death** while still in the raid area: the stock
 *    `DeathMechanics` keeps / loses items by the server's normal rules, and the gravestone (if any) goes to the lobby
 *    at `tfa.json tiles.lobbyGravestone` (4123,5558) [HOUSE]. `framework.json death.wipeItemLoss`: `server_default`
 *    (GadScape's normal rule: hardcore accounts keep a gravestone, others die safe) or `gravestone` (always, as live).
 * 2. Gravestone fee: 25 % of the normal fee when the static death tile is in the TFA death zone and KC (varp 5894)
 *    is below 5 ([DeathFeeZone], script 1797 + proc 10182 [CACHE]); stored with [GravestoneFee].
 * 3. Every member, alive or dead, goes to the lobby arrival tile; the HUD closes and its vars clear (the lobby
 *    re-opens 975). Stats: Wipes +1 through `TfaRaidListener.onWipe`. No points for the room. The raid then ends
 *    (WIPED) and releases its instance.
 */
object WipeHandler {

    private val log = LoggerFactory.getLogger(WipeHandler::class.java)

    @JvmStatic
    fun wipe(raid: TfaRaid, rt: RoomRuntime) {
        val death = raid.framework.death
        for (m in raid.members) {
            if (m.left || !m.entered) continue
            val player = m.player
            if (player.isFinished) continue
            if (m.dead && m.diedIn == rt.room) normalDeath(raid, m, death)
            if (m.dead) TfaDeathHandler.restoreAppearance(m)
            m.dead = false
            m.diedIn = null
            player.reset()
            player.hitpoints = player.maxHitpoints
            player.sendMessage("<col=ef1020>Your party has fallen. You find yourself back in the lobby.")
            raid.leaveToLobby(player, LeaveReason.WIPE)
        }
        log.info("Fractured Archive raid {}: wiped in {}", raid.id, rt.room)
    }

    /** The stock death (items, gravestone in the lobby, HCIM handling) for a member who died in the wiped room. */
    private fun normalDeath(raid: TfaRaid, m: TfaRaidMember, death: FrameworkConfig.Death) {
        val player = m.player
        val area = raid.area
        area.safeOverride = when (death.wipeItemLoss) {
            FrameworkConfig.WipeItemLoss.GRAVESTONE -> false
            // GadScape's DeathMechanics.safe(): outside the wilderness only hardcore accounts lose items.
            FrameworkConfig.WipeItemLoss.SERVER_DEFAULT -> !player.gameMode.isHardcore
        }
        try {
            val unsafe = area.safeOverride == false
            player.deathMechanics.death(m.killer, null)
            if (unsafe) {
                val kc = player.varManager.getValue(TfaIds.Varp.KC)
                val pct = DeathFeeZone.feePercent(m.deathTile, kc, death.feeKcThreshold, death.feeReducedPct)
                GravestoneFee.set(player, pct)
            }
        } catch (e: Exception) {
            log.error("Fractured Archive: wipe death of {} failed", player.username, e)
        } finally {
            area.safeOverride = null
        }
    }
}
