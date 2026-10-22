package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.config.TfaConfigSnapshot
import com.gadscape.raids.tfa.debug.TfaCommands
import com.gadscape.raids.tfa.debug.TfaDebug
import com.gadscape.raids.tfa.encounter.Scaling
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.raid.StaticTfaInstance
import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.raid.TfaInstance
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.content.achievementdiary.Diary
import com.zenyte.game.task.WorldTask
import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.region.CharacterLoop
import org.slf4j.LoggerFactory
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * `::tfa bork ...`: runs the Bork and the Smorks room on the **static** map (region 16727) without the raid system,
 * for development worlds until WU-12/WU-13 can put a party in an instance. It plays the raid's part: a debug
 * [TfaEncounterContext] (identity instance, the live config snapshot, scale from `::tfa scale` or the head count),
 * teleports the players onto the transporter landing of `tfa.json rooms.bork_smorks` (4186,5599), calls `start()`,
 * ticks the room once per server tick, completes it (and returns everyone 3 t later, as the raid would) or tears it
 * down when nobody is left in the arena (wipe).
 *
 * Sub-commands: `start` (you and every player within 20 tiles), `stop`, `phase <name>` (as `::tfa skipto`),
 * `hp <pct>` (both) / `hp <bork|smorks> <pct>`, `kill <bork|smorks|both>`, `state`. Deaths here are normal deaths:
 * use `::tfa godmode`.
 */
internal object BorkDebug {

    private val log = LoggerFactory.getLogger(BorkDebug::class.java)

    private class Run(val starter: Player, val context: DebugContext, val room: BorkSmorksRoom, val returns: Map<Player, Location>) {
        var task: WorldTask? = null
        var completedTick = -1
        var ended = false
    }

    private val runs = ConcurrentHashMap<Player, Run>()

    @Volatile
    private var installed = false

    private const val USAGE = "Usage: ::tfa bork [start|stop|phase <name>|hp [bork|smorks] <pct>|kill <bork|smorks|both>|state]"

    fun install() {
        if (installed) return
        installed = true
        TfaCommands.register(
            "bork",
            "bork [start|stop|phase <name>|hp [bork|smorks] <pct>|kill <bork|smorks|both>|state]",
            "run the Bork and the Smorks room on the static map (no raid system needed)",
        ) { player, args -> execute(player, args) }
    }

    private fun execute(player: Player, args: List<String>) {
        when (args.firstOrNull()?.lowercase() ?: "start") {
            "start" -> start(player)
            "stop" -> runOf(player)?.let { end(it, "stopped") } ?: player.sendMessage("No Bork debug run.")
            "phase", "skipto" -> {
                val run = runOf(player) ?: return player.sendMessage("No Bork debug run.")
                val phase = args.getOrNull(1)
                if (phase == null || !run.room.debugSkipToPhase(phase)) {
                    player.sendMessage("Phases: ${run.room.phases.joinToString()}.")
                } else player.sendMessage("Bork: skipped to $phase.")
            }
            "hp" -> {
                val run = runOf(player) ?: return player.sendMessage("No Bork debug run.")
                val first = args.getOrNull(1) ?: ""
                val both = first.toIntOrNull()
                val ok = if (both != null) {
                    both in 0..100 && run.room.debugSetHpPercent(both)
                } else {
                    val pct = args.getOrNull(2)?.toIntOrNull()
                    pct != null && pct in 0..100 && run.room.debugSetHp(first, pct)
                }
                if (!ok) player.sendMessage(USAGE)
            }
            "kill" -> {
                val run = runOf(player) ?: return player.sendMessage("No Bork debug run.")
                when (args.getOrNull(1)?.lowercase() ?: "both") {
                    "bork" -> run.room.debugSetHp("bork", 0)
                    "smorks", "smork" -> run.room.debugSetHp("smorks", 0)
                    else -> run.room.debugSkipToPhase(BorkSmorksRoom.PHASE_DEATH)
                }
            }
            "state" -> {
                val run = runOf(player) ?: return player.sendMessage("No Bork debug run.")
                Diary.sendJournal(player, "Bork debug", run.room.debugState())
            }
            else -> player.sendMessage(USAGE)
        }
    }

    private fun runOf(player: Player): Run? = runs.values.firstOrNull { player === it.starter || player in it.context.members }

    private fun start(player: Player) {
        if (runOf(player) != null) {
            player.sendMessage("You are already in a Bork debug run: ::tfa bork stop")
            return
        }
        val members = ArrayList<Player>()
        members += player
        CharacterLoop.forEach(player.location, 20, Player::class.java) { p ->
            if (p !== player && !p.isDead && !p.isFinished && runOf(p) == null) members += p
        }
        val snapshot = TfaConfig.current
        val context = DebugContext(snapshot, members, TfaDebug.forcedScale(members))
        val room = BorkSmorksRoom(context)
        val returns = members.associateWith { Location(it.location) }
        val run = Run(player, context, room, returns)
        runs[player] = run
        try {
            room.build()
            val entry = snapshot.raid.rooms[TfaRoomId.BORK]
            val landing = entry?.entry ?: StaticTile(4186, 5599, 0)
            for ((i, p) in members.withIndex()) {
                val tile = StaticTile(landing.x, landing.y + spreadOffset(i, entry?.spread ?: 1), landing.plane)
                p.setLocation(context.instance.translate(tile))
                room.onArenaEntry(p)
            }
            room.start()
            val task = WorldTask { tick(run) }
            run.task = task
            WorldTasksManager.schedule(task, 0, 0)
            members.forEach {
                it.sendMessage("Bork debug run (scale ${context.scale}, ${members.size} player(s)). " +
                    "Deaths here are normal deaths; ::tfa godmode. Stop: ::tfa bork stop")
            }
        } catch (e: Exception) {
            log.error("Bork debug start failed", e)
            player.sendMessage("Bork debug start failed: ${e.message}")
            end(run, "failed")
        }
    }

    /** 0, +1, -1, +2, -2 ... along the west edge, kept inside the arena (y 5592..5606). */
    private fun spreadOffset(index: Int, spread: Int): Int {
        if (spread <= 0 || index == 0) return 0
        val step = (index + 1) / 2
        val off = if (index % 2 == 1) step else -step
        return off.coerceIn(-7, 7)
    }

    private fun tick(run: Run) {
        if (run.ended) return
        try {
            run.context.encounterTicks++
            val room = run.room
            room.tick()
            if (run.completedTick < 0 && room.isComplete()) {
                room.onComplete()
                run.completedTick = run.context.encounterTicks
                run.context.members.forEach { it.sendMessage("Bork and the Smorks defeated in ${run.completedTick} ticks.") }
            }
            if (run.completedTick >= 0) {
                val delay = run.context.config.raid.roomExitDelayTicks
                if (run.context.encounterTicks - run.completedTick >= delay) end(run, "complete")
                return
            }
            if (run.context.playersInArena().isEmpty()) {
                room.onWipe()
                end(run, "wipe")
            }
        } catch (e: Exception) {
            log.error("Bork debug tick failed", e)
            end(run, "error: ${e.message}")
        }
    }

    private fun end(run: Run, reason: String) {
        if (run.ended) return
        run.ended = true
        run.task?.let { WorldTasksManager.stop(it) }
        try {
            run.room.teardown()
        } catch (e: Exception) {
            log.error("Bork debug teardown failed", e)
        }
        runs.values.remove(run)
        for ((p, back) in run.returns) {
            val here = run.context.instance.toStatic(p.location)
            if (!p.isFinished && !p.isDead && here != null && BorkArena.inBox(here)) p.setLocation(back)
            p.sendMessage("Bork debug run ended ($reason).")
        }
    }

    /** The raid's side of an encounter, for a debug run on the static map. */
    private class DebugContext(
        override val config: TfaConfigSnapshot,
        override val members: List<Player>,
        forcedScale: Int?,
    ) : TfaEncounterContext {
        override val room: TfaRoomId = TfaRoomId.BORK
        override val scaling: Scaling = Scaling(config.scaling)
        override val scale: Int = scaling.partyScale(members.size, forcedScale)
        override val instance: TfaInstance = StaticTfaInstance
        override val random: Random = Random()
        override var encounterTicks: Int = 0

        override fun playersInArena(): List<Player> = members.filter {
            !it.isDead && !it.isFinished && instance.toStatic(it.location)?.let(BorkArena::inBox) == true
        }
    }
}
