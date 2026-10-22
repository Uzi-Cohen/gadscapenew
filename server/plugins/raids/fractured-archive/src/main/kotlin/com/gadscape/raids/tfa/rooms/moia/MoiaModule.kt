package com.gadscape.raids.tfa.rooms.moia

import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.encounter.TfaEncounters
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.google.common.eventbus.Subscribe
import com.zenyte.plugins.events.ServerLaunchEvent

/**
 * Registers the Moia room (WU-23, spec §4.4) at server launch: its config spec (`moia.json`), its encounter factory
 * for [TfaRoomId.MOIA], and the `::tfa moia ...` debug command ([MoiaDebug]). Found by the plugin scanner.
 */
object MoiaModule {

    @Subscribe
    @JvmStatic
    fun onServerLaunch(@Suppress("UNUSED_PARAMETER") event: ServerLaunchEvent) {
        register()
    }

    /** Idempotent registration (also used by tests and tools). */
    @JvmStatic
    fun register() {
        TfaConfig.register(MoiaConfig)
        TfaEncounters.register(TfaRoomId.MOIA) { ctx -> MoiaRoom(ctx) }
        MoiaDebug.install()
    }
}
