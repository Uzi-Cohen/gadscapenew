package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.encounter.TfaEncounter
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.gadscape.raids.tfa.rooms.dreadnoth.Timeline.Tag
import com.zenyte.game.content.skills.prayer.Prayer
import com.zenyte.game.util.Direction
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.Projectile
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities
import org.slf4j.LoggerFactory

/**
 * Room 7, Dreadnoth the Jellied (spec §4.5, WU-24): region 16728, section 2 east.
 *
 * - [build] spawns Dreadnoth (SW 4189,5661, idle) in the copied region.
 * - [onArenaEntry] runs the seq prefetch 10014 (and the room music once the fight is on).
 * - [start] (first transporter use) starts the brain, the HUD and the music.
 * - [tick]: timeline (impacts, sequence steps, jelly rises), then the boss's decisions ([DreadnothBrain]), the jellies'
 *   targets ([JellySwarm]), then the blood tiles hurt whoever stands on them (spec §2.5 order).
 * - [isComplete]: Dreadnoth dead, his death wave spawned, and every jelly (forming ones included) dead.
 * - [onComplete] (once), [onWipe], [teardown]: everything the room created is cancelled and removed.
 *
 * All coordinates here are static; [translate] / [toStatic] go through the raid's `TfaInstance`.
 */
class DreadnothRoom(override val context: TfaEncounterContext) : TfaEncounter, DreadnothHost {

    override val room: TfaRoomId get() = TfaRoomId.DREADNOTH

    /** The raid's snapshot of `dreadnoth.json`. */
    val config: DreadnothConfig = context.config[DreadnothConfig]

    override val contributionPool: Int get() = Bridge.contributionPool()

    override val phases: List<String> = buildList {
        add(PHASE_CHASE)
        config.thresholds.forEach { add("$PHASE_SLAM_PREFIX$it") }
        add(PHASE_ENRAGE)
        add(PHASE_DEATH)
    }

    internal val timeline = Timeline()
    internal val swarm = JellySwarm(this)
    private val bloodLocs = Bridge.BloodLocs()
    private val blood = BloodField(config.blood, object : BloodField.Sink {
        override fun spawn(tile: StaticTile) = bloodLocs.spawn(translate(tile), context.random.nextInt(4))
        override fun remove(tile: StaticTile) = bloodLocs.remove(translate(tile))
    })

    private var boss: Dreadnoth? = null
    private val hud = Bridge.HudFallback(DreadnothIds.BOSS)
    private var hudMax = 0
    private val brain = DreadnothBrain(config, timeline, context.random, this, context.scale)
    private val fighterMap = LinkedHashMap<Player, PlayerFighter>()

    private var built = false
    private var started = false
    private var completed = false
    private var tornDown = false

    /** The boss NPC (null before [build] and after teardown). */
    internal val bossNpc: Dreadnoth? get() = boss

    // ================================================================================== TfaEncounter lifecycle

    override fun build() {
        if (built) return
        built = true
        val facing = when (config.arena.spawnFacing) {
            "north" -> Direction.NORTH
            "east" -> Direction.EAST
            "south" -> Direction.SOUTH
            else -> Direction.WEST
        }
        boss = Dreadnoth(this, context, translate(config.arena.spawn), facing).also { it.spawn() }
    }

    override fun onArenaEntry(player: Player) {
        Bridge.prefetch(player)
        if (started && !brain.isDead) Bridge.roomMusic(player)
    }

    override fun start() {
        if (started || tornDown) return
        if (!built) build()
        started = true
        val b = boss ?: return
        // The party scale is fixed now; re-scale an untouched boss in case it was built before the scale was known.
        if (b.hitpoints >= b.scaledMaxHp) b.applyScaling()
        Bridge.hudShow(b)
        for (p in context.playersInArena()) Bridge.roomMusic(p)
        brain.start()
    }

    override fun tick() {
        if (!started || tornDown) return
        timeline.advance()
        if (completed) return // the raid moves everyone out; nothing left to run or show
        refreshFighters()
        brain.tick()
        swarm.tick()
        blood.tick(timeline.now, fighterMap.values.toList(), context.random) { f, dmg -> bloodHit(f as PlayerFighter, dmg) }
        syncHud()
    }

    /** The HP bar of the players in the arena (fallback until the HUD service exists); 0 once he is dead. */
    private fun syncHud() {
        val b = boss
        if (b != null && b.scaledMaxHp > 0) hudMax = b.scaledMaxHp
        if (hudMax <= 0) return
        val current = if (b == null || brain.isDead || b.isFinished) 0 else b.hitpoints
        hud.sync(fighterMap.values.filter { it.alive }.map { it.player }, current, hudMax)
    }

    override fun onPlayerDeath(player: Player) {
        val f = fighterMap[player] ?: return
        brain.onFighterLost(f)
    }

    override fun isComplete(): Boolean =
        started && !tornDown && brain.isDead && brain.deathWaveSpawned && swarm.alive == 0

    override fun onComplete() {
        if (completed) return
        completed = true
        timeline.cancel(Tag.BOSS)
        boss?.let {
            Bridge.hudHide(it)
            if (!it.isFinished) it.finish()
        }
        swarm.despawnAll()
        blood.clear()
        hud.closeAll()
        log.info("Dreadnoth room complete at tick {} (scale {})", timeline.now, context.scale)
    }

    override fun onWipe() {
        boss?.let { Bridge.hudHide(it) }
        teardown()
    }

    override fun teardown() {
        if (tornDown) return
        tornDown = true
        timeline.cancelAll()
        swarm.despawnAll()
        blood.clear()
        bloodLocs.clear()
        hud.closeAll()
        boss?.let { if (!it.isFinished) it.finish() }
        boss = null
        fighterMap.clear()
    }

    // ========================================================================================== debug hooks

    override fun debugSkipToPhase(phase: String): Boolean {
        val b = boss ?: return false
        if (brain.isDead) return false
        if (!started) start()
        val key = phase.lowercase()
        when {
            key == PHASE_CHASE -> {
                brain.debugReset(100)
                b.setHpPercent(100)
            }
            key.startsWith(PHASE_SLAM_PREFIX) -> {
                val pct = key.removePrefix(PHASE_SLAM_PREFIX).toIntOrNull() ?: return false
                if (pct !in config.thresholds) return false
                brain.debugReset(pct)
                setBossHpAt(b, pct)
            }
            key == PHASE_ENRAGE -> {
                brain.debugReset(config.enragePct)
                setBossHpAt(b, config.enragePct)
            }
            key == PHASE_DEATH -> {
                b.setHitpoints(0) // sendDeath → onBossDeath
            }
            else -> return false
        }
        brain.checkThresholds()
        return true
    }

    /** HP at exactly [pct]% of the scaled max (rounded down, at least 1): the threshold at [pct] counts as reached. */
    private fun setBossHpAt(b: Dreadnoth, pct: Int) {
        val hp = (b.maxHitpoints.toLong() * pct / 100).toInt().coerceAtLeast(1)
        b.setHitpoints(hp)
        TfaServices.bossHud.onHpChanged(b)
    }

    override fun debugSetHpPercent(percent: Int): Boolean {
        val b = boss ?: return false
        if (brain.isDead) return false
        if (!started) start()
        b.setHpPercent(percent)
        brain.checkThresholds()
        return true
    }

    override fun debugState(): List<String> = buildList {
        add("Dreadnoth room: built $built, started $started, completed $completed, torn down $tornDown, tick ${timeline.now}")
        addAll(brain.describe())
        add("  ${swarm.describe()}, blood tiles ${blood.size}, pending actions ${timeline.pending()} (boss ${timeline.pending(Tag.BOSS)})")
        add("  fighters: ${fighterMap.values.joinToString { "${it.player.username}@${it.tile}${if (it.alive) "" else " (out)"}" }}")
    }

    // ==================================================================================== room-level hooks

    /** Every processed hit on the boss: thresholds are checked at once (spec §2.5 step 2). */
    internal fun onBossHit() {
        if (started && !tornDown) brain.checkThresholds()
    }

    internal fun onBossDeath() {
        if (tornDown) return
        brain.onDeath()
    }

    internal fun bossSwOrNull(): StaticTile? = boss?.takeIf { !it.isFinished }?.let { toStatic(it.location) }

    internal fun translate(tile: StaticTile): Location = context.instance.translate(tile)

    internal fun toStatic(location: Location): StaticTile =
        context.instance.toStatic(location) ?: StaticTile(-1, -1, location.plane)

    internal fun telegraph(spot: Int, tile: StaticTile) = Bridge.telegraph(spot, translate(tile))

    internal fun addBlood(tile: StaticTile) {
        if (!tornDown && !completed && DreadnothArena.isWalkable(tile)) blood.add(tile, timeline.now)
    }

    /** True while [player] is a living member inside this arena. */
    internal fun isFighting(player: Player): Boolean =
        !player.isDead && !player.isFinished && DreadnothArena.inBox(toStatic(player.location)) && player in context.members

    internal fun fightersAlive(): List<PlayerFighter> = fighterMap.values.filter { it.alive }

    private fun refreshFighters() {
        val inArena = context.playersInArena()
        for (p in inArena) fighterMap.getOrPut(p) { PlayerFighter(p, this) }
        val gone = fighterMap.keys.filter { it !in inArena || it.isFinished }
        for (p in gone) {
            val f = fighterMap.remove(p) ?: continue
            brain.onFighterLost(f)
        }
    }

    /**
     * NPC accuracy roll against [target] with the raid's random (Zenyte's `CombatUtilities.getHitAccuracy` formula),
     * then a uniform 0..[max]. Godmode players take nothing.
     */
    internal fun rollNpcHit(npc: NPC, target: Player, max: Int, style: AttackType): Int {
        if (Bridge.isGodmode(target) || max <= 0) return 0
        val accuracy = CombatUtilities.getHitAccuracy(npc, target, style)
        if (accuracy < context.random.nextDouble()) return 0
        val rolled = Bridge.wardReduced(target, npc, context.random.nextInt(max + 1))
        return CombatUtilities.clampMaxHit(npc, target, rolled)
    }

    private fun bloodHit(f: PlayerFighter, dmg: Int) {
        val p = f.player
        if (Bridge.isGodmode(p) || dmg <= 0) return
        p.applyHit(Hit(dmg, HitType.TYPELESS))
    }

    // ========================================================================================= DreadnothHost

    override val bossSw: StaticTile get() = boss?.let { toStatic(it.location) } ?: config.arena.spawn
    override val bossHp: Int get() = boss?.hitpoints ?: 0
    override val bossMaxHp: Int get() = boss?.maxHitpoints ?: 0

    override fun bossAnimate(seq: Int) {
        boss?.setAnimation(Animation(seq))
    }

    override fun bossFace(target: Fighter?) {
        val b = boss ?: return
        val p = (target as? PlayerFighter)?.player
        if (p == null) {
            if (b.faceEntity != -1) b.setFaceEntity(null)
        } else if (!b.isFacing(p)) {
            b.setFaceEntity(p)
        }
    }

    override fun bossStep(dx: Int, dy: Int): Boolean {
        val b = boss ?: return false
        // Frozen (40% resistance: Barrage 19 t) or bound (Scorching bow special, wiki Strategies): rooted in place.
        if (b.isMovementRestricted) return false
        val x = b.x
        val y = b.y
        b.resetWalkSteps()
        if (b.addWalkStep(x + dx, y + dy, x, y, true)) return true
        if (dx != 0 && dy != 0) {
            if (b.addWalkStep(x + dx, y, x, y, true)) return true
            if (b.addWalkStep(x, y + dy, x, y, true)) return true
        }
        return false
    }

    override fun bossHalt() {
        boss?.resetWalkSteps()
    }

    override fun bossLeap(from: StaticTile, to: StaticTile, startCycle: Int, endCycle: Int) {
        val b = boss ?: return
        b.resetWalkSteps()
        Bridge.exactMove(b, translate(from), translate(to), startCycle, endCycle, DreadnothArena.faceDirection(from, to))
    }

    override fun bossTargetable(targetable: Boolean) {
        boss?.setTargetableState(targetable)
    }

    override fun bossHeal(amount: Int) {
        val b = boss ?: return
        if (amount > 0 && !b.isDead) b.applyHit(Hit(amount, HitType.HEALED))
    }

    override fun bossDespawn() {
        boss?.let { if (!it.isFinished) it.finish() }
    }

    override fun fighters(): List<Fighter> = fighterMap.values.toList()

    override fun attack(target: Fighter, style: AttackStyle, max: Int) {
        val b = boss ?: return
        val p = (target as? PlayerFighter)?.player ?: return
        if (!isFighting(p)) return
        val engineStyle = when (style) {
            AttackStyle.MELEE -> DreadnothCombatDefs.meleeStyle(config.melee.style)
            AttackStyle.RANGED -> AttackType.RANGED
        }
        val damage = rollNpcHit(b, p, max, engineStyle)
        val type = if (style == AttackStyle.MELEE) HitType.MELEE else HitType.RANGED
        // Lands this tick; the protection prayer is checked as the hit lands (Player.appendHitEntry).
        CombatUtilities.delayHit(b, -1, p, Hit(b, damage, type))
    }

    override fun damage(target: Fighter, amount: Int): Int {
        val b = boss ?: return 0
        val p = (target as? PlayerFighter)?.player ?: return 0
        if (!isFighting(p) || Bridge.isGodmode(p) || amount <= 0) return 0
        val dealt = Bridge.wardReduced(p, b, amount)
        CombatUtilities.delayHit(b, -1, p, Hit(b, dealt, HitType.TYPELESS))
        return dealt
    }

    override fun graphic(spot: Int, tile: StaticTile) = telegraph(spot, tile)

    override fun projectile(spot: Int, spec: DreadnothConfig.ProjectileSpec, target: Fighter) {
        val b = boss ?: return
        val p = (target as? PlayerFighter)?.player ?: return
        World.sendProjectile(b, p, projectileOf(spot, spec))
    }

    override fun projectile(spot: Int, spec: DreadnothConfig.ProjectileSpec, tile: StaticTile) {
        val b = boss ?: return
        World.sendProjectile(b, translate(tile), projectileOf(spot, spec))
    }

    override fun blood(tile: StaticTile) = addBlood(tile)

    override fun spawnJellies(count: Int) = swarm.spawnBatch(count)

    override fun spawnDeathWave(count: Int) = swarm.spawnDeathWave(count, bossSw)

    // ================================================================================================ misc

    /**
     * A Zenyte projectile from a [DreadnothConfig.ProjectileSpec]: arrival = delay + duration + perTile × distance
     * cycles, heights in the old units (×4 on rev 241), slope converted from client units to the constructor's degrees.
     */
    private fun projectileOf(spot: Int, spec: DreadnothConfig.ProjectileSpec): Projectile =
        Projectile(spot, spec.startHeight, spec.endHeight, spec.delay, (spec.slope * 90 + 32) / 64, spec.duration, 0, spec.perTile)

    /** A player in the arena, wrapped once so the brain can keep a target. */
    internal class PlayerFighter(val player: Player, private val room: DreadnothRoom) : Fighter {
        override val tile: StaticTile get() = room.toStatic(player.location)
        override val alive: Boolean get() = room.isFighting(player)
        override fun praying(protect: Protect): Boolean = player.prayerManager.isActive(
            when (protect) {
                Protect.MELEE -> Prayer.PROTECT_FROM_MELEE
                Protect.MISSILES -> Prayer.PROTECT_FROM_MISSILES
                Protect.MAGIC -> Prayer.PROTECT_FROM_MAGIC
            },
        )

        override fun toString(): String = "PlayerFighter(${player.username})"
    }

    companion object {
        const val PHASE_CHASE = "chase"
        const val PHASE_SLAM_PREFIX = "slam"
        const val PHASE_ENRAGE = "enrage"
        const val PHASE_DEATH = "death"

        private val log = LoggerFactory.getLogger(DreadnothRoom::class.java)
    }
}
