package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.TfaServices
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
 * `::tfa dagonhai ...` (developers / dev worlds, through [TfaCommands]): runs the Dagon'hai room on the **static** map
 * (region 16215) without the raid system, until WU-12/WU-13 can put a party in an instance. It plays the raid's part:
 * a debug [TfaEncounterContext] (identity instance, the live config snapshot, scale from `::tfa scale` or the head
 * count), teleports the players to the transporter landing of `tfa.json rooms.dagonhai`, calls `start()`, ticks the
 * room once per server tick, completes it (and returns everyone `roomExitDelayTicks` later, as the raid would) or tears
 * it down.
 *
 * | Command | Effect |
 * |---|---|
 * | `dagonhai start` | you and every player within 20 tiles of you |
 * | `dagonhai stop` | ends the run (teardown) |
 * | `dagonhai phase <shielded/protected/empowered/explosion/death>` | as `::tfa skipto <phase>` |
 * | `dagonhai hp <pct>` / `pool <pct>` | the shared pool (0 breaks it) |
 * | `dagonhai state` | the room's state in a journal |
 * | `dagonhai channel [fire/water/air/earth] [elder/staff/book]` | forces a channel |
 * | `dagonhai tome [zamorak/saradomin/bandos] [n/ne/e/se/s/sw/w/nw] [staff/book]` | forces a tome throw |
 * | `dagonhai break` / `explode` / `unprotect` / `imps` / `ritual <n>` | break, 5th break, end protection, kill imps, set the ritual |
 *
 * Inside a real raid the same actions work on the raid's encounter (`TfaServices.raids`). Deaths in a static run are
 * normal deaths: use `::tfa godmode`.
 */
internal object DagonhaiDebug {

    private val log = LoggerFactory.getLogger(DagonhaiDebug::class.java)

    private class Run(val starter: Player, val context: DebugContext, val room: DagonhaiRoom, val returns: Map<Player, Location>) {
        var task: WorldTask? = null
        var completedTick = -1
        var ended = false
    }

    private val runs = ConcurrentHashMap<Player, Run>()

    @Volatile
    private var installed = false

    private const val USAGE = "dagonhai [start|stop|phase <name>|hp <pct>|pool <pct>|state|channel [element] [caster]|" +
        "tome [god] [desk] [monk]|break|explode|unprotect|imps|ritual <n>]"

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            TfaCommands.register("dagonhai", USAGE, "run and drive the Dagon'hai trio room (static map run, no raid needed)") { player, args ->
                execute(player, args)
            }
            installed = true
        }
    }

    /** The Dagon'hai room [player] is in: the raid's, or a static run's. */
    private fun roomOf(player: Player): DagonhaiRoom? =
        (TfaServices.raids.encounterOf(player) as? DagonhaiRoom) ?: runOf(player)?.room

    private fun execute(player: Player, args: List<String>) {
        val sub = args.firstOrNull()?.lowercase() ?: "start"
        val rest = args.drop(1)
        when (sub) {
            "start" -> start(player)
            "stop" -> runOf(player)?.let { end(it, "stopped") } ?: player.sendMessage("No Dagon'hai debug run.")
            "help" -> player.sendMessage("::tfa $USAGE")
            else -> {
                val room = roomOf(player) ?: return player.sendMessage("You are not in a Dagon'hai room. Try ::tfa dagonhai start.")
                when (sub) {
                    "phase", "skipto" -> {
                        val phase = rest.firstOrNull()
                        if (phase == null || !room.debugSkipToPhase(phase)) player.sendMessage("Phases: ${room.phases.joinToString()} (now ${room.fight.phase}).")
                        else player.sendMessage("Dagon'hai: skipped to $phase.")
                    }
                    "hp" -> {
                        val pct = rest.firstOrNull()?.toIntOrNull()
                        if (pct == null || pct !in 0..100 || !room.debugSetHpPercent(pct)) player.sendMessage("Usage: ::tfa dagonhai hp <0-100> (shield phase only)")
                        else player.sendMessage("Pool set to $pct%.")
                    }
                    "state" -> Diary.sendJournal(player, "Dagon'hai debug", room.debugState())
                    else -> player.sendMessage(room.debugAction(sub, rest))
                }
            }
        }
    }

    private fun runOf(player: Player): Run? = runs.values.firstOrNull { player === it.starter || player in it.context.members }

    private fun start(player: Player) {
        if (runOf(player) != null) return player.sendMessage("You are already in a Dagon'hai debug run: ::tfa dagonhai stop")
        if (TfaServices.raids.encounterOf(player) != null) return player.sendMessage("Leave your raid first.")
        val members = ArrayList<Player>()
        members += player
        CharacterLoop.forEach(player.location, 20, Player::class.java) { p ->
            if (p !== player && !p.isDead && !p.isFinished && runOf(p) == null) members += p
        }
        val snapshot = TfaConfig.current
        val context = DebugContext(snapshot, members, TfaDebug.forcedScale(members))
        val room = DagonhaiRoom(context)
        val returns = members.associateWith { Location(it.location) }
        val run = Run(player, context, room, returns)
        runs[player] = run
        try {
            room.build()
            val entry = snapshot.raid.rooms[TfaRoomId.DAGONHAI]
            val landing = entry?.entry ?: StaticTile(4072, 5601, 0)
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
                it.sendMessage("Dagon'hai debug run (scale ${context.scale}, pool ${room.poolMax}, ${members.size} player(s)). " +
                    "Deaths here are normal deaths; ::tfa godmode. Stop: ::tfa dagonhai stop")
            }
        } catch (e: Exception) {
            log.error("Dagon'hai debug start failed", e)
            player.sendMessage("Dagon'hai debug start failed: ${e.message}")
            end(run, "failed")
        }
    }

    /** 0, +1, -1, +2, -2 ... along the east edge, capped at the arena. */
    private fun spreadOffset(index: Int, spread: Int): Int {
        if (spread <= 0 || index == 0) return 0
        val step = (index + 1) / 2
        val off = if (index % 2 == 1) step else -step
        return off.coerceIn(-5, 5)
    }

    private fun tick(run: Run) {
        if (run.ended) return
        try {
            run.context.encounterTicks++
            val room = run.room
            for (p in run.context.members) if (p.isDead && room.fight.phase != DagonhaiFight.Phase.DONE) room.onPlayerDeath(p)
            room.tick()
            if (run.completedTick < 0 && room.isComplete()) {
                room.onComplete()
                run.completedTick = run.context.encounterTicks
                run.context.members.forEach { it.sendMessage("The Dagon'hai are defeated in ${run.completedTick} ticks.") }
            }
            if (run.completedTick >= 0) {
                val delay = run.context.config.raid.roomExitDelayTicks
                if (run.context.encounterTicks - run.completedTick >= delay) end(run, "complete")
                return
            }
            if (run.context.encounterTicks > 5 && run.context.playersInArena().isEmpty()) {
                room.onWipe()
                end(run, "wipe")
            }
        } catch (e: Exception) {
            log.error("Dagon'hai debug tick failed", e)
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
            log.error("Dagon'hai debug teardown failed", e)
        }
        runs.values.remove(run)
        for ((p, back) in run.returns) {
            val here = run.context.instance.toStatic(p.location)
            if (!p.isFinished && !p.isDead && here != null && DagonhaiArena.onFloor(here)) p.setLocation(back)
            if (!p.isFinished) p.sendMessage("Dagon'hai debug run ended ($reason).")
        }
    }

    /** The raid's side of an encounter, for a debug run on the static map. */
    private class DebugContext(
        override val config: TfaConfigSnapshot,
        override val members: List<Player>,
        forcedScale: Int?,
    ) : TfaEncounterContext {
        override val room: TfaRoomId = TfaRoomId.DAGONHAI
        override val scaling: Scaling = Scaling(config.scaling)
        override val scale: Int = scaling.partyScale(members.size, forcedScale)
        override val instance: TfaInstance = StaticTfaInstance
        override val random: Random = Random()
        override var encounterTicks: Int = 0

        override fun playersInArena(): List<Player> = members.filter {
            !it.isDead && !it.isFinished && instance.toStatic(it.location)?.let(DagonhaiArena::onFloor) == true
        }
    }
}
