package com.gadscape.raids.tfa.rooms.moia

import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.encounter.TfaEncounter
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.util.Direction
import com.zenyte.game.world.Projectile
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Entity
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
 * Room 6, Moia (spec §4.4; section 2 west; static region 16216). One instance per raid, created by the
 * `TfaEncounters` factory [MoiaModule] registers.
 *
 * State machine (spec §4.4), one scripted timeline ([Timeline]) cancelled as a whole on wipe and teardown:
 * ```
 * BUILT ─start()─▶ SPAWNING (2 t) ─▶ PHASE1 ─HP ≤ 1/3─▶ ENRAGE_TRANSITION (2 t) ─▶ CHARGING ⟲ (25 t, resolve)
 *                                                                     any state ─HP 0─▶ DYING (6 t) ─▶ COMPLETE
 * ```
 * - **SPAWNING**: Moia appears on the start arm with spot 4220; the binder set spawns with spot 4224.
 * - **PHASE1**: an auto slot every 5 t ([MoiaAttackPlan]): ranged dagger (seq 14786, projectile 4214) at a random
 *   player in the graveyard; every 4th slot the dagger is followed by the teleport special (T+1 seq 14788 + spot
 *   4219, T+2 move to another arm + spot 4220, T+3 cast 14785 + projectile 4213, magic hit T+6 + impact 4216);
 *   every 30 t one slot is the infect cast (14785 + projectile 4218, ground spot 4217, virus). The binder formation
 *   ([BinderFormation]) shuffles every 8 t and refills one binder every 12 t; binders stab anyone within 2 tiles
 *   every 5 t (seq 14791, hit +1 t).
 * - **ENRAGE_TRANSITION**: binders removed; Moia teleports (14788 + 4219) to the centre (4220) next tick, where the
 *   8-binder diamond appears (4224, then the 4223 loop).
 * - **CHARGING**: chant 14787 looped, no attacks, headbar 85 full → empty over 25 t (and the HUD extra bar). On
 *   completion ([MoiaEnrage]): survivors play 4222 and every player takes typeless `min(99, 25 × survivors)` with spot
 *   4225; the diamond refills to 8 and the next charge starts. No survivors: no explosion, a new set.
 * - **DYING**: death 14784 + spot 4221 (5 t); binders die (14792); viruses cleared; then the room is complete.
 *
 * Damage resolution (spec §2.5): the threshold check runs first each tick, then the timeline (landing hits, the
 * special's steps), then this tick's attacks; hits on players are queued for the player's own processing, which
 * applies protection prayers.
 */
internal class MoiaRoom(override val context: TfaEncounterContext) : TfaEncounter, MoiaDeathListener {

    enum class State { BUILT, SPAWNING, PHASE1, ENRAGE_TRANSITION, CHARGING, DYING, COMPLETE, WIPED, TORN_DOWN }

    override val room: TfaRoomId get() = TfaRoomId.MOIA

    override val contributionPool: Int get() = Bridge.contributionPool(context)

    override val phases: List<String> = listOf(PHASE_P1, PHASE_ENRAGE, PHASE_DEATH)

    val cfg: MoiaConfig = context.config[MoiaConfig]

    var state: State = State.BUILT
        private set

    val timeline = Timeline()
    private val plan = MoiaAttackPlan(cfg.attackSpeed, cfg.specialEveryNthAuto, cfg.infect.interval)
    private val enrage = MoiaEnrage(cfg.enrage)
    private val formation = BinderFormation<UndeadBinder>()
    private val virus: MoiaVirus = Bridge.virus(cfg.infect, context.random)

    var moia: MoiaNpc? = null
        private set

    /** Every binder spawned and not yet removed (dying ones included until they despawn). */
    private val binders = ArrayList<UndeadBinder>()

    /** Tiles of dead binders still lying there (new binders never spawn on them). */
    private val corpses = HashMap<UndeadBinder, StaticTile>()

    private var shuffleIndex = 0
    private var nextShuffleTick = Int.MAX_VALUE
    private var respawnCountdown = 0
    private var nextChantTick = Int.MAX_VALUE
    private var phase1StartTick = -1
    private var enrageTick = -1
    private var killTick = -1
    private var complete = false
    private var completed = false

    /** Set size for this party scale: `min(12, 2 + 2 × scale)`. */
    val setSize: Int = cfg.binders.setSize(context.scale)

    // ------------------------------------------------------------------------------------------- lifecycle

    override fun build() {
        // Nothing stands in the room before the fight: Moia "begins the fight outside of her arena" and teleports
        // in when the first player arrives (start()).
        log.debug("Moia room built (scale {}, binder set {})", context.scale, setSize)
    }

    override fun onArenaEntry(player: Player) {
        Bridge.onArenaEntry(player)
    }

    override fun start() {
        if (state != State.BUILT) return
        state = State.SPAWNING
        val arm = cfg.startArm
        val npc = MoiaNpc(context, translate(armTile(arm)), arm.facing.toDirection())
        npc.listener = this
        npc.arm = arm
        npc.spawn()
        npc.setGraphics(Graphics(MoiaIds.SPOT_TELEPORT_IN))
        npc.setFaceLocation(translate(MoiaGeometry.CENTRE))
        moia = npc
        formation.usePhase1(BinderFormation.Lattice.A)
        // Players may still be on their way in: keep the landing tile (tfa.json rooms.moia.entry) clear too.
        val avoid = playerTiles() + listOfNotNull(context.config.raid.rooms[TfaRoomId.MOIA]?.entry)
        val points = formation.pickPoints(setSize, context.random, avoid, cfg.binders.minPlayerDistance)
        points.forEach { spawnBinder(it, diamond = false) }
        timeline.schedule(cfg.spawnTicks, Timeline.Tag.PHASE1) { beginPhase1() }
    }

    override fun tick() {
        if (state == State.BUILT || state.terminal) return
        timeline.step()
        val t = timeline.now
        checkEnrageThreshold()
        timeline.runDue()
        when (state) {
            State.PHASE1 -> phase1Tick(t)
            State.CHARGING -> chargingTick(t)
            else -> {}
        }
        if (state != State.DYING && !state.terminal) {
            bindersTick()
            empowerLoops(t)
            virus.tick(population())
        }
    }

    override fun onPlayerDeath(player: Player) {
        virus.clear(player)
    }

    override fun isComplete(): Boolean = complete

    override fun onComplete() {
        if (completed) return
        completed = true
        state = State.COMPLETE
        cleanup()
    }

    override fun onWipe() {
        if (state.terminal) return
        state = State.WIPED
        cleanup()
    }

    override fun teardown() {
        cleanup()
        state = State.TORN_DOWN
    }

    /** Cancels everything and removes every NPC the room spawned. Safe to call more than once. */
    private fun cleanup() {
        timeline.cancelAll()
        plan.stop()
        enrage.stop()
        virus.clearAll()
        val players = context.members
        Bridge.hideHudExtraBar(players)
        moia?.let { m ->
            if (!m.isFinished) {
                Bridge.hideChargeBar(m)
                m.finish()
            }
        }
        binders.toList().forEach { if (!it.isFinished) it.finish() }
        binders.clear()
        corpses.clear()
        formation.clear()
    }

    // --------------------------------------------------------------------------------------------- phase 1

    private fun beginPhase1() {
        if (state != State.SPAWNING) return
        state = State.PHASE1
        val t = timeline.now
        phase1StartTick = t
        plan.start(t)
        nextShuffleTick = t + cfg.binders.shuffle
        respawnCountdown = cfg.binders.respawn
    }

    private fun phase1Tick(t: Int) {
        val m = moia ?: return
        if (m.isDead || m.isFinished) return
        when (plan.poll(t, canInfect = targets().any { !virus.isInfected(it) })) {
            MoiaAttackPlan.Attack.DAGGER -> dagger()
            MoiaAttackPlan.Attack.DAGGER_THEN_SPECIAL -> {
                dagger()
                scheduleSpecial()
            }
            MoiaAttackPlan.Attack.INFECT -> infectCast()
            null -> {}
        }
        if (t >= nextShuffleTick) {
            nextShuffleTick = t + cfg.binders.shuffle
            shuffle()
        }
        respawnTick()
    }

    /** Ranged dagger: seq 14786, projectile 4214, max 32, Protect from Missiles blocks. */
    private fun dagger() {
        val m = moia ?: return
        val target = randomTarget() ?: return
        m.setAnimation(Animation(MoiaIds.SEQ_DAGGER))
        m.faceEntity(target)
        val spec = cfg.projectiles.dagger
        World.sendProjectile(m, target, projectile(MoiaIds.SPOT_DAGGER_PROJ, spec))
        val damage = CombatUtilities.getRandomMaxHit(m, cfg.rangedMax, AttackType.RANGED, target)
        val delay = MoiaTiming.landingDelay(spec, footprintDistance(m, target), middleDistance(m, target))
        timeline.after(delay, Timeline.Tag.HIT) { landHit(m, target, Hit(m, damage, HitType.RANGED)) }
    }

    /** The teleport + blue special after the dagger of this tick (spec §4.4 attack table). */
    private fun scheduleSpecial() {
        val s = cfg.special
        timeline.schedule(s.teleportOut, Timeline.Tag.PHASE1) { teleportOut() }
        timeline.schedule(s.arrive, Timeline.Tag.PHASE1) {
            val m = moia ?: return@schedule
            arriveAt(MoiaArm.randomOther(m.arm, context.random))
        }
        timeline.schedule(s.cast, Timeline.Tag.PHASE1) { magicCast() }
    }

    /** Seq 14788 on Moia and the floor portal 4219 left where she stood. */
    private fun teleportOut() {
        val m = moia ?: return
        if (m.isDead || m.isFinished) return
        m.setAnimation(Animation(MoiaIds.SEQ_TELEPORT))
        World.sendGraphics(Graphics(MoiaIds.SPOT_TELEPORT_OUT), Location(m.middleLocation))
    }

    /** Moves Moia to [arm] (she never walks), facing the graveyard centre, with the arrival spot 4220. */
    private fun arriveAt(arm: MoiaArm) {
        val m = moia ?: return
        if (m.isDead || m.isFinished) return
        m.arm = arm
        m.setLocation(translate(armTile(arm)))
        m.setGraphics(Graphics(MoiaIds.SPOT_TELEPORT_IN))
        m.setFaceLocation(translate(MoiaGeometry.CENTRE))
    }

    /** Blue dagger: cast 14785, projectile 4213, magic max 34, lands `special.hitDelay` later with impact 4216. */
    private fun magicCast() {
        val m = moia ?: return
        if (m.isDead || m.isFinished) return
        val target = randomTarget() ?: return
        m.setAnimation(Animation(MoiaIds.SEQ_CAST))
        m.faceEntity(target)
        World.sendProjectile(m, target, projectile(MoiaIds.SPOT_MAGIC_PROJ, cfg.projectiles.magic))
        val damage = CombatUtilities.getRandomMaxHit(m, cfg.magicMax, AttackType.MAGIC, target)
        val delay = MoiaTiming.magicDelay(cfg, middleDistance(m, target))
        timeline.after(delay, Timeline.Tag.HIT) {
            if (!valid(target) || m.isDead || m.isFinished) return@after
            target.setGraphics(Graphics(MoiaIds.SPOT_MAGIC_IMPACT, 0, cfg.special.impactHeight))
            landHit(m, target, Hit(m, damage, HitType.MAGIC))
        }
    }

    /** Infect: cast 14785, smoke projectile 4218 at a random uninfected player; on landing spot 4217 and the virus. */
    private fun infectCast() {
        val m = moia ?: return
        val options = targets().filter { !virus.isInfected(it) }
        if (options.isEmpty()) {
            dagger()
            return
        }
        val target = options[context.random.nextInt(options.size)]
        m.setAnimation(Animation(MoiaIds.SEQ_CAST))
        m.faceEntity(target)
        val spec = cfg.projectiles.infect
        World.sendProjectile(m, target, projectile(MoiaIds.SPOT_INFECT_PROJ, spec))
        val delay = MoiaTiming.landingDelay(spec, footprintDistance(m, target), middleDistance(m, target))
        timeline.after(delay, Timeline.Tag.HIT) {
            if (!valid(target) || m.isDead || m.isFinished) return@after
            World.sendGraphics(Graphics(MoiaIds.SPOT_INFECT_GROUND), Location(target.location))
            virus.infect(target)
        }
    }

    // ---------------------------------------------------------------------------------------------- binders

    private fun spawnBinder(tile: StaticTile, diamond: Boolean): UndeadBinder {
        val b = UndeadBinder(context, translate(tile), facingTowardsCentre(tile))
        b.listener = this
        b.spawn()
        b.setGraphics(Graphics(MoiaIds.SPOT_BINDER_EMPOWER_IN))
        b.attackCooldown = cfg.binders.attackSpeed
        b.empowered = diamond
        b.empowerNextTick = timeline.now + MoiaIds.EMPOWER_LOOP_TICKS
        formation.add(b, tile)
        binders += b
        return b
    }

    /** One formation step (NE, SE, SW, NW in turn); binders walk one diagonal tile (walk seq 14790). */
    private fun shuffle() {
        val direction = BinderFormation.SHUFFLE_ORDER[shuffleIndex % BinderFormation.SHUFFLE_ORDER.size]
        shuffleIndex++
        val moves = formation.shuffle(direction, corpses.values) ?: return
        for ((b, tile) in moves) {
            if (b.isDead || b.isFinished) continue
            val to = translate(tile)
            b.resetWalkSteps()
            b.addWalkSteps(to.x, to.y, 1, false)
        }
    }

    /** One binder every `binders.respawn` ticks while the set is short, on a free point ≥ 2 tiles from players. */
    private fun respawnTick() {
        if (formation.size >= setSize) {
            respawnCountdown = cfg.binders.respawn
            return
        }
        if (--respawnCountdown > 0) return
        val tile = formation.pickPoints(1, context.random, playerTiles(), cfg.binders.minPlayerDistance, corpses.values).firstOrNull()
        if (tile == null) {
            respawnCountdown = 1 // nowhere free now: try again next tick
            return
        }
        spawnBinder(tile, diamond = false)
        respawnCountdown = cfg.binders.respawn
    }

    /** Each living binder stabs a random player within reach every `binders.attackSpeed` ticks. */
    private fun bindersTick() {
        val players = targets()
        for (b in binders.toList()) {
            if (b.dying || b.isDead || b.isFinished) continue
            if (b.attackCooldown > 0) b.attackCooldown--
            if (b.attackCooldown > 0) continue
            val inReach = players.filter { MoiaGeometry.footprintDistance(b.x, b.y, 1, it.x, it.y) <= cfg.binders.reach }
            if (inReach.isEmpty()) continue
            val target = inReach[context.random.nextInt(inReach.size)]
            b.setAnimation(Animation(MoiaIds.SEQ_BINDER_ATTACK))
            b.faceEntity(target)
            // Always accurate (cache attack 1 could never hit otherwise), 0-22, Protect from Melee blocks (INF/HOUSE).
            val damage = context.random.nextInt(cfg.binders.max + 1)
            timeline.after(cfg.binders.hitDelay, Timeline.Tag.HIT) {
                if (!b.dying && !b.isDead && !b.isFinished) landHit(b, target, Hit(b, damage, HitType.MELEE))
            }
            b.attackCooldown = cfg.binders.attackSpeed
        }
    }

    /** Diamond binders: 4224 once, then 4223 every 3 t (both spot seqs are 90 c). */
    private fun empowerLoops(t: Int) {
        for (b in binders) {
            if (!b.empowered || b.dying || b.isDead || b.isFinished) continue
            if (t >= b.empowerNextTick) {
                b.setGraphics(Graphics(MoiaIds.SPOT_BINDER_EMPOWER))
                b.empowerNextTick = t + MoiaIds.EMPOWER_LOOP_TICKS
            }
        }
    }

    override fun onBinderDeath(binder: UndeadBinder) {
        if (binder !in binders) return
        formation.remove(binder)?.let { corpses[binder] = it }
        virus.clear(binder)
        binder.empowered = false
        binder.setAnimation(Animation(MoiaIds.SEQ_BINDER_DEATH))
        timeline.schedule(cfg.binders.despawn, Timeline.Tag.BINDER) {
            if (!binder.isFinished) binder.finish()
            binders.remove(binder)
            corpses.remove(binder)
        }
    }

    /** Removes every binder at once (enrage repositioning), with no death seq. */
    private fun removeAllBinders() {
        timeline.cancel(Timeline.Tag.BINDER)
        binders.toList().forEach {
            virus.clear(it)
            if (!it.isFinished) it.finish()
        }
        binders.clear()
        corpses.clear()
        formation.clear()
    }

    // ----------------------------------------------------------------------------------------------- enrage

    private fun checkEnrageThreshold() {
        if (state != State.PHASE1 && state != State.SPAWNING) return
        val m = moia ?: return
        if (m.isDead || m.isFinished) return
        if (m.hitpoints <= MoiaTiming.enrageMark(m.maxHitpoints, cfg.enrageFraction)) beginEnrageTransition()
    }

    private fun beginEnrageTransition() {
        val m = moia ?: return
        state = State.ENRAGE_TRANSITION
        enrageTick = timeline.now
        plan.stop()
        timeline.cancel(Timeline.Tag.PHASE1)
        nextShuffleTick = Int.MAX_VALUE
        m.setAnimation(Animation(MoiaIds.SEQ_TELEPORT))
        World.sendGraphics(Graphics(MoiaIds.SPOT_TELEPORT_OUT), Location(m.middleLocation))
        removeAllBinders()
        formation.useDiamond(cfg.enrage.diamond.take(cfg.enrage.set))
        timeline.schedule(1, Timeline.Tag.ENRAGE) {
            arriveAt(MoiaArm.CENTRE)
            fillDiamond()
        }
        timeline.schedule(cfg.enrage.transitionTicks, Timeline.Tag.ENRAGE) { beginCharge() }
    }

    /** Refills the free diamond tiles (4224 on each new binder); a corpse still lying on a tile is cleared first. */
    private fun fillDiamond() {
        val free = formation.freePoints(emptyList(), 0)
        for (tile in free) {
            corpses.entries.filter { it.value == tile }.map { it.key }.forEach { corpse ->
                if (!corpse.isFinished) corpse.finish()
                corpses.remove(corpse)
                binders.remove(corpse)
            }
            spawnBinder(tile, diamond = true)
        }
    }

    private fun beginCharge() {
        val m = moia ?: return
        if (m.isDead || m.isFinished) return
        state = State.CHARGING
        val t = timeline.now
        enrage.begin(t)
        m.setAnimation(Animation(MoiaIds.SEQ_CHANT))
        nextChantTick = t + MoiaIds.CHANT_TICKS
        Bridge.showChargeBar(m, cfg.enrage.chargeTicks)
        if (cfg.enrage.hudExtraBar) Bridge.hudExtraBar(context.playersInArena(), cfg.enrage.chargeTicks, cfg.enrage.chargeTicks, first = true)
    }

    private fun chargingTick(t: Int) {
        val m = moia ?: return
        if (t >= nextChantTick) {
            m.setAnimation(Animation(MoiaIds.SEQ_CHANT))
            nextChantTick = t + MoiaIds.CHANT_TICKS
        }
        if (cfg.enrage.hudExtraBar) {
            Bridge.hudExtraBar(context.playersInArena(), enrage.remaining(t), cfg.enrage.chargeTicks, first = false)
        }
        if (enrage.isDue(t)) resolveCharge(t)
    }

    /** The charge completes: survivors explode on everyone, the diamond refills, the next charge starts. */
    private fun resolveCharge(t: Int) {
        val m = moia ?: return
        val survivors = binders.filter { it.empowered && !it.dying && !it.isDead && !it.isFinished }
        val outcome = enrage.resolve(survivors.size)
        if (outcome.exploded) {
            survivors.forEach {
                it.setGraphics(Graphics(MoiaIds.SPOT_BINDER_RELEASE))
                it.empowerNextTick = t + MoiaIds.EMPOWER_LOOP_TICKS // 4222 is 80 c, then back to the 4223 loop
            }
            for (p in targets()) {
                p.setGraphics(Graphics(MoiaIds.SPOT_PLAYER_EXPLODE))
                if (!Bridge.isGodmode(p)) p.applyHit(Hit(m, outcome.damage, HitType.TYPELESS))
            }
        }
        fillDiamond()
        beginCharge()
    }

    // ------------------------------------------------------------------------------------------------ death

    override fun onMoiaDeath(moia: MoiaNpc) {
        if (state == State.DYING || state.terminal) return
        state = State.DYING
        killTick = timeline.now
        plan.stop()
        enrage.stop()
        timeline.cancel(Timeline.Tag.PHASE1)
        timeline.cancel(Timeline.Tag.ENRAGE)
        timeline.cancel(Timeline.Tag.HIT)
        Bridge.hideChargeBar(moia)
        Bridge.hideHudExtraBar(context.members)
        moia.setAnimation(Animation(MoiaIds.SEQ_DEATH))
        moia.setGraphics(Graphics(MoiaIds.SPOT_DEATH))
        virus.clearAll()
        // Binders die with her (seq 14792), removed after `binders.despawn`.
        binders.toList().forEach { if (!it.dying && !it.isFinished) it.setHitpoints(0) }
        val end = maxOf(cfg.deathTicks, cfg.binders.despawn)
        timeline.schedule(cfg.deathTicks, Timeline.Tag.DEATH) { if (!moia.isFinished) moia.finish() }
        timeline.schedule(end, Timeline.Tag.DEATH) { complete = true }
    }

    // ------------------------------------------------------------------------------------------------ hits

    /**
     * Queues [hit] from [source] on [target] for the target's processing this tick (protection prayers apply there).
     * Skipped for players who left, died or have `::tfa godmode`.
     */
    private fun landHit(source: NPC, target: Player, hit: Hit) {
        if (!valid(target) || Bridge.isGodmode(target)) return
        target.scheduleHit(source, hit, -1)
    }

    private fun valid(p: Player): Boolean = !p.isDead && !p.isFinished && inGraveyard(p)

    // --------------------------------------------------------------------------------------------- helpers

    /** Living players inside the graveyard (they cannot leave it on foot). */
    fun targets(): List<Player> = context.playersInArena().filter { valid(it) }

    private fun randomTarget(): Player? {
        val list = targets()
        return if (list.isEmpty()) null else list[context.random.nextInt(list.size)]
    }

    private fun population(): List<Entity> {
        val out = ArrayList<Entity>()
        out.addAll(targets())
        binders.forEach { if (!it.dying && !it.isDead && !it.isFinished) out += it }
        return out
    }

    private fun inGraveyard(p: Player): Boolean {
        val s = context.instance.toStatic(p.location) ?: return false
        return MoiaGeometry.onFloor(s)
    }

    private fun playerTiles(): List<StaticTile> = targets().mapNotNull { context.instance.toStatic(it.location) }

    private fun armTile(arm: MoiaArm): StaticTile = cfg.arms[arm] ?: MoiaConfig.DEFAULT_ARMS.getValue(arm)

    private fun translate(tile: StaticTile): Location = context.instance.translate(tile)

    private fun footprintDistance(m: NPC, target: Entity): Int =
        MoiaGeometry.footprintDistance(m.x, m.y, m.size, target.x, target.y)

    private fun middleDistance(m: NPC, target: Entity): Int {
        val mid = m.middleLocation
        return maxOf(kotlin.math.abs(mid.x - target.x), kotlin.math.abs(mid.y - target.y))
    }

    private fun projectile(spot: Int, spec: MoiaConfig.ProjectileSpec): Projectile =
        Projectile(spot, spec.startHeight, spec.endHeight, spec.delay, MoiaTiming.slopeDegrees(spec.slope), 0, 0, spec.perTile)

    private fun facingTowardsCentre(tile: StaticTile): Direction {
        val dx = Integer.signum(MoiaGeometry.CENTRE.x - tile.x)
        val dy = Integer.signum(MoiaGeometry.CENTRE.y - tile.y)
        return Compass.entries.firstOrNull { it.dx == dx && it.dy == dy }?.toDirection() ?: Direction.SOUTH
    }

    private val State.terminal: Boolean get() = this == State.COMPLETE || this == State.WIPED || this == State.TORN_DOWN

    // ----------------------------------------------------------------------------------------------- debug

    override fun debugSkipToPhase(phase: String): Boolean = when (phase.lowercase()) {
        PHASE_P1 -> when (state) {
            State.BUILT -> { start(); true }
            State.SPAWNING -> { timeline.cancel(Timeline.Tag.PHASE1); beginPhase1(); true }
            State.PHASE1 -> true
            else -> false
        }
        PHASE_ENRAGE -> {
            if (state == State.BUILT) start()
            val m = moia
            if (m == null || state.terminal || state == State.DYING) false
            else {
                if (state == State.SPAWNING || state == State.PHASE1) {
                    m.setHitpoints(MoiaTiming.enrageMark(m.maxHitpoints, cfg.enrageFraction).coerceAtLeast(1))
                    TfaServices.bossHud.onHpChanged(m)
                }
                true
            }
        }
        PHASE_DEATH -> {
            val m = moia
            if (m == null || m.isDead || m.isFinished) false else { m.setHitpoints(0); true }
        }
        else -> false
    }

    override fun debugSetHpPercent(percent: Int): Boolean {
        val m = moia ?: return false
        if (m.isDead || m.isFinished) return false
        m.setHpPercent(percent)
        return true
    }

    override fun debugState(): List<String> {
        val m = moia
        val t = timeline.now
        val lines = ArrayList<String>()
        lines += "Moia: state $state, tick $t, scale ${context.scale}, set $setSize, binders ${formation.size} " +
            "(${if (formation.isDiamond) "diamond" else "lattice ${formation.lattice}"}), corpses ${corpses.size}"
        if (m != null) {
            lines += "  hp ${m.hitpoints}/${m.maxHitpoints} (enrage at ${MoiaTiming.enrageMark(m.maxHitpoints, cfg.enrageFraction)}), " +
                "arm ${m.arm}, level ${m.combatLevel}"
        }
        lines += "  autos ${plan.autos}, next auto ${plan.nextAutoTick.orNone()}, next infect ${plan.nextInfectTick.orNone()}, " +
            "next shuffle ${nextShuffleTick.orNone()}, respawn in $respawnCountdown"
        lines += "  charges ${enrage.completed}, charge left ${enrage.remaining(t)}, phase1 at $phase1StartTick, " +
            "enrage at $enrageTick, kill at $killTick, pending ${timeline.pending()}"
        lines += "  infected: ${virus.carriers.joinToString { carrierName(it) + " " + virus.ticksLeft(it) }.ifEmpty { "none" }}"
        return lines
    }

    /** `::tfa moia <action>` (see [MoiaDebug]); returns a message. */
    fun debugAction(action: String, args: List<String>): String {
        val m = moia ?: return "Moia has not spawned (state $state)."
        if (state.terminal || state == State.DYING) return "Moia room is $state."
        return when (action) {
            "special" -> if (state == State.PHASE1) { plan.forceSpecialNext(timeline.now + 1); "Next auto is the teleport special." } else "Only in phase 1."
            "infect" -> if (state == State.PHASE1) { plan.forceInfectDue(timeline.now + 1); "Next auto is the infect." } else "Only in phase 1."
            "shuffle" -> if (state == State.PHASE1) { nextShuffleTick = timeline.now + 1; "Binders shuffle next tick." } else "Only in phase 1."
            "charge" -> if (state == State.CHARGING) { enrage.forceDue(timeline.now + 1); "The charge completes next tick." } else "Only while charging."
            "arm" -> {
                val arm = args.firstOrNull()?.let { a -> MoiaArm.OUTER.firstOrNull { it.key.equals(a, true) } }
                if (arm == null || state != State.PHASE1) "Usage: ::tfa moia arm <W|E|S|N> (phase 1)." else { arriveAt(arm); "Moia moved to ${arm.key}." }
            }
            "binders" -> "Binders at ${formation.tiles().joinToString()}"
            else -> "Unknown action \"$action\"."
        }.also { log.debug("::tfa moia {} -> {} (hp {})", action, it, m.hitpoints) }
    }

    private fun carrierName(e: Entity): String = when (e) {
        is Player -> e.name
        is UndeadBinder -> "binder@${e.x},${e.y}"
        else -> e.toString()
    }

    private fun Int.orNone(): String = if (this == Int.MAX_VALUE || this < 0) "-" else toString()

    companion object {
        const val PHASE_P1 = "p1"
        const val PHASE_ENRAGE = "enrage"
        const val PHASE_DEATH = "death"

        private val log = LoggerFactory.getLogger(MoiaRoom::class.java)

        private fun Compass.toDirection(): Direction = when (this) {
            Compass.N -> Direction.NORTH
            Compass.NE -> Direction.NORTH_EAST
            Compass.E -> Direction.EAST
            Compass.SE -> Direction.SOUTH_EAST
            Compass.S -> Direction.SOUTH
            Compass.SW -> Direction.SOUTH_WEST
            Compass.W -> Direction.WEST
            Compass.NW -> Direction.NORTH_WEST
        }
    }
}
