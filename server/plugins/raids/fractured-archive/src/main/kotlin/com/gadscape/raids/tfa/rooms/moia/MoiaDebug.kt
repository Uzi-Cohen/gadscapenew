package com.gadscape.raids.tfa.rooms.moia

import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.config.TfaConfigSnapshot
import com.gadscape.raids.tfa.debug.TfaCommands
import com.gadscape.raids.tfa.debug.TfaDebug
import com.gadscape.raids.tfa.encounter.Scaling
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.raid.StaticTfaInstance
import com.gadscape.raids.tfa.raid.TfaInstance
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.task.WorldTask
import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.player.Player
import org.slf4j.LoggerFactory
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * `::tfa moia ...` (dev worlds / developers, through [TfaCommands]):
 *
 * | Command | Effect |
 * |---|---|
 * | `moia static` | runs the room on the static map (region 16216) for you alone: no raid needed (WU-12 not required) |
 * | `moia stop` | ends your static run (teardown) |
 * | `moia state` | the room's debug state |
 * | `moia p1` / `enrage` / `death` | same as `::tfa skipto <phase>` (works for the static run too) |
 * | `moia hp <0-100>` | same as `::tfa hp` |
 * | `moia special` / `infect` / `shuffle` | next auto is the special / the infect; shuffle next tick (phase 1) |
 * | `moia charge` | completes the current enrage charge next tick |
 * | `moia arm <W/E/S/N>` | teleports Moia to that arm (phase 1) |
 * | `moia binders` | binder tiles |
 *
 * Inside a real raid the commands act on the raid's Moia encounter (`TfaServices.raids`); `::tfa skipto moia`
 * (reaching the room) belongs to the raid unit's `SKIPTO_ROOM` slot.
 */
internal object MoiaDebug {

    private val log = LoggerFactory.getLogger(MoiaDebug::class.java)

    private val runs = ConcurrentHashMap<Player, StaticRun>()

    @Volatile
    private var installed = false

    /** Static entry tile (spec §2.4: (4066,5663) facing W; the framework's `tfa.json rooms.moia.entry`). */
    private const val ENTRY_X = 4066
    private const val ENTRY_Y = 5663

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            TfaCommands.register(
                "moia",
                "moia <static|stop|state|p1|enrage|death|hp <pct>|special|infect|shuffle|charge|arm <W|E|S|N>|binders>",
                "Moia room tools: a solo run on the static map, phase skips and forced attacks",
            ) { player, args -> execute(player, args) }
            installed = true
        }
    }

    /** The Moia room [player] is in: the raid's, or their static run. */
    fun roomOf(player: Player): MoiaRoom? =
        (TfaServices.raids.encounterOf(player) as? MoiaRoom) ?: runs[player]?.encounter

    private fun execute(player: Player, args: List<String>) {
        val sub = args.firstOrNull()?.lowercase() ?: "help"
        val rest = args.drop(1)
        when (sub) {
            "static" -> startStatic(player)
            "stop" -> {
                val run = runs[player]
                if (run == null) player.sendMessage("You have no static Moia run.") else run.end("stopped")
            }
            "help" -> player.sendMessage("::tfa moia static|stop|state|p1|enrage|death|hp <pct>|special|infect|shuffle|charge|arm <W|E|S|N>|binders")
            else -> {
                val room = roomOf(player) ?: return player.sendMessage("You are not in a Moia room. Try ::tfa moia static.")
                when (sub) {
                    "state" -> room.debugState().forEach(player::sendMessage)
                    MoiaRoom.PHASE_P1, MoiaRoom.PHASE_ENRAGE, MoiaRoom.PHASE_DEATH ->
                        player.sendMessage(if (room.debugSkipToPhase(sub)) "Moia: skipped to $sub." else "Moia: cannot skip to $sub now (${room.state}).")
                    "hp" -> {
                        val pct = rest.firstOrNull()?.toIntOrNull()
                        if (pct == null || pct !in 0..100) player.sendMessage("Usage: ::tfa moia hp <0-100>")
                        else player.sendMessage(if (room.debugSetHpPercent(pct)) "Moia set to $pct%." else "Moia is not up.")
                    }
                    else -> player.sendMessage(room.debugAction(sub, rest))
                }
            }
        }
    }

    private fun startStatic(player: Player) {
        if (runs.containsKey(player)) return player.sendMessage("You already have a static Moia run (::tfa moia stop).")
        if (TfaServices.raids.encounterOf(player) != null) return player.sendMessage("Leave your raid first.")
        val run = StaticRun(player)
        runs[player] = run
        run.begin()
        player.sendMessage("Static Moia run started (scale ${run.scale}). ::tfa moia state / stop.")
    }

    /**
     * A one-player [TfaEncounterContext] on the static map ([StaticTfaInstance]), ticked by one repeating world task
     * (debug only: the room itself never uses world tasks). Ends on completion, on leaving the room, or on `stop`.
     */
    private class StaticRun(val player: Player) : TfaEncounterContext, WorldTask {
        override val room: TfaRoomId get() = TfaRoomId.MOIA
        override val config: TfaConfigSnapshot = TfaConfig.current
        override val scaling: Scaling = Scaling(config.scaling)
        override val scale: Int = scaling.partyScale(1, TfaDebug.forcedScale(player))
        override val instance: TfaInstance get() = StaticTfaInstance
        override val random: Random = Random()
        override val members: List<Player> = listOf(player)
        override var encounterTicks: Int = 0
            private set

        val encounter = MoiaRoom(this)
        private var ended = false
        private var deathSeen = false
        private var emptyTicks = 0

        override fun playersInArena(): List<Player> = members.filter {
            !it.isDead && !it.isFinished && it.location.regionId == TfaRoomId.MOIA.staticRegion && it.plane == 0
        }

        fun begin() {
            player.setLocation(Location(ENTRY_X, ENTRY_Y, 0))
            encounter.build()
            encounter.onArenaEntry(player)
            encounter.start()
            WorldTasksManager.schedule(this, 0, 0)
        }

        override fun run() {
            if (ended) {
                stop()
                return
            }
            try {
                if (player.isFinished) return end("logged out")
                if (player.isDead && !deathSeen) {
                    deathSeen = true
                    encounter.onPlayerDeath(player)
                }
                encounterTicks++
                encounter.tick()
                if (encounter.isComplete()) {
                    encounter.onComplete()
                    player.sendMessage("Moia defeated in $encounterTicks ticks.")
                    return end("complete")
                }
                emptyTicks = if (playersInArena().isEmpty()) emptyTicks + 1 else 0
                if (encounterTicks > 5 && emptyTicks >= 3) {
                    encounter.onWipe()
                    return end("wipe")
                }
            } catch (e: Exception) {
                log.error("Static Moia run of {} failed", player.username, e)
                end("error")
            }
        }

        fun end(reason: String) {
            if (ended) return
            ended = true
            encounter.teardown()
            runs.remove(player, this)
            stop()
            log.info("Static Moia run of {} ended: {}", player.username, reason)
            if (!player.isFinished) player.sendMessage("Static Moia run ended ($reason).")
        }
    }
}
