package com.gadscape.raids.tfa.rooms.xarth

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
 * `::tfa xarth ...`: runs the Xarth room on the **static** map (region 16726) without the raid system, for development
 * worlds until WU-12/WU-13 put a party in an instance. It plays the raid's part: a debug [TfaEncounterContext]
 * (identity instance, the live config snapshot, scale from `::tfa scale` or the party size), teleports the players to
 * the transporter landing of `tfa.json rooms.xarth`, calls `start()`, ticks the room once per server tick, completes it
 * (and returns everyone 3 t later, as the raid would) or tears it down. Teardown puts the pillars and piles back, so the
 * static map is left as it was.
 *
 * Sub-commands: `start` (you and every player within 20 tiles of you), `stop`, `phase <name>` (as `::tfa skipto`:
 * p1, blast80, blast65, blast50, p2, healblast, death), `hp <pct>`, `state`, `kill`, `corpses` (a summon now, phase 2),
 * `killcorpses`. Deaths here are normal deaths: use `::tfa godmode`.
 */
internal object XarthDebug {

    private val log = LoggerFactory.getLogger(XarthDebug::class.java)

    private class Run(val starter: Player, val context: DebugContext, val room: XarthRoom, val returns: Map<Player, Location>) {
        var task: WorldTask? = null
        var completedTick = -1
        var ended = false
    }

    private val runs = ConcurrentHashMap<Player, Run>()

    @Volatile
    private var installed = false

    private const val USAGE = "xarth [start|stop|phase <name>|hp <pct>|state|kill|corpses|killcorpses]"

    fun install() {
        if (installed) return
        installed = true
        TfaCommands.register("xarth", USAGE, "run the Xarth room on the static map (no raid system needed)") { player, args ->
            execute(player, args)
        }
    }

    private fun execute(player: Player, args: List<String>) {
        when (args.firstOrNull()?.lowercase() ?: "start") {
            "start" -> start(player)
            "stop" -> runOf(player)?.let { end(it, "stopped") } ?: player.sendMessage("No Xarth debug run.")
            "phase", "skipto" -> {
                val run = runOf(player) ?: return player.sendMessage("No Xarth debug run.")
                val phase = args.getOrNull(1)
                if (phase == null || !run.room.debugSkipToPhase(phase)) {
                    player.sendMessage("Phases: ${run.room.phases.joinToString()}.")
                } else player.sendMessage("Xarth: skipped to $phase.")
            }
            "hp" -> {
                val run = runOf(player) ?: return player.sendMessage("No Xarth debug run.")
                val pct = args.getOrNull(1)?.toIntOrNull()
                if (pct == null || pct !in 0..100 || !run.room.debugSetHpPercent(pct)) player.sendMessage("Usage: ::tfa xarth hp <0-100>")
            }
            "kill" -> runOf(player)?.room?.debugSkipToPhase(XarthRoom.PHASE_DEATH)
            "corpses" -> {
                val run = runOf(player) ?: return player.sendMessage("No Xarth debug run.")
                player.sendMessage("Xarth: ${run.room.debugSummon()} pile(s) reanimating.")
            }
            "killcorpses" -> runOf(player)?.room?.corpses?.debugKillAll()
            "state" -> {
                val run = runOf(player) ?: return player.sendMessage("No Xarth debug run.")
                Diary.sendJournal(player, "Xarth debug", run.room.debugState())
            }
            else -> player.sendMessage("Usage: ::tfa $USAGE")
        }
    }

    private fun runOf(player: Player): Run? = runs.values.firstOrNull { player === it.starter || player in it.context.members }

    private fun start(player: Player) {
        if (runOf(player) != null) {
            player.sendMessage("You are already in a Xarth debug run: ::tfa xarth stop")
            return
        }
        val members = ArrayList<Player>()
        members += player
        CharacterLoop.forEach(player.location, 20, Player::class.java) { p ->
            if (p !== player && !p.isDead && !p.isFinished && runOf(p) == null) members += p
        }
        val snapshot = TfaConfig.current
        val context = DebugContext(snapshot, members, TfaDebug.forcedScale(members))
        val room = XarthRoom(context)
        val returns = members.associateWith { Location(it.location) }
        val run = Run(player, context, room, returns)
        runs[player] = run
        try {
            room.build()
            val entry = snapshot.raid.rooms[TfaRoomId.XARTH]
            val landing = entry?.entry ?: StaticTile(4184, 5535, 0)
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
                it.sendMessage("Xarth debug run (scale ${context.scale}, ${members.size} player(s)). " +
                    "Deaths here are normal deaths; ::tfa godmode. Stop: ::tfa xarth stop")
            }
        } catch (e: Exception) {
            log.error("Xarth debug start failed", e)
            player.sendMessage("Xarth debug start failed: ${e.message}")
            end(run, "failed")
        }
    }

    /** 0, +1, -1, +2, -2 ... along the west edge, capped at the arena. */
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
                run.context.members.forEach { it.sendMessage("Xarth defeated in ${run.completedTick} ticks.") }
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
            log.error("Xarth debug tick failed", e)
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
            log.error("Xarth debug teardown failed", e)
        }
        runs.values.remove(run)
        for ((p, back) in run.returns) {
            val here = run.context.instance.toStatic(p.location)
            if (!p.isFinished && !p.isDead && here != null && XarthArena.inBox(here)) p.setLocation(back)
            p.sendMessage("Xarth debug run ended ($reason).")
        }
    }

    /** The raid's side of an encounter, for a debug run on the static map. */
    private class DebugContext(
        override val config: TfaConfigSnapshot,
        override val members: List<Player>,
        forcedScale: Int?,
    ) : TfaEncounterContext {
        override val room: TfaRoomId = TfaRoomId.XARTH
        override val scaling: Scaling = Scaling(config.scaling)
        override val scale: Int = scaling.partyScale(members.size, forcedScale)
        override val instance: TfaInstance = StaticTfaInstance
        override val random: Random = Random()
        override var encounterTicks: Int = 0

        override fun playersInArena(): List<Player> = members.filter {
            !it.isDead && !it.isFinished && instance.toStatic(it.location)?.let(XarthArena::inBox) == true
        }
    }
}
