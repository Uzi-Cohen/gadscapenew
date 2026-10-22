package com.gadscape.raids.tfa.rooms.ezekiel

import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.config.TfaConfigSnapshot
import com.gadscape.raids.tfa.debug.TfaDebug
import com.gadscape.raids.tfa.encounter.Scaling
import com.gadscape.raids.tfa.encounter.TfaEncounter
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.raid.StaticTfaInstance
import com.gadscape.raids.tfa.raid.TfaInstance
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.task.WorldTask
import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.player.Player
import org.slf4j.LoggerFactory
import java.util.Random

/**
 * `::tfa ezekiel ...` (development aid, registered by [EzekielModule]).
 *
 * Inside a raid the room is reached with WU-12's `::tfa skipto ezekiel` and its phases with the built-in
 * `::tfa skipto <phase>` / `::tfa hp <pct>` (they call [EzekielRoom.debugSkipToPhase] / [EzekielRoom.debugSetHpPercent]).
 * Until the raid exists, `::tfa ezekiel here` runs the room on the static map (region 16214 holds the arena on plane 0):
 * it puts you (and every player already in the room's region) on the 9x9 floor at the encounter entry tile, builds the
 * room, starts it as the first transporter use would (you are the tank) and drives it every tick.
 */
internal object EzekielDebug {

    private val log = LoggerFactory.getLogger(EzekielDebug::class.java)

    const val USAGE = "ezekiel <here|stop|state|skip <phase>|hp <pct>|cast <grid[+grid]>|tank>"
    const val HELP = "Ezekiel: run the room on the static map, jump to a phase, set HP, force a sword cast, take the tank"

    /** Region 16214 (§2.4). */
    const val REGION = 16214

    @Volatile
    private var standalone: StandaloneRun? = null

    fun execute(player: Player, args: List<String>) {
        when (args.firstOrNull()?.lowercase() ?: "state") {
            "here", "standalone", "run" -> startStandalone(player)
            "stop", "end" -> stopStandalone(player)
            "state", "dump" -> roomOf(player)?.debugState()?.forEach(player::sendMessage)
                ?: player.sendMessage("No Ezekiel room for you (::tfa ezekiel here starts one).")
            "skip", "skipto", "phase" -> {
                val phase = args.getOrNull(1)
                val room = roomOf(player)
                when {
                    room == null -> player.sendMessage("No Ezekiel room for you.")
                    phase == null -> player.sendMessage("Phases: ${EzekielFight.PHASES.joinToString()}.")
                    room.debugSkipToPhase(phase) -> player.sendMessage("Ezekiel: skipped to $phase.")
                    else -> player.sendMessage("Can't skip to \"$phase\" now. Phases: ${EzekielFight.PHASES.joinToString()}.")
                }
            }
            "hp" -> {
                val pct = args.getOrNull(1)?.toIntOrNull()
                val room = roomOf(player)
                when {
                    room == null -> player.sendMessage("No Ezekiel room for you.")
                    pct == null || pct !in 0..100 -> player.sendMessage("Usage: ::tfa ezekiel hp <0-100>")
                    room.debugSetHpPercent(pct) -> player.sendMessage("Ezekiel set to $pct%.")
                    else -> player.sendMessage("Ezekiel has not started.")
                }
            }
            "cast", "swords" -> {
                val key = args.getOrNull(1)
                val room = roomOf(player)
                when {
                    room == null -> player.sendMessage("No Ezekiel room for you.")
                    key == null -> player.sendMessage("Casts: ${SwordCast.DEFAULT_KEYS.joinToString()}.")
                    room.debugCast(key) -> player.sendMessage("Ezekiel: sword cast $key.")
                    else -> player.sendMessage("Unknown cast \"$key\" (or the fight has not started).")
                }
            }
            "tank" -> {
                val room = roomOf(player)
                if (room == null) player.sendMessage("No Ezekiel room for you.")
                else {
                    room.tanks.force(player)
                    player.sendMessage("You are Ezekiel's tank.")
                }
            }
            else -> player.sendMessage("Usage: ::tfa $USAGE")
        }
    }

    private fun roomOf(player: Player): EzekielRoom? {
        (TfaServices.raids.encounterOf(player) as? EzekielRoom)?.let { return it }
        return standalone?.room
    }

    private fun inArena(p: Player): Boolean {
        val l = p.location
        return l.plane == 0 && EzekielArena.FLOOR.contains(l.x, l.y)
    }

    private fun startStandalone(player: Player) {
        if (standalone != null) {
            player.sendMessage("An Ezekiel debug run is already active; ::tfa ezekiel stop ends it.")
            return
        }
        val snapshot = TfaConfig.current
        val entry = snapshot.raid.rooms[TfaRoomId.EZEKIEL]?.entry
        val entryTile = if (entry != null) Location(entry.x, entry.y, entry.plane) else Location(4063, 5532, 0)
        val members = ArrayList<Player>()
        members += player
        for (p in World.getPlayers()) {
            if (p != null && p != player && !p.isFinished && p.location.regionId == REGION && p.plane == 0) members += p
        }
        for (p in members) {
            p.resetWalkSteps()
            p.setLocation(Location(entryTile))
        }
        val run = StandaloneRun(snapshot, members)
        standalone = run
        run.begin(player)
        player.sendMessage("Ezekiel debug run for ${members.size} player(s); you are the tank.")
    }

    private fun stopStandalone(player: Player) {
        val run = standalone
        if (run == null) {
            player.sendMessage("No Ezekiel debug run.")
            return
        }
        run.end(wipe = true)
        player.sendMessage("Ezekiel debug run ended.")
    }

    /** A room on the static map, driven by a world task (debug only: real raids are driven by WU-12). */
    private class StandaloneRun(snapshot: TfaConfigSnapshot, private val party: List<Player>) : WorldTask {

        private var ticks = 0
        private var ended = false
        private val exit = snapshot[EzekielConfig].debugExitTile

        val context = object : TfaEncounterContext {
            override val room: TfaRoomId = TfaRoomId.EZEKIEL
            override val config: TfaConfigSnapshot = snapshot
            override val scaling: Scaling = Scaling(snapshot.scaling)
            override val scale: Int get() = scaling.partyScale(party.size, TfaDebug.forcedScale(party))
            override val instance: TfaInstance = StaticTfaInstance
            override val random: Random = Random()
            override val members: List<Player> get() = party
            override fun playersInArena(): List<Player> = party.filter { !it.isFinished && !it.isDead && inArena(it) }
            override val encounterTicks: Int get() = ticks
        }

        val room: EzekielRoom = EzekielRoom(context)

        fun begin(first: Player) {
            room.build()
            room.onArenaEntry(first)
            for (p in party) if (p != first) room.onArenaEntry(p)
            Bridge.requestStart(room, first)
            WorldTasksManager.schedule(this, 0, 0)
        }

        override fun run() {
            if (ended) {
                stop()
                return
            }
            try {
                ticks++
                room.tick()
                if (room.isComplete()) {
                    room.onComplete()
                    end(wipe = false)
                    return
                }
                if (party.none { !it.isFinished && !it.isDead && inArena(it) }) {
                    end(wipe = true)
                }
            } catch (e: Exception) {
                log.error("Ezekiel debug run failed", e)
                end(wipe = true)
            }
        }

        fun end(wipe: Boolean) {
            if (ended) return
            ended = true
            if (wipe) room.onWipe()
            room.teardown()
            for (p in party) if (!p.isFinished && !p.isDead && inArena(p)) p.setLocation(Location(exit.x, exit.y, exit.plane))
            if (standalone === this) standalone = null
            stop()
        }
    }

    /** For `::tfa dump`: the static-map run's encounter. */
    fun standaloneEncounter(): TfaEncounter? = standalone?.room
}
