package com.gadscape.raids.tfa.raid

import com.gadscape.raids.tfa.config.TfaConfigSnapshot
import com.gadscape.raids.tfa.death.TfaDeathHandler
import com.gadscape.raids.tfa.death.WipeHandler
import com.gadscape.raids.tfa.debug.TfaDebug
import com.gadscape.raids.tfa.encounter.Hazards
import com.gadscape.raids.tfa.encounter.Scaling
import com.gadscape.raids.tfa.encounter.TfaEncounters
import com.gadscape.raids.tfa.hud.MusicUnlock
import com.gadscape.raids.tfa.hud.Prefetch
import com.gadscape.raids.tfa.hud.TfaHud
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.points.ContributionLedger
import com.gadscape.raids.tfa.points.PointsConfig
import com.gadscape.raids.tfa.spectate.SpectatorGroup
import com.gadscape.raids.tfa.supplies.RaidSupplies
import com.zenyte.game.task.WorldTask
import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.util.Utils
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.player.Player
import org.slf4j.LoggerFactory
import java.util.EnumMap
import java.util.Random

/**
 * One Fractured Archive raid: the state machine of spec §2.3 over one [TfaBlockInstance] (§2.4), ticked once per server
 * tick by its [TfaRaidArea] (§2.5: scheduler, active encounter, hazards, HUD, spectators, nexus, watchdog).
 *
 * Created by [TfaRaids.create] from a Grouping Board party. Everything delayed goes through [scheduler] (cancellable).
 * Encounters plug in through `TfaEncounters` and get a [RoomRuntime] as their context.
 */
class TfaRaid internal constructor(
    val id: Int,
    val party: TfaPartyView?,
    /** The config snapshot the raid started with (§2.6: `::tfareload` does not touch running raids). */
    val config: TfaConfigSnapshot,
    /** The raid's random source (seeded per raid). */
    val random: Random,
    roster: List<Player>,
    val instance: TfaBlockInstance,
) {
    val scaling = Scaling(config.scaling)
    val framework: FrameworkConfig get() = config[FrameworkConfig]
    val pointsConfig: PointsConfig get() = config[PointsConfig]

    /** Members by slot (board order at creation, leader first); slots never move during a raid. */
    val members: List<TfaRaidMember> = roster.take(TfaIds.Misc.MAX_PARTY_SIZE).mapIndexed { i, p -> TfaRaidMember(i, p) }

    lateinit var area: TfaRaidArea
        internal set

    val scheduler = RaidScheduler()
    val route = TfaRoute()
    val ledger = ContributionLedger(pointsConfig)
    val hud = TfaHud(this)
    val nexus = NexusController(this)
    val spectators = SpectatorGroup(this)
    val supplies = RaidSupplies(this)

    var phase: RaidPhase = RaidPhase.CREATED
        private set

    var endReason: RaidEndReason? = null
        private set

    /** Raid ticks since creation. */
    var ticks: Int = 0
        private set

    /** Raid tick of the Guardian touch, or -1 (the total timer and Overall time start there, §3.7 / §3.14). */
    var guardianTouchTick: Int = -1
        private set

    /** Raid tick of Ocellus's death, or -1. */
    var completedTick: Int = -1
        private set

    /** Members present at the Guardian touch (party size for stats, thresholds and credits; 1-8). */
    var raidSize: Int = 0
        private set

    private val rooms = EnumMap<TfaRoomId, RoomRuntime>(TfaRoomId::class.java)

    /** The encounter that is running (state ACTIVE), or the last one started. */
    var activeRoom: RoomRuntime? = null
        private set

    private var lastActivityTick = 0
    private var emptySinceTick = -1

    var released = false
        private set

    val log = LoggerFactory.getLogger(TfaRaid::class.java)

    // ======================================================================================================== queries

    fun runtime(room: TfaRoomId): RoomRuntime? = rooms[room]

    fun runtimes(): Collection<RoomRuntime> = rooms.values

    fun memberOf(player: Player): TfaRaidMember? {
        val name = player.username.lowercase()
        return members.firstOrNull { it.username == name && !it.left }
    }

    /** Members who have not left (in slot order), including ones not yet entered and dead ones. */
    fun activeMembers(): List<TfaRaidMember> = members.filter { !it.left }

    /** Members inside the instance (not left, entered, not outside). */
    fun membersInside(): List<TfaRaidMember> = members.filter { it.inRaid }

    /** Living members inside [room]'s arena (the Guardian's arena is the nexus, Ocellus's is region 16472). */
    fun membersInArena(room: TfaRoomId): List<TfaRaidMember> = members.filter { m ->
        m.alive && when (room) {
            TfaRoomId.GUARDIAN -> m.zone == Zone.NEXUS
            TfaRoomId.OCELLUS -> m.zone == Zone.OCELLUS
            else -> m.zone == Zone.ARENA && m.room == room
        }
    }

    /** The scale an encounter starting now would use (§2.6: max(2, members inside the instance); `::tfa scale`). */
    fun currentScale(): Int {
        val inside = membersInside()
        return scaling.partyScale(inside.size, TfaDebug.forcedScale(inside.map { it.player }))
    }

    /** True while late joins are allowed (§3.4, `tfa.json lateJoin`). */
    val joinsOpen: Boolean
        get() = phase == RaidPhase.CREATED && guardianTouchTick < 0 &&
            config.raid.lateJoin == com.gadscape.raids.tfa.config.RaidConfig.LateJoinRule.UNTIL_GUARDIAN_TOUCH

    /** Ticks since the Guardian touch (the HUD's total timer), or -1. */
    val overallTicks: Int get() = if (guardianTouchTick < 0) -1 else (if (completedTick >= 0) completedTick else ticks) - guardianTouchTick

    fun translate(tile: StaticTile): Location = instance.translate(tile)

    // ===================================================================================================== creation

    /** Builds the nexus and the Guardian (raid CREATED). Called once by [TfaRaids.create]. */
    internal fun init() {
        area = TfaRaidArea(this)
        area.constructRegion()
        nexus.build()
        buildRoom(TfaRoomId.GUARDIAN)
        for (m in members) {
            if (m.player.isFinished) continue
            m.player.varManager.sendBit(TfaIds.Varbit.PARTY_STATUS, 2) // "Step Inside Now!" (§2.3)
        }
        lastActivityTick = 0
        TfaRaidEvents.fire("created") { it.onRaidCreated(this) }
        log.info("Fractured Archive raid {} created for {} ({}).", id, members.joinToString { it.name }, instance.describe())
    }

    /** Creates and builds the encounter of [room] (its region must be copied). Idempotent. */
    internal fun buildRoom(room: TfaRoomId): RoomRuntime? {
        rooms[room]?.let { return it }
        val factory = TfaEncounters.factory(room)
        val rt = RoomRuntime(this, room)
        rooms[room] = rt
        if (factory == null) {
            log.warn("Fractured Archive raid {}: no encounter registered for {}; the room is empty.", id, room)
            broadcast("<col=ff0000>${room.displayName} is not available yet; the room will complete when entered.")
            return rt
        }
        try {
            val enc = factory.create(rt)
            rt.encounter = enc
            enc.build()
        } catch (e: Exception) {
            log.error("Fractured Archive raid {}: building {} failed", id, room, e)
        }
        return rt
    }

    // ======================================================================================================= entering

    /**
     * A member enters the raid from the lobby (Archive Door, §3.4): teleport to the nexus arrival tile, open the HUD.
     * Returns false (with a message) when the member may not enter.
     */
    @JvmOverloads
    fun enter(player: Player, quiet: Boolean = false): Boolean {
        if (released || phase == RaidPhase.ENDED) return false
        val member = memberOf(player) ?: run {
            if (!quiet) player.sendMessage("You are not in a party.")
            return false
        }
        if (member.inRaid) return true
        if (member.entered || !joinsOpen) {
            if (!quiet) player.sendMessage("Your party has already started.")
            return false
        }
        admit(member, player)
        return true
    }

    /** Puts [member] into the nexus without the join checks (creation with `lateJoin = never`, debug). */
    internal fun admit(member: TfaRaidMember, player: Player = member.player) {
        member.player = player
        member.entered = true
        member.zone = Zone.NEXUS
        member.room = null
        RaidWorld.move(player, RaidWorld.landing(translate(config.raid.tiles.nexusArrival), framework.raid.landingSearchRadius))
        hud.open(member)
        player.varManager.sendBit(TfaIds.Varbit.PARTY_STATUS, 1)
        onNexusEntry(member)
        lastActivityTick = ticks
        emptySinceTick = -1
        TfaRaidEvents.fire("entered") { it.onMemberEntered(this, member) }
    }

    /** The member is (again) in the nexus: prefetch and Guardian arena entry while the Guardian is not done. */
    private fun onNexusEntry(member: TfaRaidMember) {
        val guardian = rooms[TfaRoomId.GUARDIAN] ?: return
        if (guardian.state == RoomState.BUILT || guardian.state == RoomState.ACTIVE) {
            Prefetch.send(member, TfaRoomId.GUARDIAN)
            guardian.participants += member
            safe("Guardian onArenaEntry") { guardian.encounter?.onArenaEntry(member.player) }
            if (guardian.isActive) MusicUnlock.play(member.player, TfaRoomId.GUARDIAN)
        }
    }

    /** Moves [member] back to the nexus arrival tile (Passageway Exit, revive after the Guardian, debug). */
    fun moveToNexus(member: TfaRaidMember) {
        member.zone = Zone.NEXUS
        member.room = null
        RaidWorld.move(member.player, RaidWorld.landing(translate(config.raid.tiles.nexusArrival), framework.raid.landingSearchRadius))
        onNexusEntry(member)
    }

    /** Moves [member] to [room]'s antechamber (section door Enter, room exit after completion). */
    fun moveToAntechamber(member: TfaRaidMember, room: TfaRoomId) {
        member.zone = Zone.ANTECHAMBER
        member.room = room
        val tile = RaidGeometry.antechamber(room, config.raid.nexus.antechamberInset)
        RaidWorld.move(member.player, RaidWorld.landing(translate(tile), framework.raid.landingSearchRadius), facingInto(room))
    }

    private fun facingInto(room: TfaRoomId): Compass = if (room.side == Side.WEST) Compass.W else Compass.E

    /**
     * Archive transporter "Use" (§2.4 room flow, §3.5 step 4): into the arena at the entry tile plus a spread, never on
     * an NPC or a blocked tile. The first use starts the encounter ("the first player to enter"). Refused after the
     * room completes.
     */
    fun useTransporter(player: Player, room: TfaRoomId) {
        val member = memberOf(player) ?: return
        if (member.dead) return
        val rt = rooms[room]
        if (rt == null || rt.state == RoomState.COMPLETE || rt.state == RoomState.TORN_DOWN || route.isComplete(room)) {
            player.sendMessage("The transporter is inert.")
            return
        }
        if (rt.state == RoomState.WIPED) return
        val entry = config.raid.rooms[room] ?: return
        val landing = RaidWorld.landing(translate(entry.entry), framework.raid.landingSearchRadius, entry.spread, random)
        member.zone = Zone.ARENA
        member.room = room
        RaidWorld.move(player, landing, entry.facing)
        enterArena(member, rt)
        if (!rt.started) startEncounter(room, player)
    }

    /** [member] arrived in [rt]'s arena (transporter, Ocellus corridor, debug). */
    internal fun enterArena(member: TfaRaidMember, rt: RoomRuntime) {
        rt.participants += member
        Prefetch.send(member, rt.room)
        safe("${rt.room} onArenaEntry") { rt.encounter?.onArenaEntry(member.player) }
        if (rt.isActive) MusicUnlock.play(member.player, rt.room)
        hud.markDirty()
    }

    /** Ocellus door Enter (§3.6): the corridor on instance plane 1 of region 16472; prefetch 10016. */
    fun enterOcellus(player: Player) {
        val member = memberOf(player) ?: return
        if (member.dead) return
        if (phase != RaidPhase.NEXUS_FINAL && phase != RaidPhase.ROOM_ACTIVE) return
        val rt = rooms[TfaRoomId.OCELLUS] ?: return
        if (rt.state == RoomState.COMPLETE) return
        member.zone = Zone.OCELLUS
        member.room = null
        RaidWorld.move(player, RaidWorld.landing(translate(config.raid.tiles.ocellusCorridor), framework.raid.landingSearchRadius), Compass.N)
        enterArena(member, rt)
    }

    /** The vault (§3.15; WU-29 calls this from The Blade's Commune / Travel): (4127,5775) plane 2 facing N. */
    fun enterVault(player: Player): Boolean {
        val member = memberOf(player) ?: return false
        if (completedTick < 0) return false
        nexus.copyVault()
        member.zone = Zone.VAULT
        member.room = null
        RaidWorld.move(player, RaidWorld.landing(translate(config.raid.tiles.vaultArrival), framework.raid.landingSearchRadius), Compass.N)
        if (phase != RaidPhase.ENDED) phase = RaidPhase.VAULT
        hud.markDirty()
        return true
    }

    /** Passageway "Exit" (§3.5 step 5): back to the nexus arrival tile; from the antechamber only by default. */
    fun usePassageway(player: Player, room: TfaRoomId) {
        val member = memberOf(player) ?: return
        if (member.dead) return
        if (config.raid.passagewayExit == com.gadscape.raids.tfa.config.RaidConfig.PassagewayExitRule.ANTECHAMBER_ONLY &&
            member.zone == Zone.ARENA
        ) {
            player.sendMessage("You can't leave while the fight is underway.")
            return
        }
        moveToNexus(member)
    }

    // ===================================================================================================== encounters

    /**
     * Starts [room]'s encounter (the trigger: Guardian Touch, first transporter use, Ocellus intro "Yes"). Fixes the
     * party scale, locks joins at the Guardian touch, starts the timer, music and stats, then calls `start()`.
     * Returns false if the room is not built or already started.
     */
    fun startEncounter(room: TfaRoomId, by: Player?): Boolean {
        val rt = rooms[room] ?: return false
        if (rt.started || rt.state != RoomState.BUILT || phase == RaidPhase.ENDED) return false
        val inside = membersInside()
        rt.fixedScale = scaling.partyScale(inside.size, TfaDebug.forcedScale(inside.map { it.player }))
        rt.startTick = ticks
        rt.state = RoomState.ACTIVE
        activeRoom = rt
        phase = RaidPhase.ROOM_ACTIVE
        lastActivityTick = ticks
        if (room == TfaRoomId.GUARDIAN) {
            guardianTouchTick = ticks
            raidSize = inside.size.coerceIn(1, TfaIds.Misc.MAX_PARTY_SIZE)
            for (m in inside) if (m.zone == Zone.NEXUS) rt.participants += m
            party?.let { p -> for (m in members) if (!m.entered) m.player.varManager.sendBit(TfaIds.Varbit.PARTY_STATUS, 1) }
        }
        val present = inside.toList()
        hud.onEncounterStarted(rt, by)
        for (m in membersInArena(room)) MusicUnlock.play(m.player, room)
        spectators.onEncounterStarted(rt)
        TfaRaidEvents.fire("encounterStarted") { it.onEncounterStarted(this, room, present) }
        safe("$room start") { rt.encounter?.start() }
        log.info("Fractured Archive raid {}: {} started by {} at scale {}", id, room, by?.username ?: "-", rt.fixedScale)
        return true
    }

    private fun tickEncounter(rt: RoomRuntime) {
        val enc = rt.encounter
        if (enc == null) {
            // No encounter registered: the room completes as soon as someone is in it (keeps the route playable).
            if (rt.participants.isNotEmpty()) completeRoom(rt)
            return
        }
        safe("${rt.room} tick") { enc.tick() }
        safe("${rt.room} hazards") { Hazards.tick(rt) }
        val complete = try {
            enc.isComplete()
        } catch (e: Exception) {
            log.error("Fractured Archive raid {}: {} isComplete failed", id, rt.room, e)
            false
        }
        if (complete) completeRoom(rt) else checkWipe(rt)
    }

    /** §3.8: a wipe when every member who entered the active encounter is dead or gone. */
    private fun checkWipe(rt: RoomRuntime) {
        if (rt.state != RoomState.ACTIVE || rt.participants.isEmpty()) return
        val living = rt.participants.count { it.alive && inArenaOf(it, rt.room) }
        if (living > 0) return
        // Let the last death animation play out (the wipe follows once every dead participant is spectating).
        if (rt.participants.any { it.dead && !it.spectating && !it.left }) return
        // Nobody who entered is alive in the arena any more. With nobody left inside at all, memberLeft() ends the raid
        // as FAILED instead; otherwise the members still inside (dead ones, ones in the nexus) are wiped (§3.8).
        if (membersInside().isEmpty()) return
        wipe(rt)
    }

    private fun inArenaOf(m: TfaRaidMember, room: TfaRoomId): Boolean = when (room) {
        TfaRoomId.GUARDIAN -> m.zone == Zone.NEXUS
        TfaRoomId.OCELLUS -> m.zone == Zone.OCELLUS
        else -> m.zone == Zone.ARENA && m.room == room
    }

    /** Completion of [rt]: once. Banks points, stats, timer, revives, room exit, the next step of the route. */
    internal fun completeRoom(rt: RoomRuntime) {
        if (rt.state == RoomState.COMPLETE || rt.state == RoomState.TORN_DOWN || rt.state == RoomState.WIPED) return
        if (!rt.started) {
            rt.startTick = ticks
            rt.fixedScale = rt.fixedScale ?: currentScale()
        }
        rt.state = RoomState.COMPLETE
        rt.completeTick = ticks
        route.markComplete(rt.room)
        lastActivityTick = ticks
        safe("${rt.room} onComplete") { rt.encounter?.onComplete() }
        val shares = ledger.bank(rt.room)
        val present = membersInside()
        hud.onEncounterCompleted(rt)
        TfaRaidEvents.fire("encounterCompleted") { it.onEncounterCompleted(this, rt.room, rt.roomTicks, present) }
        val total = overallTicks
        for (m in present) {
            m.player.sendMessage("Room complete! Duration: <col=ef1020>${Utils.ticksToTime(rt.roomTicks.toLong())}</col>." +
                (if (total >= 0 && rt.room != TfaRoomId.GUARDIAN) " Total: <col=ef1020>${Utils.ticksToTime(total.toLong())}</col>." else ""))
        }
        log.info("Fractured Archive raid {}: {} complete in {} t; shares {}", id, rt.room, rt.roomTicks, shares)
        when (rt.room) {
            TfaRoomId.GUARDIAN -> {
                phase = RaidPhase.INTERMISSION
                reviveDead(rt.room) { m -> moveToNexus(m) }
                scheduler.schedule(config.raid.nexus.campRecopyDelayTicks, "nexus:camp") {
                    nexus.swapToCamp()
                    teardown(rt)
                }
                nexus.scheduleNavigator(1)
            }
            TfaRoomId.OCELLUS -> {
                completedTick = ticks
                phase = RaidPhase.VAULT
                reviveDead(rt.room) { m ->
                    m.zone = Zone.OCELLUS
                    RaidWorld.move(m.player, RaidWorld.landing(translate(config.raid.tiles.ocellusGateSpill), framework.raid.landingSearchRadius))
                }
                nexus.copyVault()
                onRaidCompleted()
            }
            else -> {
                phase = RaidPhase.INTERMISSION
                val section = rt.room.section ?: 0
                scheduler.schedule(config.raid.roomExitDelayTicks, "room:exit:${rt.room.configKey}") {
                    roomExit(rt)
                    teardown(rt)
                }
                if (section == 2) supplies.grantCredits()
                nexus.scheduleNavigator(section + 1)
            }
        }
        spectators.onEncounterCompleted(rt)
    }

    /** After completion + `roomExitDelayTicks`: everyone in the arena moves to the antechamber (§2.4 step 5). */
    private fun roomExit(rt: RoomRuntime) {
        for (m in members) {
            if (m.left || m.zone != Zone.ARENA || m.room != rt.room) continue
            if (m.dead) TfaDeathHandler.revive(this, m)
            moveToAntechamber(m, rt.room)
        }
        reviveDead(rt.room) { m -> moveToAntechamber(m, rt.room) }
    }

    /** Revives every member who died in [room] (§3.8: full HP and prayer, stats reset) and places them with [place]. */
    private fun reviveDead(room: TfaRoomId, place: (TfaRaidMember) -> Unit) {
        for (m in members) {
            if (m.left || !m.dead || m.diedIn != room) continue
            TfaDeathHandler.revive(this, m)
            place(m)
        }
    }

    /** Ocellus is dead (§3.14 / §3.15): timer frozen, completion jingle, KC / rewards through the listeners. */
    private fun onRaidCompleted() {
        val present = membersInside()
        hud.onRaidCompleted()
        val jingle = config.raid.completionJingle
        for (m in present) {
            if (jingle >= 0) m.player.music.playJingle(jingle)
            m.player.sendMessage("Fractured Archive completed! Duration: <col=ef1020>${Utils.ticksToTime(overallTicks.toLong())}</col>")
        }
        TfaRaidEvents.fire("raidCompleted") { it.onRaidCompleted(this, overallTicks, present) }
        log.info("Fractured Archive raid {} completed in {} t by {}", id, overallTicks, present.joinToString { it.name })
    }

    /** Despawns [rt]'s encounter (once). Ocellus is torn down only when the raid is released (The Blade lives there). */
    internal fun teardown(rt: RoomRuntime) {
        if (rt.state == RoomState.TORN_DOWN) return
        safe("${rt.room} teardown") { rt.encounter?.teardown() }
        Hazards.clear(rt)
        rt.state = RoomState.TORN_DOWN
    }

    // ========================================================================================================= deaths

    /** A member died inside the raid area (called by [TfaRaidArea.sendDeath]). Returns true if the raid handled it. */
    internal fun onDeath(player: Player, source: Entity?): Boolean {
        val member = memberOf(player) ?: return false
        if (member.dead) return true
        val room = currentRoomOf(member)
        return TfaDeathHandler.die(this, member, room, source)
    }

    /** The encounter [member] is fighting in, if any. */
    fun currentRoomOf(member: TfaRaidMember): TfaRoomId? = when (member.zone) {
        Zone.NEXUS -> TfaRoomId.GUARDIAN.takeIf { rooms[it]?.isActive == true }
        Zone.ARENA -> member.room
        Zone.OCELLUS -> TfaRoomId.OCELLUS
        else -> null
    }

    /** Called by the death handler once the member is in the spectate state. */
    internal fun afterDeath(member: TfaRaidMember, room: TfaRoomId?) {
        if (room != null) {
            val rt = rooms[room]
            safe("$room onPlayerDeath") { rt?.encounter?.onPlayerDeath(member.player) }
        }
        hud.markDirty()
        TfaRaidEvents.fire("death") { it.onMemberDeath(this, member, room) }
    }

    /** §3.8 wipe of [rt]. */
    internal fun wipe(rt: RoomRuntime) {
        if (rt.state != RoomState.ACTIVE || phase == RaidPhase.ENDED) return
        rt.state = RoomState.WIPED
        safe("${rt.room} onWipe") { rt.encounter?.onWipe() }
        log.info("Fractured Archive raid {}: wipe in {} after {} t", id, rt.room, rt.encounterTicks)
        val involved = members.filter { !it.left && it.entered }
        TfaRaidEvents.fire("wipe") { it.onWipe(this, rt.room, involved) }
        markEnded(RaidEndReason.WIPED)
        WipeHandler.wipe(this, rt)
        finishEnd()
    }

    // ==================================================================================================== leaving

    /**
     * [member] leaves the raid for good (logout, teleport out, the vault Rift, a wipe, the end): HUD closed, raid vars
     * cleared, credits reset. The caller moves the player. A leaving participant may wipe the others (§3.8).
     */
    internal fun memberLeft(member: TfaRaidMember, reason: LeaveReason) {
        if (member.left) return
        member.left = true
        val player = member.player
        if (member.dead) TfaDeathHandler.restoreAppearance(member)
        hud.close(member)
        supplies.reset(member)
        spectators.onMemberLeft(member)
        member.zone = Zone.OUTSIDE
        member.room = null
        hud.sendNames()
        TfaRaidEvents.fire("left") { it.onMemberLeft(this, member, reason) }
        log.info("Fractured Archive raid {}: {} left ({})", id, player.username, reason)
        if (phase != RaidPhase.ENDED) {
            activeRoom?.takeIf { it.isActive }?.let { checkWipe(it) }
            if (activeMembers().none { it.inRaid } && phase != RaidPhase.ENDED) {
                // Nobody inside any more. Members who never entered keep the raid open while joins are allowed.
                if (!joinsOpen || activeMembers().isEmpty()) end(if (completedTick >= 0) RaidEndReason.COMPLETED else RaidEndReason.FAILED)
            }
        }
    }

    /** The player left the instance area without the raid moving them (teleport, ::tele, the vault Rift by WU-29). */
    internal fun onLeftArea(player: Player, logout: Boolean) {
        val member = memberOf(player) ?: run {
            spectators.onLeftArea(player)
            return
        }
        if (!member.inRaid) return
        memberLeft(member, if (logout) LeaveReason.LOGOUT else LeaveReason.TELEPORT)
    }

    /** Sends [member] to the lobby arrival tile and removes them from the raid (vault Rift "Leave", debug). */
    fun leaveToLobby(player: Player, reason: LeaveReason = LeaveReason.LOBBY) {
        val member = memberOf(player)
        if (member != null) memberLeft(member, reason)
        RaidWorld.move(player, Location(config.raid.tiles.lobbyArrival.x, config.raid.tiles.lobbyArrival.y, config.raid.tiles.lobbyArrival.plane))
    }

    /** A party member who never entered left the party: drop their slot. */
    fun removeMember(player: Player) {
        val member = memberOf(player) ?: return
        if (member.inRaid) leaveToLobby(player, LeaveReason.REMOVED) else memberLeft(member, LeaveReason.REMOVED)
    }

    // ============================================================================================================ end

    /** Ends the raid (once): everyone still inside goes to the lobby; the instance is released after `releaseDelayTicks`. */
    fun end(reason: RaidEndReason) {
        if (phase == RaidPhase.ENDED) return
        markEnded(reason)
        finishEnd()
    }

    private fun markEnded(reason: RaidEndReason) {
        phase = RaidPhase.ENDED
        endReason = reason
        scheduler.cancelAll()
        for (rt in rooms.values) if (rt.state == RoomState.ACTIVE) {
            rt.state = RoomState.WIPED
            safe("${rt.room} onWipe") { rt.encounter?.onWipe() }
        }
        TfaRaidEvents.fire("ended") { it.onRaidEnded(this, reason) }
    }

    private fun finishEnd() {
        for (m in members) {
            if (m.left) continue
            if (m.inRaid) leaveToLobby(m.player, LeaveReason.RAID_ENDED) else memberLeft(m, LeaveReason.RAID_ENDED)
        }
        spectators.removeAll()
        safe("party.onRaidEnded") { party?.onRaidEnded() }
        log.info("Fractured Archive raid {} ended: {}", id, endReason)
        scheduler.schedule(config.raid.releaseDelayTicks, "release") { release() }
    }

    /** Tears every room down and frees the map (2 t after the last member left, §2.3). */
    internal fun release() {
        if (released) return
        released = true
        scheduler.cancelAll()
        for (rt in rooms.values) teardown(rt)
        nexus.cleanup()
        spectators.removeAll()
        for (p in area.players.toList()) {
            // Anyone still inside (a straggler, a ghost) goes to the lobby before the map disappears.
            val t = config.raid.tiles.lobbyArrival
            RaidWorld.move(p, Location(t.x, t.y, t.plane))
        }
        TfaRaids.unregister(this)
        // Unmapping the area while GlobalAreaManager iterates its areas (this runs inside our process()) would break
        // its loop: the map goes on the next world task tick, after the stragglers have moved.
        WorldTasksManager.schedule(WorldTask {
            area.unregister()
            instance.release()
            log.info("Fractured Archive raid {} released.", id)
        }, 1)
    }

    // =========================================================================================================== tick

    /** Once per server tick (§2.5 order: scheduler, encounter + hazards, HUD, spectators, nexus, watchdog). */
    internal fun process() {
        if (released) return
        ticks++
        scheduler.advance()
        if (released) return
        for (m in members) {
            if (!m.inRaid) continue
            val p = m.player
            if (p.isFinished || p.isNulled) memberLeft(m, LeaveReason.LOGOUT)
        }
        if (phase != RaidPhase.ENDED) {
            val rt = activeRoom
            if (rt != null && rt.state == RoomState.ACTIVE) tickEncounter(rt)
            // Safety net: a built room that completes without having been started through the raid.
            for (other in rooms.values) {
                if (other === rt || other.state != RoomState.BUILT) continue
                val done = try {
                    other.encounter?.isComplete() == true
                } catch (e: Exception) {
                    false
                }
                if (done) completeRoom(other)
            }
        }
        safe("hud") { hud.tick() }
        safe("spectators") { spectators.tick() }
        safe("nexus") { nexus.tick() }
        watchdog()
    }

    private fun watchdog() {
        if (phase == RaidPhase.ENDED) return
        if (activeRoom?.isActive == true) lastActivityTick = ticks
        val limit = config.raid.watchdogMinutes * 100 // 100 ticks a minute
        if (ticks - lastActivityTick >= limit) {
            broadcast("<col=ff0000>The Archive grows still. Your raid has ended.")
            end(RaidEndReason.FAILED)
            return
        }
        if (membersInside().isEmpty() && !joinsOpen) {
            if (emptySinceTick < 0) emptySinceTick = ticks
            if (ticks - emptySinceTick >= config.raid.releaseDelayTicks) end(RaidEndReason.FAILED)
        } else emptySinceTick = -1
    }

    // ========================================================================================================= helpers

    /** Forces a map rebuild for everyone inside (after a lazy region copy their client must see). */
    fun rebuildMaps() {
        for (p in area.players) {
            p.setNeedRegionUpdate(true)
            p.setLoadingRegion(true)
        }
    }

    fun broadcast(message: String) {
        for (m in members) if (!m.left && !m.player.isFinished) m.player.sendMessage(message)
    }

    internal inline fun safe(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            log.error("Fractured Archive raid {}: {} failed", id, what, e)
        }
    }

    // ========================================================================================================= routing

    /** Sets the phase (nexus flow). */
    internal fun setPhase(next: RaidPhase) {
        if (phase != RaidPhase.ENDED) phase = next
    }

    // =========================================================================================================== debug

    /**
     * `::tfa skipto <room>`: marks every earlier room of the route done (choosing [target]'s side for the earlier
     * sections when they are open), prepares [target] and moves everyone inside to it (the nexus for the Guardian, the
     * antechamber of a paired room, the Ocellus corridor). Returns a status line.
     */
    internal fun debugSkipTo(target: TfaRoomId): String {
        if (phase == RaidPhase.ENDED) return "The raid has ended."
        if (route.isComplete(target)) return "${target.displayName} is already done."
        val active = activeRoom?.takeIf { it.isActive }
        if (active != null && active.room != target) {
            safe("${active.room} onWipe (skip)") { active.encounter?.onWipe() }
            active.state = RoomState.COMPLETE
            route.markComplete(active.room)
            teardown(active)
        }
        if (target == TfaRoomId.GUARDIAN) {
            for (m in membersInside()) moveToNexus(m)
            return "In the nexus with the Guardian."
        }
        // The Guardian.
        if (!route.isComplete(TfaRoomId.GUARDIAN)) {
            if (guardianTouchTick < 0) {
                guardianTouchTick = ticks
                raidSize = membersInside().size.coerceIn(1, TfaIds.Misc.MAX_PARTY_SIZE)
            }
            rooms[TfaRoomId.GUARDIAN]?.let { rt ->
                rt.state = RoomState.COMPLETE
                teardown(rt)
            }
            route.markComplete(TfaRoomId.GUARDIAN)
            for (m in members) if (m.dead) TfaDeathHandler.revive(this, m)
            nexus.swapToCamp()
        }
        val lastSection = target.section ?: 4
        for (section in 1 until lastSection.coerceAtMost(4)) {
            if (section > 3) break
            if (route.room(section)?.let { route.isComplete(it) } == true) continue
            val side = route.side(section) ?: target.side ?: Side.EAST
            if (route.side(section) == null) nexus.commit(section, side, build = false)
            val room = TfaRoomId.of(section, side)
            rooms[room]?.let { rt ->
                if (rt.state != RoomState.TORN_DOWN) {
                    rt.state = RoomState.COMPLETE
                    teardown(rt)
                }
            }
            route.markComplete(room)
            if (section == 2) {
                supplies.grantCredits()
                nexus.activateQuartermaster()
            }
        }
        if (target.isPaired) {
            val section = target.section!!
            nexus.openSection(section)
            if (route.side(section) == null) nexus.commit(section, target.side!!)
            setPhase(RaidPhase.SECTION_OPEN)
            for (m in membersInside()) {
                if (m.dead) TfaDeathHandler.revive(this, m)
                moveToAntechamber(m, target)
            }
            return "In the antechamber of ${target.displayName}; use the transporter to start it."
        }
        // Ocellus.
        nexus.enterFinal()
        for (m in membersInside()) {
            if (m.dead) TfaDeathHandler.revive(this, m)
            enterOcellus(m.player)
        }
        return "In the Ocellus corridor; talk to the intro NPC to start."
    }

    /** `::tfa raid complete`: completes the running encounter now (its `onComplete` runs as usual). */
    internal fun debugCompleteActive(): String {
        val rt = activeRoom?.takeIf { it.isActive } ?: return "No encounter is running."
        completeRoom(rt)
        return "${rt.room.displayName} completed."
    }

    /** Lines for `::tfa dump raid`. */
    fun describe(): List<String> {
        val out = ArrayList<String>()
        out += "Raid $id: $phase${endReason?.let { " ($it)" } ?: ""}, tick $ticks, touch $guardianTouchTick, size $raidSize, scale now ${currentScale()}"
        out += "Instance: ${instance.describe()}; copied ${instance.copiedRegions().joinToString()}"
        out += "Route: ${route.describe()}"
        out += "Members: ${members.joinToString()}"
        for (rt in rooms.values) out += "Room ${rt.room.configKey}: ${rt.state}, start ${rt.startTick}, done ${rt.completeTick}, " +
            "scale ${rt.fixedScale ?: "-"}, participants ${rt.participants.joinToString { it.name }}"
        out += "Contribution:"
        out += ledger.describe()
        out += "Spectators: ${spectators.count}"
        out += "Scheduled:"
        out += scheduler.describe()
        return out
    }
}
