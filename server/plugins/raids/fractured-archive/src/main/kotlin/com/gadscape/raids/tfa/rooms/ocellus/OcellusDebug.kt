package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.config.TfaConfigSnapshot
import com.gadscape.raids.tfa.debug.TfaDebug
import com.gadscape.raids.tfa.encounter.Scaling
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.StaticTfaInstance
import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.raid.TfaInstance
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.task.TickTask
import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.player.Player
import java.util.Random
import java.util.WeakHashMap

/**
 * Debug support (`::tfa`):
 * - [skipTo] backs `::tfa skipto <phase>` (`TfaEncounter.debugSkipToPhase`): intro, p1, p2, p3, plus the
 *   debug-only `p1centre`, `p2b2`, `p2b3`, `p2p3` (the 21-t transition) and `death`. p4 is WU-28's.
 * - `::tfa ocellus ...` ([command]): works on the player's raid encounter when the raid system (WU-12) knows one,
 *   else on a standalone debug run on the static map (dev worlds): `here` starts it with the players nearby,
 *   `stop` tears it down.
 */
internal object OcellusDebug {

    fun skipTo(room: OcellusRoom, phase: String): Boolean = when (phase.lowercase()) {
        "intro" -> room.phase == OcellusPhase.INTRO || (room.phase == OcellusPhase.BUILT).also { if (it) room.build() }
        "p1" -> when (room.phase) {
            OcellusPhase.BUILT, OcellusPhase.INTRO -> room.startFight()
            OcellusPhase.P1 -> true
            else -> false
        }
        "p1centre" -> (room.currentController as? OcellusP1)?.let { it.debugCentre(); true } ?: false
        "p2" -> jumpToP2(room)
        "p2b2" -> jumpToBarrage(room, 1)
        "p2b3" -> jumpToBarrage(room, 2)
        "p2p3" -> {
            if (room.phase.ordinal < OcellusPhase.P2.ordinal) jumpToP2(room)
            if (room.phase == OcellusPhase.P2) {
                room.enter(P2ToP3(room))
                true
            } else false
        }
        "p3" -> jumpToP3(room)
        "death" -> {
            if (room.phase.ordinal < OcellusPhase.P3.ordinal) jumpToP3(room)
            room.boss?.let { it.setHitpoints(0); true } ?: false
        }
        else -> false
    }

    /** P2 without the 13-t transition: Ocellus 16675 at the centre with the P3 pool, barrage 1 starts. */
    private fun jumpToP2(room: OcellusRoom): Boolean {
        if (room.phase.ordinal >= OcellusPhase.P2.ordinal) return room.phase == OcellusPhase.P2
        if (room.phase.ordinal <= OcellusPhase.INTRO.ordinal && !room.startFight()) return false
        val npc = room.boss ?: return false
        room.enter(DebugHold(room, OcellusPhase.P1_TO_P2)) // ends P1 (meter, PfM, tendrils)
        npc.resetWalkSteps()
        room.fx.teleport(npc, room.centreTile)
        npc.transform(TfaIds.Npc.OCELLUS_P2, newPool = room.cfg.hp.p3)
        room.enter(OcellusP2(room))
        return true
    }

    private fun jumpToBarrage(room: OcellusRoom, index: Int): Boolean {
        if (room.phase != OcellusPhase.P2 && !jumpToP2(room)) return false
        val p2 = room.currentController as? OcellusP2 ?: return false
        if (index >= p2.plan.size) return false
        p2.startBarrage(index)
        return true
    }

    /** P3 without the transitions: the Blade dropped, 16677 at the centre, P3 music and level. */
    private fun jumpToP3(room: OcellusRoom): Boolean {
        if (room.phase == OcellusPhase.P3) return true
        if (room.phase.ordinal > OcellusPhase.P3.ordinal) return false
        if (room.phase.ordinal < OcellusPhase.P2.ordinal && !jumpToP2(room)) return false
        val npc = room.boss ?: return false
        room.enter(DebugHold(room, OcellusPhase.P2_TO_P3))
        room.blade?.let { if (!it.isFinished) it.finish() }
        room.blade = null
        room.setHangingBlade(null)
        npc.transform(TfaIds.Npc.OCELLUS_P3)
        Bridge.setCombatLevel(npc, room.cfg.combatLevel.p3)
        Bridge.playMusic(room.viewers(), Bridge.TRACK_P3, TfaIds.Midi.OCELLUS_P3, TfaIds.Dbrow.MUSIC_OCELLUS_P3)
        room.enter(room.createP3())
        return true
    }

    /** A do-nothing phase used to end the current controller cleanly while jumping. */
    private class DebugHold(room: OcellusRoom, phase: OcellusPhase) : PhaseController(room, phase) {
        override fun begin() {}
        override fun onBossZeroHp(): Boolean = true
    }

    // ---- ::tfa ocellus ------------------------------------------------------------------------------------------

    const val USAGE = "ocellus <here|stop|skipto <phase>|hp <pct>|dump|met <0|1>>"

    fun command(player: Player, args: List<String>) {
        val sub = args.firstOrNull()?.lowercase() ?: "dump"
        when (sub) {
            "here", "start" -> startDebugRun(player)
            "stop" -> stopDebugRun(player)
            "met" -> {
                val v = args.getOrNull(1)?.toIntOrNull() ?: 1
                player.varManager.sendBit(TfaIds.Varbit.OCELLUS_MET, if (v == 0) 0 else 1)
                player.sendMessage("tfa_ocellus_met = ${if (v == 0) 0 else 1}")
            }
            else -> {
                val room = roomOf(player) ?: run {
                    player.sendMessage("No Ocellus encounter: use ::tfa ocellus here (dev world) or enter the raid's room 8.")
                    return
                }
                when (sub) {
                    "skipto" -> {
                        val ph = args.getOrNull(1) ?: ""
                        player.sendMessage(if (skipTo(room, ph)) "Ocellus: skipped to $ph." else "Ocellus: cannot skip to \"$ph\" from ${room.phase}.")
                    }
                    "centre" -> player.sendMessage(if (skipTo(room, "p1centre")) "Ocellus: centre sequence." else "Not in P1.")
                    "hp" -> {
                        val pct = args.getOrNull(1)?.toIntOrNull()
                        if (pct == null) player.sendMessage("Usage: ::tfa ocellus hp <0-100>")
                        else player.sendMessage(if (room.debugSetHpPercent(pct)) "Ocellus HP set to $pct%." else "No damageable Ocellus in ${room.phase}.")
                    }
                    else -> room.debugState().forEach(player::sendMessage)
                }
            }
        }
    }

    private fun roomOf(player: Player): OcellusRoom? =
        (TfaServices.raids.encounterOf(player) as? OcellusRoom) ?: runs[player]?.room

    // ---- standalone debug run (static map, dev worlds) -----------------------------------------------------------

    private val runs = WeakHashMap<Player, DebugRun>()

    private class DebugContext(
        override val members: List<Player>,
        override val config: TfaConfigSnapshot,
        seed: Long,
    ) : TfaEncounterContext {
        override val room: TfaRoomId = TfaRoomId.OCELLUS
        override val scaling: Scaling = Scaling(config.scaling)
        override val scale: Int = scaling.partyScale(members.size, TfaDebug.forcedScale(members))
        override val instance: TfaInstance = StaticTfaInstance
        override val random: Random = Random(seed)
        override var encounterTicks: Int = 0
        override fun playersInArena(): List<Player> = members.filter { p ->
            !p.isFinished && !p.isDead && p.location.regionId == TfaIds.Region.OCELLUS
        }
    }

    private class DebugRun(val room: OcellusRoom, val context: DebugContext) {
        var stopped = false
        var completed = false
    }

    private fun startDebugRun(player: Player) {
        if (!TfaDebug.isDevelopmentWorld()) {
            player.sendMessage("The standalone Ocellus run is for development worlds; use the raid (::tfa skipto ocellus).")
            return
        }
        stopDebugRun(player)
        val nearby = World.getPlayers().filter { it !== player && it.location.withinDistance(player.location, 15) }
        val members = listOf(player) + nearby
        val ctx = DebugContext(members, TfaConfig.current, System.nanoTime())
        val room = OcellusRoom(ctx)
        val arrival = room.onPlane(StaticTile(4127, 5662))
        for (m in members) m.setLocation(room.fx.loc(arrival))
        val run = DebugRun(room, ctx)
        members.forEach { runs[it] = run }
        WorldTasksManager.schedule(object : TickTask() {
            override fun run() {
                if (run.stopped) {
                    stop()
                    return
                }
                try {
                    if (ticks == 1) {
                        room.build()
                        members.forEach(room::onArenaEntry)
                    }
                    if (ticks >= 1) {
                        if (room.phase.ordinal > OcellusPhase.INTRO.ordinal) ctx.encounterTicks++
                        room.tick()
                        if (!run.completed && room.isComplete()) {
                            run.completed = true
                            room.onComplete()
                        }
                        val wiped = room.phase.ordinal in OcellusPhase.P1.ordinal..OcellusPhase.P4.ordinal &&
                            members.none { !it.isDead && !it.isFinished && it.location.regionId == TfaIds.Region.OCELLUS }
                        if (wiped) {
                            room.onWipe()
                            finishRun(run)
                            stop()
                            return
                        }
                    }
                } catch (t: Throwable) {
                    org.slf4j.LoggerFactory.getLogger(OcellusDebug::class.java).error("Ocellus debug run failed", t)
                    finishRun(run)
                    stop()
                    return
                }
                ticks++
            }
        }, 0, 0)
        player.sendMessage("Ocellus debug run started for ${members.size} player(s), scale ${ctx.scale}. Talk to the Hooded Figure, or ::tfa ocellus skipto p1.")
    }

    private fun stopDebugRun(player: Player) {
        val run = runs[player] ?: return
        finishRun(run)
        player.sendMessage("Ocellus debug run stopped.")
    }

    private fun finishRun(run: DebugRun) {
        if (run.stopped) return
        run.stopped = true
        run.room.teardown()
        runs.entries.removeIf { it.value === run }
    }
}
