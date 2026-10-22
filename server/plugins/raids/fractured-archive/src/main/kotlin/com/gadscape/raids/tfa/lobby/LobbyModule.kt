@file:Suppress("unused")

package com.gadscape.raids.tfa.lobby

import com.gadscape.game.world.WorldEvent
import com.gadscape.game.world.hook
import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.debug.TfaCommands
import com.gadscape.raids.tfa.entry.EntranceFlow
import com.gadscape.raids.tfa.entry.Folio
import com.gadscape.raids.tfa.entry.IntroScene
import com.gadscape.raids.tfa.entry.MessengerService
import com.gadscape.raids.tfa.entry.Miniquest
import com.gadscape.raids.tfa.entry.TfaEntryGate
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.party.PartyListInterface
import com.gadscape.raids.tfa.party.TfaPartyRegistry
import com.gadscape.raids.tfa.raid.TfaRaidEvents
import com.gadscape.raids.tfa.stats.RaidStatsFromRaid
import com.gadscape.raids.tfa.stats.StatsInterface979
import com.gadscape.raids.tfa.stats.TfaStatsListener
import com.gadscape.raids.tfa.stats.TfaRecordsStore
import com.gadscape.raids.tfa.stats.TfaStats
import com.google.common.eventbus.Subscribe
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.VarManager
import com.zenyte.plugins.events.LoginEvent
import com.zenyte.plugins.events.LogoutEvent
import com.zenyte.plugins.events.ServerLaunchEvent
import com.zenyte.plugins.events.ServerShutdownEvent
import org.slf4j.LoggerFactory

/**
 * Boot of the fw-lobby unit (WU-09 entry, WU-10 lobby, WU-11 party, WU-19 stats). Plugin-scanned static subscribers:
 * - launch: `lobby.json` spec, persistent vars, the 979 trigger, the lobby tick, `::tfa party` and helpers;
 * - login: entry gate (waived → unlocked; attunement → checked, messenger);
 * - logout: board, intro scene, messenger, sprite;
 * - shutdown: the records file is flushed.
 */
object TfaLobbyModule {

    private val log = LoggerFactory.getLogger(TfaLobbyModule::class.java)

    private var started = false
    private var ticks = 0L

    @Subscribe
    @JvmStatic
    fun onServerLaunch(event: ServerLaunchEvent) {
        TfaConfig.register(LobbyConfig)
        registerPersistentVars()
        try {
            StatsInterface979.install()
        } catch (e: Throwable) {
            log.error("Fractured Archive: registering the Alliance Log trigger failed", e)
        }
        StatsInterface979.raid = RaidStatsFromRaid
        TfaRaidEvents.register(TfaStatsListener)
        TfaPartyRegistry.configure(TfaConfig.current[LobbyConfig].party)
        TfaCommands.bind(TfaCommands.Slot.PARTY) { player, args -> partyCommand(player, args) }
        registerCommands()
        event.worldThread.hook<WorldEvent.Tick> { tick() }
    }

    @Subscribe
    @JvmStatic
    fun onLogin(event: LoginEvent) {
        val player = event.player
        try {
            TfaEntryGate.check(player)
        } catch (e: Exception) {
            log.error("Fractured Archive login check failed for {}", player.username, e)
        }
    }

    @Subscribe
    @JvmStatic
    fun onLogout(event: LogoutEvent) {
        val player = event.player
        try {
            TfaPartyRegistry.onLogout(player)
            IntroScene.abort(player)
            MessengerService.remove(player)
            Sprites.forget(player)
        } catch (e: Exception) {
            log.error("Fractured Archive logout handling failed for {}", player.username, e)
        }
    }

    @Subscribe
    @JvmStatic
    fun onShutdown(event: ServerShutdownEvent) {
        TfaRecordsStore.flushNow()
    }

    /** Vars that live in the player save [CACHE: perm varps / varbits of §5.4]. */
    private fun registerPersistentVars() {
        try {
            VarManager.appendPersistentVarp(TfaIds.Varp.KC)
            for (varbit in listOf(
                TfaIds.Varbit.ENTRANCE_UNLOCKED,      // wgs_weight bit 23
                TfaIds.Varbit.FRIENDS_FILTER,         // tfa_perm_transmit_1 (16380-16384)
                TfaIds.Varbit.PREFERRED_MODE,
                TfaIds.Varbit.RECORDS_ROOM_UNLOCKED,
                TfaIds.Varbit.OCELLUS_MET,
                TfaIds.Varbit.MINIQUEST,              // tfa_miniquest_main (16426-16428)
                TfaIds.Varbit.WIZARD_APPRENTICE_MET,
                TfaIds.Varbit.JUNA_DIALOGUE,
                TfaIds.Varbit.CHARGES_FOLIO,
            )) VarManager.appendPersistentVarbit(varbit)
        } catch (e: Throwable) {
            log.error("Fractured Archive: registering persistent vars failed", e)
        }
    }

    private fun tick() {
        try {
            if (!started) {
                started = true
                val cfg = TfaConfig.current[LobbyConfig]
                LobbyNpcs.spawnAll(cfg)
                AscensionRocks.init()
            }
            LobbyClock.queue.advance()
            ticks++
            val cfg = TfaConfig.current[LobbyConfig]
            if (ticks % cfg.stats.saveIntervalTicks == 0L) TfaRecordsStore.flush()
            if (ticks % cfg.messenger.checkIntervalTicks == 0L) checkTempleVisitors()
        } catch (e: Exception) {
            log.error("Fractured Archive lobby tick failed", e)
        }
    }

    /** Players at the temple (region 16199) who now meet the attunement get the entrance (and the messenger). */
    private fun checkTempleVisitors() {
        for (player in World.getPlayers()) {
            if (player == null || player.isFinished) continue
            val loc = player.location
            if ((((loc.x shr 6) shl 8) or (loc.y shr 6)) != TfaIds.Region.TEMPLE) continue
            if (!TfaEntryGate.isUnlocked(player)) TfaEntryGate.check(player)
        }
    }

    // ---------------------------------------------------------------------------------------------- ::tfa

    private fun partyCommand(player: Player, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            null, "show" -> {
                val party = TfaPartyRegistry.partyOf(player) ?: TfaPartyRegistry.create(player)
                player.sendMessage(party?.let { "Party #${it.id}: ${it.members.joinToString { m -> m.name }}" } ?: "No party.")
            }
            "list" -> TfaPartyRegistry.describe().ifEmpty { listOf("No parties.") }.forEach(player::sendMessage)
            "board" -> PartyListInterface.open(player)
            "add" -> {
                val name = args.drop(1).joinToString(" ")
                val other = World.getPlayer(name).orElse(null)
                player.sendMessage(if (other == null) "No online player called \"$name\"." else TfaPartyRegistry.debugAdd(player, other))
            }
            "leave" -> TfaPartyRegistry.leave(player)
            "disband" -> TfaPartyRegistry.partyOf(player)?.let { TfaPartyRegistry.disband(player, it) }
            else -> player.sendMessage("Usage: ::tfa party [show|list|board|add <name>|leave|disband]")
        }
    }

    private fun registerCommands() {
        TfaCommands.register("lobby", "lobby [tp|temple|npcs]", "Fractured Archive lobby state; tp = go to the lobby") { player, args ->
            when (args.firstOrNull()?.lowercase()) {
                "tp" -> EntranceFlow.toLobby(player)
                "temple" -> EntranceFlow.toTemple(player)
                else -> {
                    AscensionRocks.describe().forEach(player::sendMessage)
                    player.sendMessage("Lobby clock ${LobbyClock.queue.now}, ${LobbyClock.queue.size} queued; parties ${TfaPartyRegistry.all().size}")
                }
            }
        }
        TfaCommands.register("gate", "gate [check|unlock|reset]", "entry gate / miniquest state (16354, 16426)") { player, args ->
            when (args.firstOrNull()?.lowercase()) {
                "unlock" -> TfaEntryGate.unlock(player)
                "reset" -> {
                    player.varManager.sendBit(TfaIds.Varbit.ENTRANCE_UNLOCKED, 0)
                    player.varManager.sendBit(TfaIds.Varbit.MINIQUEST, 0)
                    player.putBooleanAttribute(IntroScene.SEEN_ATTRIBUTE, false)
                    player.putBooleanAttribute(MessengerService.DELIVERED_ATTRIBUTE, false)
                    player.putBooleanAttribute(Miniquest.MESSAGE_READ_ATTRIBUTE, false)
                }
                "check" -> TfaEntryGate.check(player)
            }
            val missing = TfaEntryGate.missing(TfaConfig.current.raid.entryGate.requirements, TfaEntryGate.statsOf(player))
            player.sendMessage("Gate ${TfaConfig.current.raid.entryGate.mode.name.lowercase()}: unlocked=${TfaEntryGate.isUnlocked(player)}, " +
                "miniquest=${Miniquest.stage(player)}, missing=${missing.joinToString().ifEmpty { "none" }}")
        }
        TfaCommands.register("intro", "intro", "play the first-entry scene now") { player, _ ->
            IntroScene.play(player) { EntranceFlow.toLobby(player) }
        }
        TfaCommands.register("folio", "folio [charges]", "show or set Archivist's folio charges") { player, args ->
            args.firstOrNull()?.toIntOrNull()?.let { player.varManager.sendBit(TfaIds.Varbit.CHARGES_FOLIO, it.coerceIn(0, 65535)) }
            Folio.check(player)
        }
        TfaCommands.register("stats", "stats [open|raid|kc <n>]", "Alliance Log and personal stats") { player, args ->
            when (args.firstOrNull()?.lowercase()) {
                "open" -> StatsInterface979.open(player, inActiveRaid = false)
                "raid" -> StatsInterface979.openFromRaid(player)
                "kc" -> args.getOrNull(1)?.toIntOrNull()?.let { player.varManager.sendVar(TfaIds.Varp.KC, it.coerceAtLeast(0)) }
            }
            player.sendMessage("KC ${TfaStats.killCount(player)}; records file ${TfaRecordsStore.records.todayDate}")
        }
    }
}
