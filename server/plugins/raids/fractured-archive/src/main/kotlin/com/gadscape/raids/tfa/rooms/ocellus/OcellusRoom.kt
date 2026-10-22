package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.TfaEncounter
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.`object`.WorldObject
import org.slf4j.LoggerFactory

/** Phases of the room in order (spec §4.8 "Phase flow"). [key] is the `::tfa skipto` / Practice name. */
enum class OcellusPhase(val key: String) {
    BUILT("built"),
    INTRO("intro"),
    P1("p1"),
    P1_TO_P2("p1_to_p2"),
    P2("p2"),
    P2_TO_P3("p2_to_p3"),
    P3("p3"),
    P4("p4"),
    DYING("dying"),
    DEAD("dead"),
    ENDED("ended"),
}

/**
 * A phase of the fight: schedules its actions on the room timeline under its own tag ([tag], cancelled by [end]),
 * reacts to boss HP, deaths and arrivals. P3 and P4 (WU-28) plug in as further controllers ([OcellusRoom.createP3]).
 */
internal abstract class PhaseController(val room: OcellusRoom, val phase: OcellusPhase) {

    val tag: String get() = phase.key

    protected val timeline: OcellusTimeline get() = room.timeline
    protected val fx: Fx get() = room.fx

    /** Schedules [action] under this phase's tag (or `tag.sub`). */
    protected fun after(delay: Int, sub: String? = null, action: () -> Unit): OcellusTimeline.Entry =
        timeline.after(delay, if (sub == null) tag else "$tag.$sub", action)

    abstract fun begin()

    open fun tick() {}

    open fun onBossHp(before: Int, after: Int) {}

    open fun onBossDamaged(player: Player, amount: Int) {}

    /** Boss HP reached 0. Return true when handled (else the room ignores it). */
    open fun onBossZeroHp(): Boolean = false

    open fun onBladeDestroyed(blade: BladeHitboxNpc) {}

    open fun onPlayerDeath(player: Player) {}

    open fun onArenaEntry(player: Player) {}

    /** Cancels this phase's scheduled actions; subclasses clean up their own state. */
    open fun end() {
        timeline.cancel(tag)
    }

    open fun debugLines(): List<String> = emptyList()
}

/**
 * Room 8: Ocellus and The Blade (spec §4.8; WU-27 intro / P1 / P2, WU-28 P3 / P4 / death).
 *
 * Flow: [build] spawns the intro NPC 16671 → its "Yes" ([requestStart]) calls [start]: the vine gate closes,
 * corridor stragglers are moved in, Ocellus P1 16674 replaces the intro NPC → [OcellusP1] → [P1ToP2] (13 t) →
 * [OcellusP2] (3 portal barrages) → [P2ToP3] (21 t) → P3 ([createP3]; a placeholder until WU-28) → [OcellusDeath]
 * (15 t) → The Blade 16683 → [isComplete].
 *
 * Every delayed action is an entry of [timeline] (spec §2.5); [onWipe] / [teardown] cancel it all. All
 * coordinates are static (cache plane 1 + `planeShift`) and go through the raid's instance in [fx].
 */
class OcellusRoom(override val context: TfaEncounterContext) : TfaEncounter {

    override val room: TfaRoomId = TfaRoomId.OCELLUS

    override val contributionPool: Int get() = Bridge.contributionPool()

    override val phases: List<String> = listOf("intro", "p1", "p2", "p3", "p4")

    internal val cfg: OcellusConfig = context.config[OcellusConfig]
    internal val geo = ArenaGeometry(ArenaGeometry.ARENA_PLANE + cfg.planeShift)
    internal val timeline = OcellusTimeline()
    internal val fx = Fx(context.instance)
    internal val random get() = context.random
    internal val gate = VineGate(this)

    var phase: OcellusPhase = OcellusPhase.BUILT
        private set

    internal var boss: OcellusNpc? = null
    internal var intro: OcellusIntroNpc? = null
    internal var blade: BladeHitboxNpc? = null
    internal var theBlade: TheBladeNpc? = null

    private var current: PhaseController? = null
    private var hangingBlade: WorldObject? = null
    private var started = false
    private var completed = false
    private var tornDown = false
    private val prefetched = HashSet<Player>()

    // ---- static tiles (with planeShift) ------------------------------------------------------------------------

    internal fun onPlane(t: StaticTile): StaticTile = StaticTile(t.x, t.y, geo.plane)

    internal val introTile: StaticTile get() = onPlane(cfg.arena.introTile)
    internal val centreTile: StaticTile get() = onPlane(cfg.arena.centreTile)
    internal fun anchorTile(q: Quadrant): StaticTile = onPlane(cfg.arena.anchorTiles[q.ordinal])

    /** The hanging Blade loc 62706 at the arena centre (4127,5673) [CACHE r16472_locs]. */
    internal val bladeTile: StaticTile get() = geo.centre

    // ---- players ------------------------------------------------------------------------------------------------

    /** Players who see the HUD: everyone the raid counts as inside this arena. */
    internal fun viewers(): List<Player> = context.playersInArena()

    /** Living players standing in the octagon. */
    internal fun targets(): List<Player> = context.playersInArena().filter { p ->
        !p.isDead && !p.isFinished && fx.static(p.location)?.let { geo.contains(it) } == true
    }

    internal fun isTarget(p: Player): Boolean =
        !p.isDead && !p.isFinished && p in context.playersInArena() && fx.static(p.location)?.let { geo.contains(it) } == true

    internal fun tileOf(p: Player): StaticTile? = fx.static(p.location)

    internal fun playersIn(q: Quadrant): List<Player> = targets().filter { p -> tileOf(p)?.let { q.contains(it.x, it.y) } == true }

    // ---- traits -------------------------------------------------------------------------------------------------

    internal fun traitsFor(npcId: Int): CombatTraits = when (npcId) {
        TfaIds.Npc.OCELLUS_P1 -> if (cfg.p1.bindable) cfg.traits.p1 else cfg.traits.p1.copy(freezeResistPct = 100)
        TfaIds.Npc.OCELLUS_BLADE_HITBOX -> cfg.traits.blade
        else -> cfg.traits.p3
    }

    // ---- TfaEncounter -------------------------------------------------------------------------------------------

    override fun build() {
        if (phase != OcellusPhase.BUILT) return
        hangingBlade = fx.findStaticLoc(TfaIds.Loc.OCELLUS_HANGING_BLADE, bladeTile)
        val npc = OcellusIntroNpc(this, fx.loc(introTile))
        npc.spawn()
        intro = npc
        phase = OcellusPhase.INTRO
    }

    override fun onArenaEntry(player: Player) {
        if (prefetched.add(player)) Bridge.prefetch(player)
        if (started && gate.closed) gate.spill(player)
        current?.onArenaEntry(player)
    }

    /** The intro NPC's "Yes" by a raid member (spec §4.8 INTRO: any member may start it [HOUSE]). */
    internal fun requestStart(by: Player): Boolean {
        if (phase != OcellusPhase.INTRO || started) return false
        if (by !in context.members) return false
        return Bridge.requestStart(this, by)
    }

    /** Fight begins: gate closes, P1 spawns in place of the intro NPC (spec §4.8 INTRO). Idempotent. */
    override fun start() {
        startFight()
    }

    internal fun startFight(): Boolean {
        if (started || tornDown || phase.ordinal > OcellusPhase.INTRO.ordinal) return false
        if (phase == OcellusPhase.BUILT) build()
        started = true
        gate.close()
        val spawnTile = intro?.let { fx.static(it.location) } ?: introTile
        intro?.finish()
        intro = null
        val npc = OcellusNpc(this, TfaIds.Npc.OCELLUS_P1, fx.loc(spawnTile))
        npc.spawn()
        boss = npc
        Bridge.playMusic(viewers(), Bridge.TRACK_P1, TfaIds.Midi.OCELLUS, TfaIds.Dbrow.MUSIC_OCELLUS)
        enter(OcellusP1(this))
        return true
    }

    override fun tick() {
        if (tornDown) return
        timeline.tick()
        current?.tick()
    }

    override fun onPlayerDeath(player: Player) {
        current?.onPlayerDeath(player)
    }

    override fun isComplete(): Boolean = phase == OcellusPhase.DEAD

    override fun onComplete() {
        if (completed) return
        completed = true
        gate.open()
        Bridge.onKilled(this)
    }

    override fun onWipe() {
        stopAll()
        phase = OcellusPhase.ENDED
    }

    override fun teardown() {
        if (tornDown) return
        stopAll()
        tornDown = true
        boss?.let { b ->
            Bridge.meterHide(context.members)
            if (!b.isFinished) Bridge.hudHidden(b, context.members)
        }
        for (npc in listOf<NPC?>(boss, intro, blade, theBlade)) {
            if (npc != null && !npc.isFinished) npc.finish()
        }
        boss = null
        intro = null
        blade = null
        theBlade = null
        gate.open()
        restoreHangingBlade()
        fx.clear()
        if (phase != OcellusPhase.DEAD) phase = OcellusPhase.ENDED
    }

    private fun stopAll() {
        current?.end()
        current = null
        timeline.clear()
    }

    // ---- phase plumbing -----------------------------------------------------------------------------------------

    internal fun enter(next: PhaseController) {
        if (tornDown) return
        current?.end()
        current = next
        phase = next.phase
        log.debug("Ocellus room -> {}", next.phase)
        next.begin()
    }

    /** The P3 controller (WU-28 replaces the placeholder with the full P3/P4 flow). */
    internal fun createP3(): PhaseController = OcellusP3Placeholder(this)

    internal fun onBossHp(npc: OcellusNpc, before: Int, after: Int) {
        if (npc !== boss || before == after) return
        current?.onBossHp(before, after)
    }

    internal fun onBossDamaged(npc: OcellusNpc, player: Player, amount: Int) {
        if (npc !== boss) return
        current?.onBossDamaged(player, amount)
    }

    internal fun onBossZeroHp(npc: OcellusNpc) {
        if (npc !== boss) return
        if (current?.onBossZeroHp() != true) log.warn("Ocellus reached 0 HP in phase {} with no handler", phase)
    }

    internal fun onBladeDestroyed(npc: BladeHitboxNpc) {
        if (npc !== blade) return
        current?.onBladeDestroyed(npc)
    }

    /** Called by the death controller once The Blade 16683 stands: the room is complete. */
    internal fun markDead() {
        phase = OcellusPhase.DEAD
    }

    internal fun markPhase(p: OcellusPhase) {
        phase = p
    }

    // ---- the hanging Blade loc ----------------------------------------------------------------------------------

    /** Swaps the hanging Blade loc to [locId] (62706 / 62707-62709); null removes it. */
    internal fun setHangingBlade(locId: Int?) {
        val old = hangingBlade
        if (locId == null) {
            if (old != null) fx.removeLoc(old)
            hangingBlade = null
            return
        }
        hangingBlade = if (old != null) fx.swapLoc(old, locId) else fx.spawnLoc(locId, 10, 0, bladeTile)
    }

    private fun restoreHangingBlade() {
        val current = hangingBlade
        if (current?.id == TfaIds.Loc.OCELLUS_HANGING_BLADE) return
        // Remove (and untrack) the swapped state first: a later Fx.clear() removes by slot and would take the
        // restored loc with it.
        if (current != null) fx.removeLoc(current)
        val restored = fx.spawnLoc(TfaIds.Loc.OCELLUS_HANGING_BLADE, 10, 0, bladeTile)
        fx.release(restored)
        hangingBlade = restored
    }

    // ---- debug hooks ------------------------------------------------------------------------------------------

    override fun debugSkipToPhase(phase: String): Boolean = OcellusDebug.skipTo(this, phase)

    override fun debugSetHpPercent(percent: Int): Boolean {
        val npc = boss ?: return false
        if (phase != OcellusPhase.P1 && phase != OcellusPhase.P3 && phase != OcellusPhase.P4) return false
        val before = npc.hitpoints
        if (percent <= 0) {
            npc.setHitpoints(0) // sendDeath → onBossZeroHp
            return true
        }
        npc.setHpPercent(percent)
        onBossHp(npc, before, npc.hitpoints)
        return true
    }

    override fun debugState(): List<String> = buildList {
        add("Ocellus: phase=$phase tick=${timeline.now} pending=${timeline.pending()} started=$started completed=$completed")
        boss?.let { add("boss id=${it.id} hp=${it.hitpoints}/${it.maxHitpoints} at ${fx.static(it.location)} pfm=${it.protectFromMelee}") }
        blade?.let { add("blade hp=${it.hitpoints}/${it.maxHitpoints}") }
        add("gate closed=${gate.closed}; targets=${targets().joinToString { it.username }}")
        current?.let { addAll(it.debugLines()) }
    }

    internal val currentController: PhaseController? get() = current

    private companion object {
        private val log = LoggerFactory.getLogger(OcellusRoom::class.java)
    }
}
