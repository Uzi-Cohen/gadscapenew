@file:Suppress("unused")

package com.gadscape.raids.tfa

import com.gadscape.game.world.PlayerEvent
import com.gadscape.game.world.hook
import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.debug.TfaCommands
import com.gadscape.raids.tfa.debug.TfaDebug
import com.gadscape.raids.tfa.encounter.TfaEncounters
import com.gadscape.raids.tfa.ids.RemapTable
import com.gadscape.raids.tfa.ids.TfaIds
import com.google.common.eventbus.Subscribe
import com.zenyte.plugins.events.ServerLaunchEvent
import org.slf4j.LoggerFactory

/**
 * Bootstrap of The Fractured Archive (Raids 4) module, `:plugins:raids:fractured-archive`.
 *
 * Discovered by the plugin scanner (`:app:runPluginScanner` writes `data/plugins.dat`; the module is on the app
 * runtime classpath through `runtimeOnly(projects.plugins.raids.fracturedArchive)`): the static
 * [onServerLaunch] subscriber runs once the world thread exists. It
 * 1. resolves every id through `data/raids/tfa/remap.tsv` ([RemapTable], [TfaIds]) and logs the result;
 * 2. loads the config ([TfaConfig.current]) and logs problems and the keys still holding house values;
 * 3. installs `::tfa` and `::tfareload` ([TfaCommands]) and the godmode backstop.
 *
 * Other units do **not** edit this file. Each adds its own `object XModule` with a `@Subscribe @JvmStatic
 * fun onServerLaunch(event: ServerLaunchEvent)` that registers its config spec ([TfaConfig.register]), its
 * encounter factory ([TfaEncounters.register]), its services ([TfaServices]) and its debug slots
 * ([TfaCommands.bind]). Every registry here is order-independent.
 *
 * Still to come from other units (spec §2.1): IF_SCRIPT_TRIGGER crc handlers (needs the WU-02 router; crcs in
 * `TfaIds.Crc`), loc/NPC option handlers (plugin-scanned `ObjectAction` / `NPCPlugin` classes in their packages).
 */
object TfaModule {

    private val log = LoggerFactory.getLogger(TfaModule::class.java)

    @Volatile
    var booted: Boolean = false
        private set

    @Subscribe
    @JvmStatic
    fun onServerLaunch(event: ServerLaunchEvent) {
        boot()
        event.worldThread.hook<PlayerEvent.Process> { e ->
            if (TfaDebug.isGodmode(e.player)) TfaDebug.restore(e.player)
        }
        installRaidFramework()
    }

    /**
     * Raid side (fw-core: WU-12..WU-18, see `raids4-impl/FRAMEWORK-core.md`): the raid registry, contribution, the boss
     * HP HUD, the framework config sections, the 974 triggers, the music unlock varp and the `::tfa start / skipto
     * <room> / raid` commands.
     */
    private fun installRaidFramework() {
        try {
            TfaConfig.register(com.gadscape.raids.tfa.points.PointsConfig)
            TfaConfig.register(com.gadscape.raids.tfa.raid.FrameworkConfig)
            TfaServices.raids = com.gadscape.raids.tfa.raid.TfaRaids
            TfaServices.contribution = com.gadscape.raids.tfa.points.ContributionService
            TfaServices.bossHud = com.gadscape.raids.tfa.hud.BossHpHud
            com.gadscape.raids.tfa.spectate.SpectatorService.registerTriggers()
            com.gadscape.raids.tfa.hud.MusicUnlock.install()
            com.gadscape.raids.tfa.raid.RaidCommands.install()
        } catch (e: Throwable) {
            log.error("Fractured Archive: installing the raid framework failed", e)
        }
    }

    /** Resolves ids, loads config and installs the commands. Safe to call more than once. */
    @JvmStatic
    fun boot() {
        if (booted) return
        synchronized(this) {
            if (booted) return
            try {
                val ids = TfaIds.all()
                val remap = RemapTable.active
                log.info("Fractured Archive: {} ids named ({} remapped by {}).", ids.size, ids.count { it.remapped }, remap.source)
            } catch (e: Throwable) {
                log.error("Fractured Archive: resolving ids failed", e)
            }
            try {
                TfaConfig.current // loads and logs
            } catch (e: Throwable) {
                log.error("Fractured Archive: loading config failed", e)
            }
            TfaCommands.install()
            booted = true
            log.info("Fractured Archive module ready; encounters registered so far: {}.",
                TfaEncounters.registered().joinToString().ifEmpty { "none" })
        }
    }
}
