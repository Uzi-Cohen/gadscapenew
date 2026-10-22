@file:Suppress("unused")

package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.encounter.TfaEncounters
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.google.common.eventbus.Subscribe
import com.zenyte.plugins.events.ServerLaunchEvent

/**
 * WU-24 registration (ARCHITECTURE.md §3): the `dreadnoth.json` spec, the room's encounter factory, and the
 * `::tfa dreadnoth` static-map debug run. Plugin-scanned; order-independent with `TfaModule`.
 */
object DreadnothModule {

    @Subscribe
    @JvmStatic
    fun onServerLaunch(@Suppress("UNUSED_PARAMETER") event: ServerLaunchEvent) {
        register()
    }

    /** Idempotent; also used by tests and tools. */
    @JvmStatic
    fun register() {
        TfaConfig.register(DreadnothConfig)
        TfaEncounters.register(TfaRoomId.DREADNOTH) { context -> DreadnothRoom(context) }
        DreadnothDebug.install()
    }
}
