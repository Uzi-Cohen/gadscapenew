package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.debug.TfaCommands
import com.gadscape.raids.tfa.encounter.TfaEncounters
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.google.common.eventbus.Subscribe
import com.zenyte.plugins.events.ServerLaunchEvent

/**
 * Registers the Ocellus room (WU-27/28) at server launch (ARCHITECTURE.md §3): its config spec (`ocellus.json`),
 * its encounter factory for [TfaRoomId.OCELLUS], the `::tfa ocellus` debug sub-command and the Bridge's one-time
 * setup. Discovered by the plugin scanner; order-independent with `TfaModule`.
 */
object OcellusModule {

    @Subscribe
    @JvmStatic
    fun onServerLaunch(@Suppress("UNUSED_PARAMETER") event: ServerLaunchEvent) {
        register()
    }

    @Volatile
    private var registered = false

    /** Idempotent (tests and tools may call it). */
    @JvmStatic
    fun register() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            TfaConfig.register(OcellusConfig)
            TfaEncounters.register(TfaRoomId.OCELLUS) { ctx -> OcellusRoom(ctx) }
            TfaCommands.register("ocellus", OcellusDebug.USAGE, "Ocellus room tools: a standalone run on the static map, phase skips, HP, state") { p, args ->
                OcellusDebug.command(p, args)
            }
            Bridge.install()
            registered = true
        }
    }
}
