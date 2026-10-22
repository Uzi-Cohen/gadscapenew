package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.encounter.TfaEncounters
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.google.common.eventbus.Subscribe
import com.zenyte.plugins.events.ServerLaunchEvent

/**
 * Registers the Dagon'hai trio room (WU-25, spec §4.6) at server launch: its config spec (`dagonhai.json`), its
 * encounter factory for [TfaRoomId.DAGONHAI], and the `::tfa dagonhai ...` debug command ([DagonhaiDebug]). Found by
 * the plugin scanner.
 */
object DagonhaiModule {

    @Subscribe
    @JvmStatic
    fun onServerLaunch(@Suppress("UNUSED_PARAMETER") event: ServerLaunchEvent) {
        register()
    }

    /** Idempotent registration (also used by tests and tools). */
    @JvmStatic
    fun register() {
        TfaConfig.register(DagonhaiConfig)
        TfaEncounters.register(TfaRoomId.DAGONHAI) { ctx -> DagonhaiRoom(ctx) }
        DagonhaiDebug.install()
    }
}
