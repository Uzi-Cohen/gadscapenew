package com.gadscape.raids.tfa.rooms.guardian

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
 * `::tfa guardian ...` (development aid, registered by [ArchiveGuardianModule]).
 *
 * Inside a raid the room is reached with WU-12's `::tfa skipto guardian` and its phases with the built-in
 * `::tfa skipto <phase>` / `::tfa hp <pct>` (they call [ArchiveGuardianRoom.debugSkipToPhase] /
 * [ArchiveGuardianRoom.debugSetHpPercent]). Until the raid exists, `::tfa guardian here` runs the room on the static
 * map (region 16471, which holds the fight layout on plane 0): it teleports you to the atrium, builds the room for
 * everyone there and drives it every tick; Touch the Inert Guardian to start it.
 */
internal object GuardianDebug {

    private val log = LoggerFactory.getLogger(GuardianDebug::class.java)

    const val USAGE = "guardian <here|stop|state|skip <phase>|hp <pct>|battery <nw|sw|ne|se>>"
    const val HELP = "Archive Guardian: run the room on the static map, jump to a phase, set HP, break a battery"

    /** The static-map debug run, if any (one per server). */
    @Volatile
    private var standalone: StandaloneRun? = null

    fun execute(player: Player, args: List<String>) {
        val sub = args.firstOrNull()?.lowercase() ?: "state"
        when (sub) {
            "here", "standalone", "run" -> startStandalone(player)
            "stop", "end" -> stopStandalone(player)
            "state", "dump" -> roomOf(player)?.debugState()?.forEach(player::sendMessage)
                ?: player.sendMessage("No Archive Guardian room for you (::tfa guardian here starts one).")
            "skip", "skipto", "phase" -> {
                val phase = args.getOrNull(1)
                val room = roomOf(player)
                when {
                    room == null -> player.sendMessage("No Archive Guardian room for you.")
                    phase == null -> player.sendMessage("Phases: ${GuardianFight.PHASES.joinToString()}.")
                    room.debugSkipToPhase(phase) -> player.sendMessage("Archive Guardian: skipped to $phase.")
                    else -> player.sendMessage("Unknown phase \"$phase\". Phases: ${GuardianFight.PHASES.joinToString()}.")
                }
            }
            "hp" -> {
                val pct = args.getOrNull(1)?.toIntOrNull()
                val room = roomOf(player)
                when {
                    room == null -> player.sendMessage("No Archive Guardian room for you.")
                    pct == null || pct !in 0..100 -> player.sendMessage("Usage: ::tfa guardian hp <0-100>")
                    room.debugSetHpPercent(pct) -> player.sendMessage("Archive Guardian pool set to $pct%.")
                    else -> player.sendMessage("The Archive Guardian is not up yet.")
                }
            }
            "battery" -> {
                val alcove = args.getOrNull(1)?.let(Alcove::byName)
                val room = roomOf(player)
                when {
                    room == null -> player.sendMessage("No Archive Guardian room for you.")
                    alcove == null -> player.sendMessage("Usage: ::tfa guardian battery <nw|sw|ne|se>")
                    room.debugDestroyBattery(alcove) -> player.sendMessage("Battery $alcove destroyed.")
                    else -> player.sendMessage("Battery $alcove is already destroyed.")
                }
            }
            else -> player.sendMessage("Usage: ::tfa $USAGE")
        }
    }

    /** The player's raid encounter if it is the Guardian, else the static-map run. */
    private fun roomOf(player: Player): ArchiveGuardianRoom? {
        (TfaServices.raids.encounterOf(player) as? ArchiveGuardianRoom)?.let { return it }
        val run = standalone ?: return null
        return run.room
    }

    private fun startStandalone(player: Player) {
        if (standalone != null) {
            player.sendMessage("An Archive Guardian debug run is already active; ::tfa guardian stop ends it.")
            return
        }
        val snapshot = TfaConfig.current
        val arrival = snapshot.raid.tiles.nexusArrival
        player.setLocation(Location(arrival.x, arrival.y, arrival.plane))
        val members = ArrayList<Player>()
        members += player
        for (p in World.getPlayers()) {
            if (p != null && p != player && !p.isFinished && p.location.regionId == ATRIUM_REGION && p.plane == 0) members += p
        }
        val run = StandaloneRun(snapshot, members)
        standalone = run
        run.begin()
        player.sendMessage("Archive Guardian debug run for ${members.size} player(s); Touch the Inert Guardian to start.")
    }

    private fun stopStandalone(player: Player) {
        val run = standalone
        if (run == null) {
            player.sendMessage("No Archive Guardian debug run.")
            return
        }
        run.end(wipe = true)
        player.sendMessage("Archive Guardian debug run ended.")
    }

    /** Region 16471 (§2.4). */
    const val ATRIUM_REGION = 16471

    /** A room on the static map, driven by a world task (debug only: real raids are driven by WU-12). */
    private class StandaloneRun(snapshot: TfaConfigSnapshot, private val party: List<Player>) : WorldTask {

        private var ticks = 0
        private var ended = false

        val context = object : TfaEncounterContext {
            override val room: TfaRoomId = TfaRoomId.GUARDIAN
            override val config: TfaConfigSnapshot = snapshot
            override val scaling: Scaling = Scaling(snapshot.scaling)
            override val scale: Int get() = scaling.partyScale(party.size, TfaDebug.forcedScale(party))
            override val instance: TfaInstance = StaticTfaInstance
            override val random: Random = Random()
            override val members: List<Player> get() = party
            override fun playersInArena(): List<Player> = party.filter {
                !it.isFinished && !it.isDead && it.location.regionId == ATRIUM_REGION && it.plane == 0
            }
            override val encounterTicks: Int get() = ticks
        }

        val room: ArchiveGuardianRoom = ArchiveGuardianRoom(context)

        fun begin() {
            room.build()
            for (p in context.playersInArena()) room.onArenaEntry(p)
            WorldTasksManager.schedule(this, 0, 0)
        }

        override fun run() {
            if (ended) {
                stop()
                return
            }
            try {
                if (room.started) {
                    ticks++
                    room.tick()
                    if (room.isComplete()) {
                        room.onComplete()
                        end(wipe = false)
                        return
                    }
                    if (party.none { !it.isFinished && !it.isDead && it.location.regionId == ATRIUM_REGION }) {
                        end(wipe = true)
                        return
                    }
                } else if (party.all { it.isFinished }) {
                    end(wipe = true)
                }
            } catch (e: Exception) {
                log.error("Archive Guardian debug run failed", e)
                end(wipe = true)
            }
        }

        fun end(wipe: Boolean) {
            if (ended) return
            ended = true
            if (wipe) room.onWipe()
            room.teardown()
            if (standalone === this) standalone = null
            stop()
        }
    }

    /** For `::tfa dump`: the static-map run's state. */
    fun standaloneEncounter(): TfaEncounter? = standalone?.room
}
