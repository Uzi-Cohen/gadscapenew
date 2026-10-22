package com.gadscape.raids.tfa.rooms.guardian

import com.gadscape.raids.tfa.encounter.TfaEncounter
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.content.skills.prayer.Prayer
import com.zenyte.game.world.Projectile
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.EnumMap

/**
 * Room 1 of The Fractured Archive: the Archive Guardian in the nexus atrium (spec §4.1, static region 16471).
 *
 * - [build] spawns the Inert Guardian 16657 on its pedestal (SW 4125,5597) and the four batteries.
 * - The Touch ([onTouch]) starts the room ([start], through [Bridge.requestStart] until WU-12's
 *   `TfaRaid.startEncounter` exists): [GuardianFight] runs the state machine and its clocks; this class carries out
 *   what it decides against the world (npcs, projectiles, hits, locs).
 * - Every delayed action (projectile landings, the slam, the laser, tendril swipes, mushroom bursts) is an entry in
 *   the one [timeline], so a wipe or [teardown] cancels everything.
 */
class ArchiveGuardianRoom(override val context: TfaEncounterContext) : TfaEncounter, GuardianHost {

    private val log = LoggerFactory.getLogger(ArchiveGuardianRoom::class.java)

    override val room: TfaRoomId get() = TfaRoomId.GUARDIAN

    /** The raid's config snapshot of `guardian.json`. */
    val cfg: GuardianConfig = context.config[GuardianConfig]

    override val contributionPool: Int get() = Bridge.contributionPool()

    override val phases: List<String> get() = GuardianFight.PHASES

    val timeline = GuardianTimeline()

    /** The fight; created on [start] with the party scale fixed then. */
    private var fightOrNull: GuardianFight? = null
    val fight: GuardianFight get() = fightOrNull ?: error("the Archive Guardian fight has not started")

    var started = false
        private set

    var inert: InertGuardianNpc? = null
        private set

    /** The boss npc (16659 / 16660 / 16658), from the end of the wake. */
    var boss: ArchiveGuardianNpc? = null
        private set

    val batteries = EnumMap<Alcove, GuardianBattery>(Alcove::class.java)

    private val tendrils = TendrilController(this)
    private val mushrooms = MushroomField(this)

    /** Discharges of batteries destroyed before the Touch (only with `battery.attackableBeforeStart`). */
    private var preStartDischarges = 0

    private var completeHandled = false
    private var tornDown = false

    /** The boss's current single target (§4 shared aggro rule). */
    private var aggro: Player? = null

    /** Whom the boss npc faces (re-sent only on change). */
    private var faced: Player? = null

    /** The tile the laser was aimed at when it began (used if the target is gone when it fires). */
    private var laserAim: Tile? = null

    private class DamageEntry(val tick: Int, val player: Player, val amount: Int)

    private val recentDamage = ArrayDeque<DamageEntry>()

    // ================================================================================================ lifecycle

    override fun build() {
        val spawn = context.instance.translate(cfg.bossSpawn)
        inert = InertGuardianNpc(this, context, spawn).also { it.spawn() }
        for (alcove in Alcove.entries) {
            val tile = context.instance.translate(cfg.battery.tiles.getValue(alcove))
            val active = cfg.battery.startStates[alcove] ?: (alcove != Alcove.SE)
            batteries[alcove] = GuardianBattery(this, context, alcove, tile, active).also { it.spawn() }
        }
    }

    override fun onArenaEntry(player: Player) {
        Bridge.prefetch(player)
        val b = boss
        if (b != null && started && !fight.isComplete) {
            Bridge.hudShow(b, hudNpcId(b), listOf(player))
            Bridge.playMusic(player)
        }
    }

    /** op1 Touch on the Inert Guardian. */
    fun onTouch(player: Player) {
        if (started) return
        player.faceEntity(inert ?: return)
        Bridge.requestStart(this, player)
    }

    override fun start() {
        if (started || tornDown) return
        started = true
        val n = context.scale
        val f = GuardianFight(cfg, context.scaling.hp(cfg.shieldHp, n), context.scaling.hp(cfg.mainHp, n), this, timeline)
        fightOrNull = f
        log.info("Archive Guardian started at scale {} (shield {}, main {})", n, f.shieldMax, f.mainMax)
        f.start()
        repeat(preStartDischarges) { f.onBatteryDestroyed(byLaser = false) }
        preStartDischarges = 0
    }

    override fun tick() {
        if (tornDown) return
        val f = fightOrNull ?: return
        f.tick()
        mushrooms.endOfTick()
        val oldest = timeline.now - cfg.aggroWindowTicks
        while (recentDamage.isNotEmpty() && recentDamage.peekFirst().tick <= oldest) recentDamage.pollFirst()
    }

    override fun onPlayerDeath(player: Player) {
        if (aggro == player) aggro = null
    }

    override fun isComplete(): Boolean = fightOrNull?.isComplete == true

    override fun onComplete() {
        if (completeHandled) return
        completeHandled = true
        tendrils.clear()
        mushrooms.clear()
        Bridge.hudHide(boss, viewers())
        log.info("Archive Guardian defeated after {} ticks", timeline.now)
    }

    override fun onWipe() {
        fightOrNull?.stop()
        timeline.cancelAll()
        tendrils.clear()
        mushrooms.clear()
        Bridge.hudHide(boss, viewers())
    }

    override fun teardown() {
        if (tornDown) return
        tornDown = true
        fightOrNull?.stop()
        timeline.cancelAll()
        tendrils.clear()
        mushrooms.clear()
        inert?.let { if (!it.isFinished) it.finish() }
        inert = null
        for (b in batteries.values) if (!b.isFinished) b.finish()
        batteries.clear()
        val b = boss
        if (b != null && !b.isFinished) {
            if (completeHandled && b.form == ArchiveGuardianNpc.Form.DEAD && cfg.corpseLingerTicks > 0) {
                b.lingerThenFinish(cfg.corpseLingerTicks)
            } else {
                b.finish()
            }
        }
    }

    // ================================================================================================ debug

    override fun debugSkipToPhase(phase: String): Boolean {
        if (tornDown) return false
        if (!started) start()
        return fight.debugSkipTo(phase)
    }

    override fun debugSetHpPercent(percent: Int): Boolean {
        val b = boss ?: return false
        if (!started) return false
        val before = b.hitpoints
        b.setHpPercent(percent)
        onBossHpChanged(b, before, b.hitpoints)
        return true
    }

    override fun debugState(): List<String> {
        val lines = ArrayList<String>()
        val f = fightOrNull
        if (f == null) lines += "Archive Guardian dormant (not touched)" else lines += f.describe()
        boss?.let { lines += "boss npc ${it.id} form ${it.form} hp ${it.hitpoints}/${it.maxHitpoints} at ${it.location}" }
        lines += "batteries: " + batteries.entries.joinToString { (a, b) -> "$a=${if (b.destroyed) "destroyed" else if (b.isActive) "active ${b.hitpoints}" else "inactive"}" }
        lines += "tendrils ${tendrils.alive}, mushrooms ${mushrooms.alive}, aggro ${aggro?.username ?: "none"}"
        return lines
    }

    /** `::tfa guardian battery <alcove>`: destroys a battery as if killed. */
    fun debugDestroyBattery(alcove: Alcove): Boolean {
        val b = batteries[alcove] ?: return false
        if (b.destroyed) return false
        onBatteryDestroyed(b, byLaser = false)
        return true
    }

    // ================================================================================================ queries

    /** Living players inside the arena. */
    fun alivePlayers(): List<Player> = context.playersInArena().filter { !it.isDead && !it.isFinished }

    /** Who sees the boss HP HUD: the players in the arena. */
    private fun viewers(): List<Player> = context.playersInArena().filter { !it.isFinished }

    fun bossCentre(b: ArchiveGuardianNpc): Tile = GuardianGeometry.centre(b.x, b.y)

    /** A tile a mushroom or tendril may use: floor, no blocking loc. */
    fun walkable(x: Int, y: Int, plane: Int): Boolean = World.isFloorFree(plane, x, y)

    fun batteriesAttackable(): Boolean = started || cfg.battery.attackableBeforeStart

    private fun hudNpcId(b: ArchiveGuardianNpc): Int =
        if (b.form == ArchiveGuardianNpc.Form.FLYING) TfaIds.Npc.GUARDIAN_FLYING else TfaIds.Npc.GUARDIAN_GROUNDED

    private fun protectedFrom(p: Player, prayer: Prayer): Boolean = p.prayerManager.isActive(prayer)

    private fun face(b: ArchiveGuardianNpc, target: Player?) {
        if (faced === target) return
        faced = target
        b.setFaceEntity(target)
    }

    // ================================================================================================ npc callbacks

    internal fun onBossHpChanged(b: ArchiveGuardianNpc, before: Int, after: Int) {
        Bridge.hudUpdate(b, viewers())
        fightOrNull?.onPoolHpChanged(before, after)
    }

    internal fun onBossHudChanged(b: ArchiveGuardianNpc) {
        Bridge.hudUpdate(b, viewers())
    }

    internal fun recordDamage(player: Player, amount: Int) {
        if (amount > 0) recentDamage.addLast(DamageEntry(timeline.now, player, amount))
    }

    internal fun onBatteryDestroyed(battery: GuardianBattery, byLaser: Boolean) {
        if (battery.destroyed) return
        battery.markDestroyed()
        val f = fightOrNull
        if (f == null) {
            // Before the Touch nothing ticks the timeline: show the destroyed battery at once.
            battery.showDestroyed()
            preStartDischarges++
            return
        }
        timeline.schedule(GuardianAnims.BATTERY_DESPAWN_TICKS, TAG_BATTERY) {
            if (!battery.isFinished) battery.showDestroyed()
        }
        f.onBatteryDestroyed(byLaser)
    }

    // ================================================================================================ GuardianHost

    override fun playWake() {
        inert?.wake()
        for (p in context.playersInArena()) Bridge.playMusic(p)
    }

    /** The boss npc, spawned in place of the Inert Guardian at the end of the wake. */
    private fun ensureBoss(): ArchiveGuardianNpc {
        boss?.let { return it }
        val old = inert
        val tile = old?.location?.let { Location(it) } ?: context.instance.translate(cfg.bossSpawn)
        if (old != null && !old.isFinished) old.finish()
        inert = null
        val b = ArchiveGuardianNpc(this, context, tile)
        boss = b
        b.spawn()
        return b
    }

    override fun showShieldPool(shield: Int, max: Int) {
        val b = ensureBoss()
        b.becomeForm(ArchiveGuardianNpc.Form.FLYING, TfaIds.Npc.GUARDIAN_FLYING)
        b.setPool(shield, cfg.shieldHp)
        // §4.1 [C+]: the shield also shows as headbar 86 standard_shield_60 (cyan) over the Guardian [INF].
        Bridge.setHeadbar(b, TfaIds.Headbar.SHIELD_60)
        Bridge.hudShow(b, TfaIds.Npc.GUARDIAN_FLYING, viewers())
    }

    override fun showMainPool(hp: Int, max: Int, enraged: Boolean) {
        val b = ensureBoss()
        b.becomeForm(if (enraged) ArchiveGuardianNpc.Form.ENRAGED else ArchiveGuardianNpc.Form.GROUNDED, TfaIds.Npc.GUARDIAN_GROUNDED)
        b.setPool(hp, cfg.mainHp)
        Bridge.setHeadbar(b, -1)
        Bridge.hudShow(b, TfaIds.Npc.GUARDIAN_GROUNDED, viewers())
    }

    override fun poolHp(): Int = boss?.hitpoints ?: 0

    override fun setPoolHp(hp: Int) {
        val b = boss ?: return
        b.setHitpoints(hp)
        Bridge.hudUpdate(b, viewers())
    }

    override fun playLanding() {
        val b = boss ?: return
        b.resetWalkSteps()
        b.setAnimation(GuardianAnims.LAND)
        b.setGraphics(GuardianAnims.LAND_GFX)
    }

    override fun playGroundIdle() {
        boss?.setAnimation(GuardianAnims.IDLE_GROUNDED)
    }

    override fun playLifting() {
        val b = boss ?: return
        b.setAnimation(GuardianAnims.LIFT)
        b.setGraphics(GuardianAnims.LIFT_GFX)
    }

    override fun playEnrage() {
        val b = boss ?: return
        b.resetWalkSteps()
        b.setAnimation(GuardianAnims.ENRAGE)
        b.setGraphics(GuardianAnims.ENRAGE_GFX)
    }

    override fun playDeath() {
        val b = boss ?: return
        b.resetWalkSteps()
        face(b, null)
        b.setAnimation(GuardianAnims.DEATH)
    }

    override fun showDead() {
        val b = boss ?: return
        b.becomeForm(ArchiveGuardianNpc.Form.DEAD, TfaIds.Npc.GUARDIAN_DEAD)
        Bridge.setHeadbar(b, -1)
    }

    override fun followTarget() {
        val b = boss ?: return
        if (timeline.hasPending(TAG_LASER)) return
        val target = currentTarget() ?: return
        face(b, target)
        val box = instanceBox()
        val step = GuardianGeometry.followStep(b.x, b.y, target.x, target.y, cfg.movement.followRange, box) ?: return
        b.resetWalkSteps()
        b.addWalkSteps(step.x, step.y, 1, true)
    }

    override fun attack(kind: GuardianAttack) {
        when (kind) {
            GuardianAttack.AUTO -> autoAttack()
            GuardianAttack.MUSHROOMS -> mushrooms.cast()
            GuardianAttack.LASER -> laser()
        }
    }

    override fun tendrilWave() = tendrils.wave()

    override fun dischargeOnShield(amount: Int) {
        boss?.queueDischarge(amount, 0)
    }

    override fun dischargeOnMain(amount: Int, floorHp: Int) {
        boss?.queueDischarge(amount, floorHp)
    }

    override fun clearHazards() {
        tendrils.retreat()
        mushrooms.retreat()
    }

    override fun log(message: String) {
        log.debug("Archive Guardian: {}", message)
    }

    // ================================================================================================ attacks

    /** The aggro target for this attack (§4 shared rule: most damage in the last 10 t, then nearest, then slot). */
    private fun pickTarget(): Player? {
        val b = boss ?: return null
        val c = bossCentre(b)
        val players = alivePlayers()
        if (players.isEmpty()) return null
        val damage = HashMap<Player, Int>()
        for (e in recentDamage) damage.merge(e.player, e.amount, Int::plus)
        val members = context.members
        val pick = GuardianRules.pickAggro(players.map { p ->
            GuardianRules.AggroCandidate(p, damage[p] ?: 0, GuardianGeometry.chebyshev(c.x, c.y, p.x, p.y),
                members.indexOf(p).let { if (it < 0) Int.MAX_VALUE else it })
        })
        aggro = pick
        return pick
    }

    private fun currentTarget(): Player? {
        val a = aggro
        if (a != null && !a.isDead && !a.isFinished && a in alivePlayers()) return a
        return pickTarget()
    }

    private fun autoAttack() {
        val b = boss ?: return
        val target = pickTarget() ?: return
        face(b, target)
        if (GuardianGeometry.under(b.x, b.y, GuardianGeometry.BOSS_SIZE, target.x, target.y)) slam() else ranged(target)
    }

    /**
     * Ranged auto (§4.1): seq 14751 + spot 4199 on the boss, projectile 4201 (released 30 cycles in, 82-cycle
     * flight), impact 4200 on arrival. Max 25 on a ranged accuracy roll, blocked by Protect from Missiles; the hit
     * lands on the tick the projectile arrives (prayer checked then).
     */
    private fun ranged(target: Player) {
        val b = boss ?: return
        b.setAnimation(GuardianAnims.RANGED)
        b.setGraphics(GuardianAnims.RANGED_CAST)
        val targets = ArrayList<Player>()
        targets += target
        if (cfg.rangedCone) {
            val c = bossCentre(b)
            for (p in alivePlayers()) if (p != target && GuardianGeometry.inCone(c.x, c.y, target.x, target.y, p.x, p.y, cfg.coneWidth)) targets += p
        }
        val damageRoll = rollRanged(target)
        for (t in targets) launchRanged(t, if (t == target || !cfg.rangedCone) null else damageRoll)
        if (cfg.tailSwipe) {
            val strip = GuardianGeometry.tailStrip(b.x, b.y, target.x, target.y).toHashSet()
            timeline.schedule(cfg.slamHitDelay, TAG_ATTACK_HIT) {
                for (p in alivePlayers()) if (Tile(p.x, p.y) in strip) {
                    val dmg = if (protectedFrom(p, Prayer.PROTECT_FROM_MELEE)) 0 else rollMelee(p, cfg.tailMax)
                    Bridge.hit(p, b, dmg, HitType.MELEE)
                }
            }
        }
    }

    private fun launchRanged(t: Player, fixedDamage: Int?) {
        val b = boss ?: return
        val pc = cfg.rangedProjectile
        val projectile = Projectile(GuardianAnims.RANGED_PROJ_ID, pc.startHeight, pc.endHeight, pc.startDelay, pc.slope, pc.flight, 0, pc.perTile)
        World.sendProjectile(b, t, projectile)
        val endCycle = projectile.getProjectileDuration(b.middleLocation, t)
        t.setGraphics(Graphics(GuardianAnims.RANGED_IMPACT_ID, endCycle, pc.impactHeight))
        val landTicks = ((endCycle + CYCLES_PER_TICK - 1) / CYCLES_PER_TICK).coerceAtLeast(1)
        timeline.schedule(landTicks, TAG_PROJECTILE) {
            if (t.isDead || t.isFinished || t !in alivePlayers()) return@schedule
            val roll = fixedDamage ?: rollRanged(t)
            val dmg = if (protectedFrom(t, Prayer.PROTECT_FROM_MISSILES)) 0 else roll
            Bridge.hit(t, b, dmg, HitType.RANGED)
        }
    }

    private fun rollRanged(t: Player): Int {
        val b = boss ?: return 0
        return GuardianRules.accuracyRoll(context.random, CombatUtilities.getHitAccuracy(b, t, AttackType.RANGED), cfg.rangedMax)
    }

    private fun rollMelee(t: Player, max: Int): Int {
        val b = boss ?: return 0
        return GuardianRules.accuracyRoll(context.random, CombatUtilities.getHitAccuracy(b, t, AttackType.CRUSH), max)
    }

    /**
     * Step-under slam (§4.1, wiki "step under him on the tick before he attacks so he slams the ground instead,
     * stepping out will prevent damage"): seq 14752; 2 t later every player still under the 5x5 takes a crush hit,
     * max 30, blocked by Protect from Melee.
     */
    private fun slam() {
        val b = boss ?: return
        b.setAnimation(GuardianAnims.SLAM)
        timeline.schedule(cfg.slamHitDelay, TAG_ATTACK_HIT) {
            for (p in alivePlayers()) {
                if (p.plane != b.plane || !GuardianGeometry.under(b.x, b.y, GuardianGeometry.BOSS_SIZE, p.x, p.y)) continue
                val dmg = if (protectedFrom(p, Prayer.PROTECT_FROM_MELEE)) 0 else rollMelee(p, cfg.slamMax)
                Bridge.hit(p, b, dmg, HitType.MELEE)
            }
        }
    }

    /**
     * The enraged laser (§4.1): a random living target; "The Archive Guardian stares intently at <name>." to the
     * others and "... at you." to the target; it faces the target and plays seq 14753 + the beam spot 4205; 3 t later
     * one hit along an 18-tile ray from its centre through the target's tile: T = 45 × alive shared by the players on
     * the line (× 0.8 under Protect from Magic). Batteries on the line are destroyed and hurt main HP.
     */
    private fun laser() {
        val b = boss ?: return
        val players = alivePlayers()
        if (players.isEmpty()) return
        val target = players[context.random.nextInt(players.size)]
        for (p in context.playersInArena()) {
            p.sendMessage(if (p == target) "The Archive Guardian stares intently at you." else "The Archive Guardian stares intently at ${target.name}.")
        }
        laserAim = Tile(target.x, target.y)
        b.resetWalkSteps()
        face(b, target)
        b.setAnimation(GuardianAnims.LASER)
        b.setGraphics(GuardianAnims.LASER_GFX)
        timeline.schedule(cfg.laser.windup, TAG_LASER) { fireLaser(target) }
    }

    private fun fireLaser(target: Player) {
        val b = boss ?: return
        val aim = if (!target.isDead && !target.isFinished && target in alivePlayers()) Tile(target.x, target.y) else laserAim ?: return
        val c = bossCentre(b)
        val line = GuardianGeometry.ray(c.x, c.y, aim.x, aim.y, cfg.laser.length).toHashSet()
        val players = alivePlayers()
        val onLine = players.filter { it.plane == b.plane && Tile(it.x, it.y) in line }
        val total = GuardianRules.laserTotal(players.size, cfg.laser.perAlive)
        for (p in onLine) {
            val dmg = GuardianRules.laserShare(total, onLine.size, protectedFrom(p, Prayer.PROTECT_FROM_MAGIC), cfg.laser.pfmMult)
            Bridge.hit(p, b, dmg, HitType.MAGIC)
        }
        for (battery in batteries.values) {
            if (battery.destroyed) continue
            val hitIt = (0 until 2).any { dx -> (0 until 2).any { dy -> Tile(battery.x + dx, battery.y + dy) in line } }
            if (hitIt) onBatteryDestroyed(battery, byLaser = true)
        }
        laserAim = null
    }

    private fun instanceBox(): List<Int> {
        val box = cfg.movement.box
        val lo = context.instance.translate(box[0], box[1], 0)
        val hi = context.instance.translate(box[2], box[3], 0)
        return listOf(lo.x, lo.y, hi.x, hi.y)
    }

    companion object {
        const val CYCLES_PER_TICK = 30
        const val TAG_PROJECTILE = "projectile"
        const val TAG_ATTACK_HIT = "attackHit"
        const val TAG_LASER = "laser"
        const val TAG_BATTERY = "battery"
    }
}
