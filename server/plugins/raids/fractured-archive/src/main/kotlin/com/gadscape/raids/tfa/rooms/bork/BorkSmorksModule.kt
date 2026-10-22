@file:Suppress("unused")

package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.encounter.TfaEncounters
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.google.common.eventbus.Subscribe
import com.zenyte.plugins.events.ServerLaunchEvent

/**
 * WU-22 registration (ARCHITECTURE.md §3): the `bork_smorks.json` spec, the room's encounter factory and the
 * `::tfa bork` static-map debug run. Plugin-scanned; order-independent with `TfaModule`.
 */
object BorkSmorksModule {

    @Subscribe
    @JvmStatic
    fun onServerLaunch(@Suppress("UNUSED_PARAMETER") event: ServerLaunchEvent) {
        register()
    }

    /** Idempotent; also used by tests and tools. */
    @JvmStatic
    fun register() {
        TfaConfig.register(BorkSmorksConfig)
        TfaEncounters.register(TfaRoomId.BORK) { context -> BorkSmorksRoom(context) }
        BorkDebug.install()
    }
}
