@file:Suppress("unused")

package com.gadscape.raids.tfa.rooms.ezekiel

import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.debug.TfaCommands
import com.gadscape.raids.tfa.encounter.TfaEncounters
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.google.common.eventbus.Subscribe
import com.zenyte.plugins.events.ServerLaunchEvent

/**
 * WU-21 registration (ARCHITECTURE.md §3): the `ezekiel.json` spec, the encounter factory for room 2 and the
 * `::tfa ezekiel` debug sub-command. Found by the plugin scanner; order-independent with `TfaModule`.
 */
object EzekielModule {

    @Subscribe
    @JvmStatic
    fun onServerLaunch(event: ServerLaunchEvent) {
        TfaConfig.register(EzekielConfig)
        TfaEncounters.register(TfaRoomId.EZEKIEL) { ctx -> EzekielRoom(ctx) }
        TfaCommands.register("ezekiel", EzekielDebug.USAGE, EzekielDebug.HELP) { player, args -> EzekielDebug.execute(player, args) }
    }
}
