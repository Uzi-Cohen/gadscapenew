@file:Suppress("unused")

package com.gadscape.raids.tfa.rooms.guardian

import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.debug.TfaCommands
import com.gadscape.raids.tfa.encounter.TfaEncounters
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.google.common.eventbus.Subscribe
import com.zenyte.game.world.entity.npc.actions.NPCPlugin
import com.zenyte.plugins.events.ServerLaunchEvent

/**
 * WU-20 registration (ARCHITECTURE.md §3): the `guardian.json` spec, the encounter factory for room 1 and the
 * `::tfa guardian` debug sub-command. Found by the plugin scanner; order-independent with `TfaModule`.
 */
object ArchiveGuardianModule {

    @Subscribe
    @JvmStatic
    fun onServerLaunch(event: ServerLaunchEvent) {
        TfaConfig.register(GuardianConfig)
        TfaEncounters.register(TfaRoomId.GUARDIAN) { ctx -> ArchiveGuardianRoom(ctx) }
        TfaCommands.register("guardian", GuardianDebug.USAGE, GuardianDebug.HELP) { player, args -> GuardianDebug.execute(player, args) }
    }
}

/** op1 "Touch" on the Inert Guardian 16657 (§2.3, §4.1): starts room 1. */
class InertGuardianTouch : NPCPlugin() {

    override fun handle() {
        bind("Touch") { player, npc ->
            val inert = npc as? InertGuardianNpc
            if (inert == null || !inert.dormant) {
                player.sendMessage("Nothing interesting happens.")
                return@bind
            }
            inert.room.onTouch(player)
        }
    }

    override fun getNPCs(): IntArray = intArrayOf(TfaIds.Npc.GUARDIAN_INERT)
}
