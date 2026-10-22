package com.gadscape.raids.tfa.raid

import com.gadscape.raids.tfa.debug.TfaCommands
import com.gadscape.raids.tfa.party.TfaPartyRegistry
import com.gadscape.raids.tfa.spectate.SpectatorService
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.player.Player

/**
 * The raid's `::tfa` commands (WU-12/13 slots of `TfaCommands`, plus `::tfa raid ...`):
 * - `::tfa start [name ...]`: a raid now, skipping the Archive Door checks: your Grouping Board party, else you and the
 *   named online players; everyone in the lobby or nearby is taken in.
 * - `::tfa skipto <room>`: your raid (one is started if you have none) jumps to the room: earlier rooms are marked
 *   done (the target's side is chosen for earlier sections), you land in its antechamber / the nexus / the corridor.
 * - `::tfa raid [status|complete|end|leave|wipe|spectate <name>|navigator]`.
 */
object RaidCommands {

    fun install() {
        TfaCommands.bind(TfaCommands.Slot.START) { player, args -> start(player, args) }
        TfaCommands.bind(TfaCommands.Slot.SKIPTO_ROOM) { player, args -> skipTo(player, args) }
        TfaCommands.register(
            "raid", "raid [status|complete|end|leave|wipe|spectate <name>|navigator]",
            "raid state machine tools: complete the running room, end, leave, wipe, spectate a party, spawn the Navigator",
        ) { player, args -> raid(player, args) }
    }

    private fun start(player: Player, args: List<String>): TfaRaid? {
        TfaRaids.raidOf(player)?.let {
            player.sendMessage("You are already in raid ${it.id}.")
            return it
        }
        val party = TfaPartyRegistry.partyOf(player)
        val raid = if (party != null) {
            TfaRaids.create(party)
        } else {
            val others = args.mapNotNull { n -> World.getPlayer(n.replace('_', ' ')).orElse(null) }
            TfaRaids.create(null, listOf(player) + others, player)
        }
        if (raid == null) {
            player.sendMessage("No raid could be created (see the server log).")
            return null
        }
        // Take everyone in at once (debug start).
        for (m in raid.members) if (!m.entered && !m.player.isFinished) raid.admit(m)
        player.sendMessage("Raid ${raid.id} started for ${raid.members.joinToString { it.name }} (scale now ${raid.currentScale()}).")
        return raid
    }

    private fun skipTo(player: Player, args: List<String>) {
        val room = args.firstOrNull()?.let { TfaRoomId.byName(it) } ?: run {
            player.sendMessage("Usage: ::tfa skipto <room>")
            return
        }
        val raid = TfaRaids.raidOf(player) ?: start(player, emptyList()) ?: return
        player.sendMessage(raid.debugSkipTo(room))
    }

    private fun raid(player: Player, args: List<String>) {
        when (args.firstOrNull()?.lowercase() ?: "status") {
            "status" -> {
                val raid = TfaRaids.raidOf(player) ?: return player.sendMessage("You are not in a raid (${TfaRaids.activeRaidCount()} running).")
                raid.describe().take(12).forEach { player.sendMessage(it) }
            }
            "complete" -> TfaRaids.raidOf(player)?.let { player.sendMessage(it.debugCompleteActive()) }
                ?: player.sendMessage("You are not in a raid.")
            "end" -> TfaRaids.raidOf(player)?.end(RaidEndReason.FAILED) ?: player.sendMessage("You are not in a raid.")
            "leave" -> TfaRaids.raidOf(player)?.leaveToLobby(player) ?: player.sendMessage("You are not in a raid.")
            "wipe" -> {
                val raid = TfaRaids.raidOf(player) ?: return player.sendMessage("You are not in a raid.")
                val rt = raid.activeRoom?.takeIf { it.isActive } ?: return player.sendMessage("No encounter is running.")
                raid.wipe(rt)
            }
            "spectate" -> {
                val name = args.drop(1).joinToString(" ")
                if (name.isBlank()) SpectatorService.spectateRecent(player) else SpectatorService.spectateSpecific(player, name)
            }
            "navigator" -> {
                val raid = TfaRaids.raidOf(player) ?: return player.sendMessage("You are not in a raid.")
                val next = raid.route.nextSection() ?: (if (raid.route.sectionsDone) 4 else 1)
                raid.nexus.scheduleNavigator(next)
                player.sendMessage("The Navigator will come for section $next.")
            }
            else -> player.sendMessage("Usage: ::tfa raid [status|complete|end|leave|wipe|spectate <name>|navigator]")
        }
    }
}
