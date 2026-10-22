package com.gadscape.raids.tfa.hud

import com.gadscape.raids.tfa.config.RaidConfig
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.RaidPhase
import com.gadscape.raids.tfa.raid.RoomRuntime
import com.gadscape.raids.tfa.raid.TfaRaid
import com.gadscape.raids.tfa.raid.TfaRaidMember
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.gadscape.raids.tfa.raid.Zone
import com.zenyte.game.model.ui.InterfacePosition
import com.zenyte.game.world.entity.player.Player
import org.slf4j.LoggerFactory

/**
 * The raid HUD, overlay 974 `tfa_hud` (spec §3.7, WU-14), for members and spectators of one raid:
 * - on open: varbit 16395 = slot + 1 (0 for external spectators), 16396 = 0, names (RUNCLIENTSCRIPT 10042, 8 strings,
 *   "" for empty slots), timer (10036), 16386 = 1;
 * - every tick: the orbs 16387-16394 ([OrbCodec]), spectate mode / slot 16405 / 16406, skip-phase 16407 (0 unless
 *   Practice), primary 16408-16415, path 16417, external spectator count 16425: each viewer gets only the values that
 *   changed since the last send (§5.4 "push changed values once per tick");
 * - timer: `hud.timerMode` TOTAL (default) shows the raid time since the Guardian touch, counting client-side;
 *   ROOM shows the room time. (-1, 1) before the Guardian; frozen at completion.
 * - on close: 974 closed, the HUD varbits zeroed (varps 5910-5912 except 16416 loot and the cape-stand bits).
 */
class TfaHud(val raid: TfaRaid) {

    private val log = LoggerFactory.getLogger(TfaHud::class.java)

    /** Last values sent per viewer (lower-case username), indexed like [TRACKED]. */
    private val sent = HashMap<String, IntArray>()

    /** Members flagged as "primary" (Ezekiel tank, Bork's target) for varbits 16408-16415 [HOUSE, RuneLite]. */
    private val primary = HashSet<Int>()

    private var dirty = true

    /** Forces a full recompute on the next tick (cheap: only changed values are sent). */
    fun markDirty() {
        dirty = true
    }

    // ============================================================================================== open / close

    /** Opens the HUD for a member who entered the instance. */
    fun open(member: TfaRaidMember) {
        openFor(member.player, member.slot + 1)
    }

    /** Opens 974 for [viewer]; [slotVarbit] is varbit 16395 (slot + 1, or 0 for an external spectator). */
    fun openFor(viewer: Player, slotVarbit: Int) {
        try {
            viewer.interfaceHandler.sendInterface(InterfacePosition.OVERLAY, TfaIds.Interface.HUD)
            val vm = viewer.varManager
            vm.sendBit(TfaIds.Varbit.PARTY_SLOT, slotVarbit)
            vm.sendBit(TfaIds.Varbit.HIDE_PLAYERS, 0)
            vm.sendBit(TfaIds.Varbit.PARTY_STATUS, 1)
            sent.remove(viewer.username.lowercase())
            sendNamesTo(viewer)
            sendTimerTo(viewer)
            dirty = true
        } catch (e: Exception) {
            log.warn("Fractured Archive: opening the HUD failed for {}", viewer.username, e)
        }
    }

    fun close(member: TfaRaidMember) = closeFor(member.player)

    /** Closes 974 and clears the HUD vars (spec §3.8 step 4 / §5.4). */
    fun closeFor(viewer: Player) {
        sent.remove(viewer.username.lowercase())
        if (viewer.isFinished) return
        try {
            if (viewer.interfaceHandler.isVisible(TfaIds.Interface.HUD)) viewer.interfaceHandler.closeInterface(TfaIds.Interface.HUD)
            val vm = viewer.varManager
            vm.sendVar(TfaIds.Varp.TEMP_TRANSMIT_1, 0) // 16386 party status, orbs p0-p5
            vm.sendVar(TfaIds.Varp.TEMP_TRANSMIT_2, 0) // orbs p6-p7, slot, hide, vault chests, spectate, skip phase
            for (id in TfaIds.Varbit.PRIMARY) vm.sendBit(id, 0)
            vm.sendBit(TfaIds.Varbit.CURRENT_PATH, 0)
            vm.sendBit(TfaIds.Varbit.MOIA_SHADOW_SHIELD, 0)
            vm.sendBit(TfaIds.Varbit.EXTERNAL_SPECTATORS, 0)
            MusicUnlock.resume(viewer)
        } catch (e: Exception) {
            log.warn("Fractured Archive: closing the HUD failed for {}", viewer.username, e)
        }
    }

    // =================================================================================================== names

    /** RUNCLIENTSCRIPT 10042 (8 names, "" for empty or left slots) to every viewer: on join, leave and reorder. */
    fun sendNames() {
        for (v in viewers()) sendNamesTo(v)
    }

    fun sendNamesTo(viewer: Player) {
        val names = Array(8) { i -> raid.members.getOrNull(i)?.takeIf { !it.left }?.name ?: "" }
        viewer.packetDispatcher.sendClientScript(TfaIds.Script.HUD_NAMES, *names)
    }

    // =================================================================================================== timer

    /** The timer arguments right now for [hud.timerMode]. */
    fun timerArgs(): OrbCodec.TimerArgs {
        val mode = raid.config.raid.hud.timerMode
        if (raid.guardianTouchTick < 0) return OrbCodec.TIMER_NOT_STARTED
        if (mode == RaidConfig.TimerMode.TOTAL) {
            return if (raid.completedTick >= 0) OrbCodec.frozen(raid.overallTicks) else OrbCodec.running(raid.overallTicks)
        }
        val rt = raid.activeRoom ?: return OrbCodec.TIMER_NOT_STARTED
        return if (rt.isActive) OrbCodec.running(rt.encounterTicks) else OrbCodec.frozen(rt.roomTicks.coerceAtLeast(0))
    }

    fun sendTimerTo(viewer: Player) {
        val args = timerArgs()
        viewer.packetDispatcher.sendClientScript(TfaIds.Script.HUD_TIMER, args.elapsed, args.frozen)
    }

    private fun sendTimer() {
        for (v in viewers()) sendTimerTo(v)
    }

    // ============================================================================================ raid events

    fun onEncounterStarted(rt: RoomRuntime, by: Player?) {
        if (by != null && (rt.room == TfaRoomId.EZEKIEL || rt.room == TfaRoomId.BORK)) {
            raid.memberOf(by)?.let { setPrimary(it, true) }
        }
        sendTimer()
        dirty = true
    }

    fun onEncounterCompleted(rt: RoomRuntime) {
        primary.clear()
        if (raid.config.raid.hud.timerMode == RaidConfig.TimerMode.ROOM) sendTimer()
        dirty = true
    }

    fun onRaidCompleted() {
        sendTimer()
        dirty = true
    }

    /** Marks [member] as the "primary" target (varbits 16408-16415, read by no client script [CACHE]; RuneLite). */
    fun setPrimary(member: TfaRaidMember, on: Boolean) {
        if (on) primary += member.slot else primary -= member.slot
        dirty = true
    }

    // ==================================================================================================== tick

    /** Everyone who sees this raid's HUD: members inside and the raid's spectators. */
    fun viewers(): List<Player> =
        raid.members.filter { it.inRaid && !it.player.isFinished }.map { it.player } + raid.spectators.players()

    /** Pushes the changed HUD varbits (once per tick, §5.4). */
    fun tick() {
        val orbs = IntArray(8) { slot -> orbOf(raid.members.getOrNull(slot)) }
        var primaryBits = 0
        for (s in primary) primaryBits = primaryBits or (1 shl s)
        val path = raid.route.pathValue
        val spectators = raid.spectators.count.coerceAtMost(100)
        for (m in raid.members) {
            if (!m.inRaid || m.player.isFinished) continue
            push(m.player, orbs, if (m.dead) 1 else 0, m.spectatedSlot + 1, primaryBits, path, spectators)
        }
        for ((viewer, slot) in raid.spectators.viewerSlots()) {
            push(viewer, orbs, 2, slot + 1, primaryBits, path, spectators)
        }
        dirty = false
    }

    private fun push(viewer: Player, orbs: IntArray, mode: Int, spectated: Int, primaryBits: Int, path: Int, spectators: Int) {
        val key = viewer.username.lowercase()
        val last = sent.getOrPut(key) { IntArray(TRACKED.size) { -1 } }
        val want = IntArray(TRACKED.size)
        for (i in 0 until 8) want[i] = orbs[i]
        want[8] = mode
        want[9] = spectated
        want[10] = 0 // skip-phase button: Practice only (§3.18, flag off)
        want[11] = path
        want[12] = spectators
        for (i in 0 until 8) want[13 + i] = (primaryBits shr i) and 1
        val vm = viewer.varManager
        for (i in TRACKED.indices) {
            if (last[i] == want[i]) continue
            last[i] = want[i]
            vm.sendBit(TRACKED[i], want[i])
        }
    }

    /** The orb of one slot ([OrbCodec]). */
    fun orbOf(member: TfaRaidMember?): Int {
        if (member == null || member.left) return OrbCodec.EMPTY
        val p = member.player
        return OrbCodec.orb(
            occupied = true,
            dead = member.dead || p.isDead,
            inActiveRoom = isInActiveRoom(member),
            hp = p.hitpoints,
            maxHp = p.maxHitpoints,
            cap = raid.config.raid.hud.orbHpCap,
        )
    }

    /** "Active room" = the arena of the running encounter, else the nexus (the vault once Ocellus is dead). */
    fun isInActiveRoom(m: TfaRaidMember): Boolean {
        if (!m.inRaid) return false
        val rt = raid.activeRoom?.takeIf { it.isActive }
        if (rt != null) return when (rt.room) {
            TfaRoomId.GUARDIAN -> m.zone == Zone.NEXUS
            TfaRoomId.OCELLUS -> m.zone == Zone.OCELLUS
            else -> m.zone == Zone.ARENA && m.room == rt.room
        }
        return when (raid.phase) {
            RaidPhase.VAULT -> m.zone == Zone.VAULT || m.zone == Zone.OCELLUS
            else -> m.zone == Zone.NEXUS
        }
    }

    companion object {
        /** The diffed varbits, in [push] order: orbs 0-7, spectate mode, spectated slot, skip phase, path, spectators, primary 0-7. */
        val TRACKED: IntArray by lazy {
            val v = TfaIds.Varbit
            intArrayOf(
                v.ORBS[0], v.ORBS[1], v.ORBS[2], v.ORBS[3], v.ORBS[4], v.ORBS[5], v.ORBS[6], v.ORBS[7],
                v.SPECTATE_MODE, v.SPECTATE_SLOT, v.SKIP_PHASE_MODE, v.CURRENT_PATH, v.EXTERNAL_SPECTATORS,
                v.PRIMARY[0], v.PRIMARY[1], v.PRIMARY[2], v.PRIMARY[3], v.PRIMARY[4], v.PRIMARY[5], v.PRIMARY[6], v.PRIMARY[7],
            )
        }
    }
}
