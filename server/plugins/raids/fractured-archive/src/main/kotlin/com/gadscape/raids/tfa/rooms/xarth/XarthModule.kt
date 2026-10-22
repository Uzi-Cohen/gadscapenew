@file:Suppress("unused")

package com.gadscape.raids.tfa.rooms.xarth

import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.encounter.TfaEncounters
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.google.common.eventbus.Subscribe
import com.zenyte.plugins.events.ServerLaunchEvent

/**
 * WU-26 registration (ARCHITECTURE.md §3): the `xarth.json` spec, the room's encounter factory, and the `::tfa xarth`
 * static-map debug run. Plugin-scanned; order-independent with `TfaModule`.
 */
object XarthModule {

    @Subscribe
    @JvmStatic
    fun onServerLaunch(@Suppress("UNUSED_PARAMETER") event: ServerLaunchEvent) {
        register()
    }

    /** Idempotent; also used by tests and tools. */
    @JvmStatic
    fun register() {
        TfaConfig.register(XarthConfig)
        TfaEncounters.register(TfaRoomId.XARTH) { context -> XarthRoom(context) }
        XarthDebug.install()
    }
}
