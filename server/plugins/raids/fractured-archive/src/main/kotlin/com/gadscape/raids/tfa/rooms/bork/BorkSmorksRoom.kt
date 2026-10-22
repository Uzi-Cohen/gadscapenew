package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.encounter.TfaEncounter
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.gadscape.raids.tfa.rooms.bork.BorkSmorksBrain.Special
import com.gadscape.raids.tfa.rooms.bork.Timeline.Tag
import com.zenyte.game.util.Direction
import com.zenyte.game.world.Projectile
import com.zenyte.game.world.entity.ForceTalk
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities
import org.slf4j.LoggerFactory

/**
 * Room 5, Bork and the Smorks (spec §4.3, WU-22): region 16727, section 1 east, a sealed 15x17 arena with the Smorks'
 * cart in the middle.
 *
 * - [build] spawns Bork (SW 4194,5597, facing west) and the Smorks (on the cart, SW 4190,5598).
 * - [onArenaEntry] runs the seq prefetch 10010, remembers the arrival order (the first arrival is Bork's first target)
 *   and plays the room music once the fight is on.
 * - [start] (first transporter use): HP scaling for the party, the dual HUD, music 888, the brain starts.
 * - [tick]: timeline (impacts, sequence steps, death despawns), then [BorkSmorksBrain] (Bork, the Smorks, the oil at
 *   the end of the tick), then the HUD (spec §2.5 order).
 * - [isComplete]: both NPCs dead (and their death sequences played, `completeAfterDeathAnims`).
 * - [onComplete] (once), [onWipe], [teardown]: everything the room created is cancelled and removed.
 *
 * All coordinates here are static; [translate] / [toStatic] go through the raid's `TfaInstance`.
 */
class BorkSmorksRoom(override val context: TfaEncounterContext) : TfaEncounter, BorkHost {

    override val room: TfaRoomId get() = TfaRoomId.BORK

    /** The raid's snapshot of `bork_smorks.json`. */
    val config: BorkSmorksConfig = context.config[BorkSmorksConfig]

    override val contributionPool: Int get() = Bridge.contributionPool()

    override val phases: List<String> = PHASES

    internal val timeline = Timeline()
    private val shadow = CartShadow(CartShadow.Mode.of(config.shadow.mode), Bridge::line)
    internal val brain = BorkSmorksBrain(config, timeline, context.random, this, shadow)

    /** For the NPCs' trait getters, which can run while this room is still being constructed. */
    internal val brainOrNull: BorkSmorksBrain? get() = brainField
    private val brainField: BorkSmorksBrain? = brain

    private var bork: Bork? = null
    private var smorks: Smorks? = null
    private val hud = Bridge.DualHud(BorkIds.HUD_NPC)
    private val primary = Bridge.PrimaryVarbits()
    private val oilLocs = Bridge.OilLocs(config.smorks.oilLocType)
    private val fighterMap = LinkedHashMap<Player, PlayerFighter>()
    private val arrivals = ArrayList<Player>()
    private var hudBorkMax = 0
    private var hudSmorksMax = 0

    private var built = false
    private var started = false
    private var completed = false
    private var tornDown = false

    internal val borkNpc: Bork? get() = bork
    internal val smorksNpc: Smorks? get() = smorks

    // ================================================================================== TfaEncounter lifecycle

    override fun build() {
        if (built) return
        built = true
        bork = Bork(this, context, translate(config.bork.spawn), facing(config.bork.spawnFacing)).also { it.spawn() }
        smorks = Smorks(this, context, translate(config.smorks.spawn), facing(config.smorks.spawnFacing)).also { it.spawn() }
    }

    override fun onArenaEntry(player: Player) {
        Bridge.prefetch(player)
        if (player !in arrivals) arrivals += player
        if (started && !completed) Bridge.music(player)
    }

    override fun start() {
        if (started || tornDown) return
        if (!built) build()
        started = true
        // The party scale is fixed now; re-scale untouched NPCs built before the scale was known.
        bork?.let { if (it.hitpoints >= it.scaledMaxHp) it.applyScaling() }
        smorks?.let { if (it.hitpoints >= it.scaledMaxHp) it.applyScaling() }
        bork?.let(Bridge::hudShow)
        val inArena = context.playersInArena()
        for (p in inArena) Bridge.music(p)
        refreshFighters()
        val first = arrivals.firstOrNull { it in inArena } ?: inArena.firstOrNull()
        brain.start(first?.let { fighterMap[it] })
        syncHud()
        log.info("Bork and the Smorks started (scale {}, Bork {} HP, Smorks {} HP, first target {})",
            context.scale, bork?.scaledMaxHp, smorks?.scaledMaxHp, first?.username)
    }

    override fun tick() {
        if (!started || tornDown) return
        timeline.advance()
        if (completed) return
        refreshFighters()
        brain.tick()
        syncHud()
    }

    override fun onPlayerDeath(player: Player) {
        val f = fighterMap[player] ?: return
        brain.onFighterLost(f)
    }

    override fun isComplete(): Boolean = started && !tornDown && (completed || brain.bothDone)

    override fun onComplete() {
        if (completed) return
        completed = true
        // Death despawns stay queued (DEATH) when the room completes before they ran (completeAfterDeathAnims=false).
        timeline.cancel(Tag.BORK)
        timeline.cancel(Tag.SMORKS)
        timeline.cancel(Tag.FLIGHT)
        brain.oil.clear()
        oilLocs.clear()
        primary.clear(context.members)
        hud.closeAll()
        bork?.let(Bridge::hudHide)
        smorks?.let(Bridge::hudHide)
        log.info("Bork and the Smorks complete at tick {} (scale {})", timeline.now, context.scale)
    }

    override fun onWipe() {
        bork?.let(Bridge::hudHide)
        smorks?.let(Bridge::hudHide)
        teardown()
    }

    override fun teardown() {
        if (tornDown) return
        tornDown = true
        timeline.cancelAll()
        brain.oil.clear()
        oilLocs.clear()
        hud.closeAll()
        primary.clear(context.members)
        bork?.let { if (!it.isFinished) it.finish() }
        smorks?.let { if (!it.isFinished) it.finish() }
        bork = null
        smorks = null
        fighterMap.clear()
    }

    // ========================================================================================== debug hooks

    override fun debugSkipToPhase(phase: String): Boolean {
        // The raid (or `::tfa bork`) builds the room first; nothing to skip in an unbuilt or finished room.
        if (!built || tornDown || completed) return false
        if (!started) start()
        val b = bork
        val s = smorks
        when (phase.lowercase()) {
            PHASE_FIGHT -> {
                if (brain.borkDead || brain.smorksDead) return false
                b?.setHpPercent(100)
                s?.setHpPercent(100)
                brain.debugResume()
            }
            PHASE_TEMPO_66 -> {
                if (brain.borkDead || brain.smorksDead) return false
                b?.setHpPercent(66)
                s?.setHpPercent(66)
                brain.debugResume()
            }
            PHASE_TEMPO_33 -> {
                if (brain.borkDead || brain.smorksDead) return false
                b?.setHpPercent(33)
                s?.setHpPercent(33)
                brain.debugResume()
            }
            PHASE_DAZE -> {
                if (brain.borkDead) return false
                brain.debugResume()
                brain.daze()
            }
            PHASE_STOMP -> {
                if (brain.borkDead) return false
                brain.debugResume()
                brain.startStomp(fromDaze = false, cause = BorkSmorksBrain.Cause.MISSED_HEAVY)
            }
            PHASE_GONG -> {
                if (brain.borkDead) return false
                brain.debugResume()
                brain.startGong(fromDaze = false)
            }
            PHASE_INCENDIARY, PHASE_HEAVY -> {
                if (brain.smorksDead) return false
                val victim = brain.smorksTargets().firstOrNull() ?: return false
                brain.fireSpecial(if (phase.lowercase() == PHASE_HEAVY) Special.HEAVY else Special.INCENDIARY, victim)
            }
            PHASE_SMORKS_ENRAGED -> {
                if (brain.borkDead || brain.smorksDead) return false
                b?.setHitpoints(0) // sendDeath → onBorkDeath → the Smorks enrage
            }
            PHASE_BORK_ENRAGED -> {
                if (brain.borkDead || brain.smorksDead) return false
                s?.setHitpoints(0)
            }
            PHASE_DEATH -> {
                if (!brain.borkDead) b?.setHitpoints(0)
                if (!brain.smorksDead) s?.setHitpoints(0)
            }
            else -> return false
        }
        syncHud()
        return true
    }

    /** `::tfa hp <pct>`: both NPCs to [percent]% (the tempo and armour follow at once). */
    override fun debugSetHpPercent(percent: Int): Boolean {
        if (!built || tornDown || completed) return false
        if (!started) start()
        var any = false
        bork?.takeIf { !brain.borkDead }?.let { it.setHpPercent(percent); any = true }
        smorks?.takeIf { !brain.smorksDead }?.let { it.setHpPercent(percent); any = true }
        syncHud()
        return any
    }

    /** `::tfa bork hp <bork|smorks> <pct>`. */
    internal fun debugSetHp(which: String, percent: Int): Boolean {
        if (!built || tornDown || completed) return false
        if (!started) start()
        val npc: TfaBossNpc = when (which.lowercase()) {
            "bork" -> bork?.takeIf { !brain.borkDead }
            "smorks", "smork" -> smorks?.takeIf { !brain.smorksDead }
            else -> null
        } ?: return false
        npc.setHpPercent(percent)
        syncHud()
        return true
    }

    override fun debugState(): List<String> = buildList {
        add("Bork and the Smorks: built $built, started $started, completed $completed, torn down $tornDown, tick ${timeline.now}")
        addAll(brain.describe())
        add("  timeline: ${timeline.pending()} pending (bork ${timeline.pending(Tag.BORK)}, flight ${timeline.pending(Tag.FLIGHT)}, " +
            "death ${timeline.pending(Tag.DEATH)}); oil locs ${oilLocs.size}; HUD viewers ${hud.viewers}")
        add("  fighters: ${fighterMap.values.joinToString { "${it.name}@${it.tile}${if (it.alive) "" else " (out)"}" }}")
    }

    // ==================================================================================== room-level hooks

    /** Every processed hit on either NPC (the HUD shows it on the next sync; no HP thresholds besides the tempo). */
    internal fun onNpcHit() {}

    internal fun onBorkDeath() {
        if (tornDown) return
        brain.onBorkDeath()
        syncHud()
    }

    internal fun onSmorksDeath() {
        if (tornDown) return
        brain.onSmorksDeath()
        syncHud()
    }

    internal fun translate(tile: StaticTile): Location = context.instance.translate(tile)

    internal fun toStatic(location: Location): StaticTile =
        context.instance.toStatic(location) ?: StaticTile(-1, -1, location.plane)

    /** True while [player] is a living member inside this arena. */
    internal fun isFighting(player: Player): Boolean =
        !player.isDead && !player.isFinished && player in context.members && BorkArena.inBox(toStatic(player.location))

    private fun refreshFighters() {
        val inArena = context.playersInArena()
        for (p in inArena) fighterMap.getOrPut(p) { PlayerFighter(p, this) }
        for ((p, f) in fighterMap) if (!f.alive && p !in inArena) brain.onFighterLost(f)
    }

    private fun syncHud() {
        val b = bork
        val s = smorks
        if (b != null && b.scaledMaxHp > 0) hudBorkMax = b.scaledMaxHp
        if (s != null && s.scaledMaxHp > 0) hudSmorksMax = s.scaledMaxHp
        if (hudBorkMax <= 0 || hudSmorksMax <= 0) return
        val borkHp = if (b == null || brain.borkDead || b.isFinished) 0 else b.hitpoints
        val smorksHp = if (s == null || brain.smorksDead || s.isFinished) 0 else s.hitpoints
        hud.sync(fighterMap.values.filter { it.alive }.map { it.player }, borkHp, hudBorkMax, smorksHp, hudSmorksMax)
    }

    private fun playerOf(f: Fighter): Player? = (f as? PlayerFighter)?.player

    /**
     * NPC accuracy roll of [style] against [target] with the raid's random (Zenyte's `CombatUtilities.getHitAccuracy`
     * formula) when [roll], then a uniform 0..[max]. Godmode players take nothing.
     */
    private fun rolledHit(npc: NPC, target: Player, max: Int, style: AttackType, roll: Boolean): Int {
        if (Bridge.isGodmode(target) || max <= 0) return 0
        if (roll) {
            val accuracy = CombatUtilities.getHitAccuracy(npc, target, style)
            if (accuracy < context.random.nextDouble()) return 0
        }
        return CombatUtilities.clampMaxHit(npc, target, context.random.nextInt(max + 1))
    }

    // ============================================================================================= BorkHost

    override val borkSw: StaticTile? get() = bork?.takeIf { !it.isFinished }?.let { toStatic(it.location) }
    override val borkHp: Int get() = bork?.hitpoints ?: 0
    override val borkMaxHp: Int get() = bork?.maxHitpoints ?: 0

    override fun borkAnimate(seq: Int) {
        bork?.setAnimation(Animation(seq))
    }

    override fun borkSpot(spot: Int) {
        bork?.setGraphics(Graphics(spot))
    }

    override fun borkSpotAtCentre(spot: Int) {
        val sw = borkSw ?: return
        Bridge.graphic(spot, translate(BorkArena.middle(sw, BorkArena.BORK_SIZE)))
    }

    override fun borkSay(text: String) {
        bork?.setForceTalk(ForceTalk(text))
    }

    override fun borkFace(target: Fighter?) = face(bork, target)

    override fun borkChase(target: Fighter) {
        val b = bork ?: return
        val p = playerOf(target) ?: return
        Bridge.chaseStep(b, p)
    }

    override fun borkHalt() {
        bork?.resetWalkSteps()
    }

    /** The traits follow the brain's state on every hit ([Bork.traits]); nothing else to switch. */
    override fun borkDazed(dazed: Boolean) {}

    override fun borkHit(target: Fighter, kind: BorkHit, max: Int, rollAccuracy: Boolean) {
        val b = bork ?: return
        val p = playerOf(target) ?: return
        if (!isFighting(p) || Bridge.isGodmode(p)) return
        val damage = rolledHit(b, p, max, AttackType.CRUSH, rollAccuracy)
        // Lands this tick; Protect from Melee (× pfmMult via Bork.getMeleePrayerMultiplier) is applied as it lands.
        CombatUtilities.delayHit(b, -1, p, Hit(b, damage, HitType.MELEE))
    }

    override fun borkDamage(amount: Int) {
        val b = bork ?: return
        if (amount > 0 && !b.isDead && !b.isFinished) b.applyHit(Hit(amount, HitType.TYPELESS))
    }

    override fun borkTargetable(targetable: Boolean) {
        bork?.setTargetableState(targetable)
    }

    override fun borkDespawn() {
        bork?.let { if (!it.isFinished) it.finish() }
    }

    override fun primaryTarget(target: Fighter?) {
        primary.set(context.members, target?.let(::playerOf))
    }

    override val smorksHp: Int get() = smorks?.hitpoints ?: 0
    override val smorksMaxHp: Int get() = smorks?.maxHitpoints ?: 0

    override fun smorksAnimate(seq: Int) {
        smorks?.setAnimation(Animation(seq))
    }

    override fun smorksSpot(spot: Int) {
        smorks?.setGraphics(Graphics(spot))
    }

    override fun smorksSpotAtCentre(spot: Int, delayCycles: Int) {
        Bridge.graphic(spot, translate(BorkArena.middle(config.smorks.spawn, BorkArena.SMORKS_SIZE)), delayCycles)
    }

    override fun smorksSay(text: String) {
        smorks?.setForceTalk(ForceTalk(text))
    }

    override fun smorksFace(target: Fighter?) = face(smorks, target)

    override fun smorksProjectile(spot: Int, spec: BorkSmorksConfig.ProjectileSpec, target: Fighter): Int {
        val distance = BorkArena.chebyshev(BorkArena.middle(config.smorks.spawn, BorkArena.SMORKS_SIZE), target.tile)
        val s = smorks
        val p = playerOf(target)
        if (s != null && p != null) {
            Bridge.projectile(s, p, Projectile(spot, spec.startHeight, spec.endHeight, spec.delay, slopeDegrees(spec.slope), 0, 0, spec.perTile))
        }
        return spec.flightTicks(distance)
    }

    override fun smorksLob(spot: Int, spec: BorkSmorksConfig.ArcSpec, tile: StaticTile, flightTicks: Int) {
        val s = smorks ?: return
        // A fixed flight whatever the distance: duration pads the delay up to flightTicks × 30 cycles, no per-tile time.
        val duration = (flightTicks * CYCLES_PER_TICK - spec.delay).coerceAtLeast(1)
        Bridge.projectile(s, translate(tile), Projectile(spot, spec.startHeight, spec.endHeight, spec.delay, slopeDegrees(spec.slope), duration, 0, 0))
    }

    override fun smorksVolleyHit(target: Fighter, style: VolleyStyle, max: Int) {
        val s = smorks ?: return
        val p = playerOf(target) ?: return
        if (!isFighting(p)) return
        val attackType = if (style == VolleyStyle.RANGED) AttackType.RANGED else AttackType.MAGIC
        val damage = rolledHit(s, p, max, attackType, roll = true)
        // Prayer is checked as the hit lands (the engine's appendHitEntry): the right protection blocks it fully.
        if (!Bridge.isGodmode(p)) {
            CombatUtilities.delayHit(s, -1, p, Hit(s, damage, if (style == VolleyStyle.RANGED) HitType.RANGED else HitType.MAGIC))
        }
        if (style == VolleyStyle.RANGED) {
            p.setGraphics(Graphics(BorkIds.SPOT_BONE_IMPACT))
        } else {
            Bridge.graphic(BorkIds.SPOT_POTION_SPLASH, p.location)
            p.setGraphics(Graphics(BorkIds.SPOT_POTION_HIT))
        }
    }

    override fun smorksTargetable(targetable: Boolean) {
        smorks?.setTargetableState(targetable)
    }

    override fun smorksDespawn() {
        smorks?.let { if (!it.isFinished) it.finish() }
    }

    override fun fighters(): List<Fighter> = fighterMap.values.toList()

    override fun graphic(spot: Int, tile: StaticTile, delayCycles: Int) {
        Bridge.graphic(spot, translate(tile), delayCycles)
    }

    override fun damage(target: Fighter, amount: Int) {
        val p = playerOf(target) ?: return
        if (amount <= 0 || !isFighting(p) || Bridge.isGodmode(p)) return
        // No source: typeless room damage (cannonballs, oil) is not reduced by prayer and lands even after the Smorks die.
        p.applyHit(Hit(amount, HitType.TYPELESS))
    }

    override fun burn(target: Fighter, stacks: Int) {
        val p = playerOf(target) ?: return
        if (isFighting(p)) Bridge.burn(p, stacks)
    }

    override fun oilSpawn(tile: StaticTile, rotation: Int) {
        val live = translate(tile)
        oilLocs.spawn(live, rotation)
        Bridge.graphic(BorkIds.spotOilSplat(rotation), live)
    }

    override fun oilFade(tile: StaticTile, rotation: Int) {
        val live = translate(tile)
        Bridge.graphic(BorkIds.spotOilDespawn(rotation), live)
        oilLocs.animate(live, BorkIds.SEQ_OIL_FADE)
    }

    override fun oilRemove(tile: StaticTile) {
        oilLocs.remove(translate(tile))
    }

    // ================================================================================================ misc

    private fun face(npc: NPC?, target: Fighter?) {
        val n = npc ?: return
        val p = target?.let(::playerOf)
        if (p == null) {
            if (n.faceEntity != -1) n.setFaceEntity(null)
        } else if (!n.isFacing(p)) {
            n.setFaceEntity(p)
        }
    }

    private fun facing(name: String): Direction = when (name) {
        "north" -> Direction.NORTH
        "east" -> Direction.EAST
        "south" -> Direction.SOUTH
        else -> Direction.WEST
    }

    /** Client slope units (0-64) to the `Projectile` constructor's degrees. */
    private fun slopeDegrees(slope: Int): Int = (slope * 90 + 32) / 64

    /** A player in the arena, wrapped once so Bork can keep a target. */
    internal class PlayerFighter(val player: Player, private val room: BorkSmorksRoom) : Fighter {
        override val tile: StaticTile get() = room.toStatic(player.location)
        override val alive: Boolean get() = room.isFighting(player)
        override val name: String get() = player.name
        override fun toString(): String = "PlayerFighter(${player.username})"
    }

    companion object {
        const val PHASE_FIGHT = "fight"
        const val PHASE_TEMPO_66 = "tempo66"
        const val PHASE_TEMPO_33 = "tempo33"
        const val PHASE_DAZE = "daze"
        const val PHASE_STOMP = "stomp"
        const val PHASE_GONG = "gong"
        const val PHASE_INCENDIARY = "incendiary"
        const val PHASE_HEAVY = "heavy"
        const val PHASE_SMORKS_ENRAGED = "smorks_enraged"
        const val PHASE_BORK_ENRAGED = "bork_enraged"
        const val PHASE_DEATH = "death"

        @JvmField
        val PHASES: List<String> = listOf(
            PHASE_FIGHT, PHASE_TEMPO_66, PHASE_TEMPO_33, PHASE_DAZE, PHASE_STOMP, PHASE_GONG, PHASE_INCENDIARY,
            PHASE_HEAVY, PHASE_SMORKS_ENRAGED, PHASE_BORK_ENRAGED, PHASE_DEATH,
        )

        private const val CYCLES_PER_TICK = 30

        private val log = LoggerFactory.getLogger(BorkSmorksRoom::class.java)
    }
}
