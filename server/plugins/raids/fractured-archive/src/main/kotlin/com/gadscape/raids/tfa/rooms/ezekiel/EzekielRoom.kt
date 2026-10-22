package com.gadscape.raids.tfa.rooms.ezekiel

import com.gadscape.raids.tfa.encounter.TfaEncounter
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.content.skills.prayer.Prayer
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.WalkStep
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities
import org.slf4j.LoggerFactory
import java.util.EnumMap

/**
 * Room 2 of The Fractured Archive (west, section 1): Ezekiel on the 9x9 floor of static region 16214 (spec §4.2).
 *
 * - [build] spawns Ezekiel 16652 at SW (4062,5534) on the Guthix emblem, idle.
 * - The raid calls [onArenaEntry] for each transporter use and [start] on the first one; the first user becomes the
 *   tank ([TankOrder]). [EzekielFight] runs the state machine and its clocks; this class carries out what it decides
 *   against the world (npcs, hits, map graphics, collision).
 * - Every delayed action is an entry in the fight's [timeline] or in [effects] (world-side follow-ups the fight does
 *   not track: sword hits on players, finale sword removal), so the finale, the death, a wipe or [teardown] cancels it.
 */
class EzekielRoom(override val context: TfaEncounterContext) : TfaEncounter, EzekielHost {

    private val log = LoggerFactory.getLogger(EzekielRoom::class.java)

    override val room: TfaRoomId get() = TfaRoomId.EZEKIEL

    /** The raid's config snapshot of `ezekiel.json`. */
    val cfg: EzekielConfig = context.config[EzekielConfig]

    override val contributionPool: Int get() = Bridge.contributionPool(context)

    override val phases: List<String> get() = EzekielFight.PHASES

    /** The fight's timeline (created with the room so debug and dumps can read it before the start). */
    val timeline = EzekielTimeline()

    /** World-side follow-ups (player hits of a sword, finale sword removal). */
    private val effects = EzekielTimeline()

    private var fightOrNull: EzekielFight? = null
    val fight: EzekielFight get() = fightOrNull ?: error("the Ezekiel fight has not started")

    var started = false
        private set

    var boss: EzekielNpc? = null
        private set

    private var deathSword: DeathSwordNpc? = null

    val tanks = TankOrder<Player>()
    private var markedTank: Player? = null

    private val swords = LinkedHashMap<Long, AnimatedSword>()
    private val fences = ArrayList<SwordFence>()
    private var fenceHoldFrom = Int.MAX_VALUE
    private val finaleSwords = EnumMap<FinaleSword, FinaleSwordNpc>(FinaleSword::class.java)
    /** Finale swords playing their despawn, removed a tick later (teardown removes them at once). */
    private val leaving = ArrayList<FinaleSwordNpc>()
    private val fenceClip = Bridge.FenceClip()
    private val hud = Bridge.HudFallback(EzekielIds.BOSS)

    /** Swing direction of the cleave in progress (from his centre toward the tank when it started). */
    private var swing: Pair<Int, Int>? = null
    private var lightningTargets: List<Player> = emptyList()

    private var completeHandled = false
    private var tornDown = false

    private val now: Int get() = timeline.now

    // ================================================================================================ lifecycle

    override fun build() {
        if (boss != null || tornDown) return
        val b = EzekielNpc(this, context, live(cfg.spawn.x, cfg.spawn.y), cfg.spawnFacing.direction)
        boss = b
        b.spawn()
    }

    override fun onArenaEntry(player: Player) {
        Bridge.prefetch(context, player)
        tanks.entered(player)
        if (!started || tornDown) return
        val f = fightOrNull ?: return
        if (!f.isActive) return
        Bridge.playMusic(context, player)
        boss?.let { Bridge.hudShow(it) }
        if (f.state == EzekielState.FINALE_TRANSITION || f.state == EzekielState.FINALE) movePlayersIntoPen(listOf(player))
        refreshTank()
    }

    override fun start() {
        if (started || tornDown) return
        val b = boss ?: run { build(); boss } ?: return
        started = true
        b.applyScaling() // the party scale is fixed now (§2.6)
        val f = EzekielFight(cfg, b.scaledMaxHp, this, context.random, timeline)
        fightOrNull = f
        for (p in alivePlayers()) tanks.entered(p)
        refreshTank()
        for (p in context.playersInArena()) Bridge.playMusic(context, p)
        Bridge.hudShow(b)
        log.info("Ezekiel started at scale {} ({} HP), tank {}", context.scale, b.scaledMaxHp, tanks.tank?.username)
        f.start()
    }

    override fun tick() {
        if (tornDown) return
        effects.advance()
        val f = fightOrNull ?: return
        if (f.state == EzekielState.CYCLE || f.state == EzekielState.STUNNED) refreshTank()
        f.tick()
        val b = boss
        if (b != null && !b.isFinished && f.state != EzekielState.DEAD) hud.sync(viewers(), b.hitpoints.coerceAtLeast(0), b.maxHitpoints)
    }

    override fun onPlayerDeath(player: Player) {
        if (tanks.tank == player) {
            val next = tanks.lost(player) { eligible(it) }
            if (next != null) onNewTank(next) else markTank(null)
        }
        lightningTargets = lightningTargets.filter { it != player }
    }

    override fun isComplete(): Boolean = fightOrNull?.isComplete == true

    override fun onComplete() {
        if (completeHandled) return
        completeHandled = true
        clearSwords()
        clearFinale()
        hud.closeAll()
        Bridge.hudHide(boss)
        markTank(null)
        log.info("Ezekiel defeated after {} ticks", now)
    }

    override fun onWipe() {
        fightOrNull?.stop()
        timeline.cancelAll()
        effects.cancelAll()
        clearSwords()
        clearFinale()
        hud.closeAll()
        Bridge.hudHide(boss)
        markTank(null)
    }

    override fun teardown() {
        if (tornDown) return
        tornDown = true
        fightOrNull?.stop()
        timeline.cancelAll()
        effects.cancelAll()
        clearSwords()
        clearFinale()
        hud.closeAll()
        Bridge.hudHide(boss)
        markTank(null)
        boss?.let { if (!it.isFinished) it.finish() }
        deathSword?.let { if (!it.isFinished) it.finish() }
        deathSword = null
    }

    // ================================================================================================ debug

    override fun debugSkipToPhase(phase: String): Boolean {
        if (tornDown) return false
        if (!started) start()
        val f = fightOrNull ?: return false
        return f.debugSkipTo(phase)
    }

    override fun debugSetHpPercent(percent: Int): Boolean {
        val b = boss ?: return false
        if (!started || b.isFinished || b.isDead) return false
        val before = b.hitpoints
        b.setHpPercent(percent)
        if (!b.isDead) fightOrNull?.onBossHpChanged(before, b.hitpoints)
        return true
    }

    override fun debugState(): List<String> {
        val lines = ArrayList<String>()
        val f = fightOrNull
        if (f == null) lines += "Ezekiel waiting (no transporter use yet)" else lines += f.describe()
        boss?.let { lines += "boss npc ${it.id} hp ${it.hitpoints}/${it.maxHitpoints} at ${it.location}${if (it.isFinished) " (gone)" else ""}" }
        lines += "tank ${tanks.tank?.username ?: "none"}; entry order ${tanks.entries.joinToString { it.username }}"
        lines += "swords ${swords.size}, fences ${fences.size} (${fenceClip.size} blocked tiles), finale swords ${finaleSwords.size}"
        return lines
    }

    /** `::tfa ezekiel cast <h_full|...>`: a sword volley of [key] now. */
    fun debugCast(key: String): Boolean {
        val cast = SwordCast.parse(key) ?: return false
        val f = fightOrNull ?: return false
        f.spawnVolley(cast)
        return true
    }

    // ================================================================================================ queries

    /** Living players inside the arena. */
    fun alivePlayers(): List<Player> = context.playersInArena().filter { !it.isDead && !it.isFinished }

    private fun viewers(): List<Player> = context.playersInArena().filter { !it.isFinished }

    private fun eligible(p: Player): Boolean = !p.isDead && !p.isFinished && p in context.playersInArena()

    internal fun bossAttackable(): Boolean {
        val f = fightOrNull ?: return false
        return started && f.state != EzekielState.DYING && f.state != EzekielState.DEAD
    }

    private fun live(x: Int, y: Int): Location = context.instance.translate(x, y, 0)
    private fun live(t: Tile): Location = live(t.x, t.y)

    /** The static floor tile of [p], or null off the room plane / instance. */
    private fun staticOf(loc: Location): Tile? {
        val s = context.instance.toStatic(loc) ?: return null
        return if (s.plane == 0) Tile(s.x, s.y) else null
    }

    private fun bossSw(): Tile? {
        val b = boss ?: return null
        if (b.isFinished) return null
        return staticOf(b.location)
    }

    private fun protectedFrom(p: Player, prayer: Prayer): Boolean = p.prayerManager.isActive(prayer)

    /** An accuracy-rolled hit 0..[max] ([roll] false: uniform 0..max). */
    private fun rolled(target: Player, max: Int, style: AttackType, roll: Boolean): Int {
        val b = boss ?: return 0
        val random = context.random
        if (roll) {
            val accuracy = try {
                CombatUtilities.getHitAccuracy(b, target, style)
            } catch (e: Exception) {
                1.0
            }
            if (random.nextDouble() >= accuracy) return 0
        }
        return if (max <= 0) 0 else random.nextInt(max + 1)
    }

    // ================================================================================================ tank

    private fun refreshTank() {
        val changed = tanks.refresh { eligible(it) }
        if (changed != null) onNewTank(changed)
        else if (tanks.tank == null && markedTank != null) markTank(null)
    }

    private fun onNewTank(p: Player) {
        p.sendMessage(cfg.tankMessage)
        boss?.let { if (fightOrNull?.state == EzekielState.CYCLE || fightOrNull?.state == EzekielState.STUNNED) it.setFaceEntity(p) }
        markTank(p)
    }

    private fun markTank(p: Player?) {
        if (markedTank == p) return
        val old = markedTank
        markedTank = p
        Bridge.markTank(context, old, p)
    }

    // ================================================================================================ npc callbacks

    internal fun onBossHpChanged(b: EzekielNpc, before: Int, after: Int) {
        fightOrNull?.onBossHpChanged(before, after)
    }

    internal fun onBossDeath() {
        fightOrNull?.onBossDeath()
    }

    internal fun onSwordEmpowered(sword: AnimatedSword) {
        if (sword.launched || sword.isFinished) return
        Bridge.empowerBar(sword, sword.empower, (sword.launchAt - now).coerceAtLeast(1))
    }

    // ================================================================================================ EzekielHost

    override fun tankInReach(): Boolean {
        val t = tanks.tank ?: return false
        val b = boss ?: return false
        if (t.isDead || t.isFinished || t.plane != b.plane) return false
        return CombatUtilities.isWithinMeleeDistance(b, t)
    }

    override fun bossHp(): Int = boss?.hitpoints ?: 0

    override fun setBossHp(hp: Int) {
        val b = boss ?: return
        b.setHitpoints(hp.coerceIn(0, b.maxHitpoints))
    }

    override fun follow() {
        val b = boss ?: return
        val t = tanks.tank ?: return
        if (t.isDead || t.isFinished) return
        if (!b.isFacing(t)) b.setFaceEntity(t)
        if (tankInReach()) return
        b.resetWalkSteps()
        b.calcFollow(t, 1, true, true, false)
    }

    override fun playCleave() {
        val b = boss ?: return
        val sw = bossSw() ?: return
        val t = tanks.tank
        t?.let { b.setFaceEntity(it) }
        val tankTile = t?.let { staticOf(it.location) }
        swing = if (tankTile != null) Cleave.swingDirection(sw, tankTile) else null
        b.setAnimation(EzekielIds.CLEAVE)
    }

    /**
     * Cleave (§4.2): every living player on his ring except the 3 tiles opposite the swing. The tank takes a melee roll
     * (max 36) that Protect from Melee blocks; everyone else takes the same roll through prayer, and bleeds
     * (Vardorvis rules) when it deals damage.
     */
    override fun resolveCleave() {
        val b = boss ?: return
        val sw = bossSw() ?: return
        val dir = swing ?: return
        swing = null
        val tiles = Cleave.hitTiles(sw, dir.first, dir.second)
        val style = cleaveAttackType(cfg.cleaveStyle)
        val tank = tanks.tank
        for (p in alivePlayers()) {
            val t = staticOf(p.location) ?: continue
            if (t !in tiles) continue
            val roll = rolled(p, cfg.meleeMax, style, cfg.cleaveAccuracyRoll)
            if (p == tank) {
                val dmg = if (protectedFrom(p, Prayer.PROTECT_FROM_MELEE)) 0 else roll
                Bridge.hit(p, b, dmg, HitType.MELEE)
                if (dmg > 0 && cfg.bleedOnTank && cfg.bleed == EzekielConfig.BleedMode.VARDORVIS) Bridge.bleed(p)
            } else {
                Bridge.hit(p, b, roll, HitType.MELEE)
                if (roll > 0 && cfg.bleed == EzekielConfig.BleedMode.VARDORVIS) Bridge.bleed(p)
            }
        }
    }

    override fun playLightning() {
        val b = boss ?: return
        tanks.tank?.let { b.setFaceEntity(it) }
        b.setAnimation(EzekielIds.LIGHTNING)
        val targets = alivePlayers()
        for (p in targets) p.setGraphics(EzekielIds.LIGHTNING_CAST)
        lightningTargets = targets
    }

    /** Lightning on all (§4.2): spot 4168 + magic max 30 on each target, Protect from Magic halves it. */
    override fun resolveLightning() {
        val b = boss ?: return
        val arena = alivePlayers().toHashSet()
        for (p in lightningTargets) {
            if (p !in arena) continue
            p.setGraphics(EzekielIds.LIGHTNING_IMPACT)
            val roll = rolled(p, cfg.magicMax, AttackType.MAGIC, cfg.lightningAccuracyRoll)
            val dmg = if (protectedFrom(p, Prayer.PROTECT_FROM_MAGIC)) kotlin.math.floor(roll * cfg.magicPfmMult + 1e-9).toInt() else roll
            Bridge.hit(p, b, dmg, HitType.MAGIC)
        }
        lightningTargets = emptyList()
    }

    override fun playSpecial(): Set<Tile> {
        val b = boss ?: return emptySet()
        val sw = bossSw() ?: return emptySet()
        b.resetWalkSteps()
        b.setAnimation(EzekielIds.SPECIAL)
        b.setGraphics(EzekielIds.STAR_CRACK)
        return Star.tiles(sw, cfg.starArmWidth, cfg.starArmLength)
    }

    override fun starTelegraph(tiles: Set<Tile>) {
        for (t in tiles) Bridge.mapGraphic(EzekielIds.STAR_SPAWN_ID, live(t))
    }

    override fun starMarker(tiles: Set<Tile>) {
        for (t in tiles) Bridge.mapGraphic(EzekielIds.STAR_MARKER_ID, live(t))
    }

    override fun starExplosion(tiles: Set<Tile>) {
        for (t in tiles) Bridge.mapGraphic(EzekielIds.STAR_EXPLOSION_ID, live(t))
    }

    override fun starBurst(tiles: Set<Tile>, index: Int) {
        for (p in alivePlayers()) {
            val t = staticOf(p.location) ?: continue
            if (t in tiles) Bridge.hazardHit(p, cfg.starBurstDamage)
        }
    }

    private fun key(volley: Int, index: Int): Long = (volley.toLong() shl 8) or index.toLong()

    override fun spawnSword(volley: Int, sword: SwordSpec) {
        val npc = AnimatedSword(this, context, volley, sword, live(sword.start))
        npc.launchAt = now + sword.chargeTicks
        npc.spawn()
        swords[key(volley, sword.index)] = npc
        Bridge.launchBar(npc, sword.chargeTicks)
        Bridge.empowerBar(npc, 0.0, sword.chargeTicks)
    }

    override fun launchSword(volley: Int, sword: SwordSpec): SwordOutcome {
        val npc = swords[key(volley, sword.index)] ?: return SwordOutcome.GONE
        if (npc.isFinished) return SwordOutcome.GONE
        npc.launched = true
        val empower = npc.empower
        Bridge.removeBars(npc)
        val f = fightOrNull
        val bossTile = if (f != null && (f.state == EzekielState.CYCLE || f.state == EzekielState.STUNNED)) bossSw() else null
        val movers = alivePlayers().mapNotNull { p -> staticOf(p.location)?.let { mover(p, it) } }
        val result = SwordFlight.resolve(sword, cfg.swordFlightTicks, bossTile, movers, cfg.swordDodge) { canStand(it) }
        Bridge.exactMove(npc, live(result.stop), 0, result.travelCycles, sword.direction.direction)
        for (d in result.dodges) dodge(d)
        val damage = SwordFlight.playerDamage(cfg.swordPlayerDmgBase, cfg.swordPlayerDmgEmpower, empower)
        val victims = result.hit
        val land = {
            val arena = alivePlayers().toHashSet()
            for (p in victims) if (p in arena) Bridge.hazardHit(p, damage)
        }
        if (cfg.swordPlayerHitDelay <= 0) land() else effects.schedule(cfg.swordPlayerHitDelay, TAG_SWORD_HITS) { land() }
        log.debug("sword {}/{} {}: e {} stop {} boss {} hit {} dodged {}", volley, sword.index, sword.axis, empower, result.stop,
            result.hitsBoss, victims.size, result.dodges.size)
        return SwordOutcome(result.hitsBoss, empower, result.impactTicks, result.despawnTicks)
    }

    private fun mover(p: Player, tile: Tile): SwordFlight.Mover<Player> {
        val moving = p.hasWalkSteps()
        var dx = 0
        var dy = 0
        if (moving) {
            val step = p.walkSteps.peek()
            dx = Integer.signum(WalkStep.getNextX(step) - p.x)
            dy = Integer.signum(WalkStep.getNextY(step) - p.y)
        }
        return SwordFlight.Mover(p, tile, moving, dx, dy, p.direction)
    }

    /** A player may be rolled onto [t]: on the floor, not under Ezekiel, no blocking flag. */
    private fun canStand(t: Tile): Boolean {
        if (t !in EzekielArena.FLOOR) return false
        val sw = bossSw()
        if (sw != null && EzekielArena.under(sw, EzekielArena.SIZE, t)) return false
        return Bridge.standable(live(t))
    }

    /** The roll (14705) or strafe (14706) out of a sword's lane: moved at once, shown as an exact move. */
    private fun dodge(d: SwordFlight.Dodge<Player>) {
        val p = d.who
        val dx = d.to.x - d.from.x
        val dy = d.to.y - d.from.y
        if (d.strafe) {
            p.setAnimation(EzekielIds.PLAYER_STRAFE)
            Bridge.dodgeMove(p, live(d.to), EzekielIds.STRAFE_CYCLES, p.direction)
        } else {
            p.setAnimation(EzekielIds.PLAYER_ROLL)
            Bridge.dodgeMove(p, live(d.to), EzekielIds.ROLL_CYCLES, EzekielArena.angleOf(dx, dy))
        }
    }

    override fun swordGone(volley: Int, sword: SwordSpec) {
        swords.remove(key(volley, sword.index))?.let { if (!it.isFinished) it.finish() }
    }

    override fun damageBoss(damage: Int) {
        val b = boss ?: return
        if (damage > 0 && !b.isDead && !b.isFinished) b.applyHit(Hit(damage, HitType.TYPELESS))
    }

    override fun argh() {
        boss?.setForceTalk("Argh!")
    }

    override fun playStunStart() {
        val b = boss ?: return
        b.resetWalkSteps()
        b.setAnimation(EzekielIds.STUN_IN)
    }

    override fun playStunLoop() {
        boss?.setAnimation(EzekielIds.STUN_LOOP)
    }

    override fun playStunEnd() {
        boss?.setAnimation(EzekielIds.STUN_OUT)
    }

    override fun playHeal() {
        val b = boss ?: return
        val sw = bossSw() ?: return
        b.resetWalkSteps()
        b.setAnimation(EzekielIds.HEAL)
        b.setGraphics(EzekielIds.HEAL_GLOW)
        Bridge.mapGraphic(EzekielIds.HEAL_WAVE_ID, live(sw.offset(1, 1)))
    }

    override fun healBoss(amount: Int) {
        val b = boss ?: return
        if (amount > 0 && !b.isDead && !b.isFinished) b.applyHit(Hit(amount, HitType.HEALED))
    }

    override fun clearSwords() {
        for (s in swords.values) if (!s.isFinished) s.finish()
        swords.clear()
        effects.cancel(TAG_SWORD_HITS)
    }

    // ---- finale ---------------------------------------------------------------------------------------------------

    private val pen: Rect get() = cfg.finale.penRect

    override fun beginFinale() {
        val b = boss ?: return
        val f = cfg.finale
        b.resetWalkSteps()
        b.setFaceEntity(null)
        b.setLocation(live(f.bossTile.x, f.bossTile.y))
        b.faceDirection(f.bossFacing.direction)
        b.setAnimation(EzekielIds.FINALE)
        b.setGraphics(EzekielIds.FINALE_GLOW)
        // Players outside the pen are moved in before the fences take their cells.
        movePlayersIntoPen(alivePlayers())
        val variants = EzekielIds.FENCE_VARIANTS
        f.fenceTiles.forEachIndexed { i, t ->
            val variant = i % variants
            val fence = SwordFence(context, variant, live(t.x, t.y), f.fenceFacing.direction)
            fence.spawn()
            fence.setAnimation(EzekielIds.fenceDrop(variant))
            fences += fence
            if (f.fenceCollision) {
                for (tile in Rect.footprint(t.x, t.y, EzekielArena.SIZE).tiles()) {
                    if (tile in EzekielArena.FLOOR && tile !in pen) fenceClip.block(live(tile))
                }
            }
        }
        fenceHoldFrom = now + EzekielIds.FENCE_DROP_TICKS
        log.info("Ezekiel finale: {} fences, {} tiles blocked", fences.size, fenceClip.size)
    }

    /** Moves each of [players] standing outside the pen onto a random free pen tile. */
    private fun movePlayersIntoPen(players: List<Player>) {
        val taken = HashSet<Tile>()
        for (p in alivePlayers()) staticOf(p.location)?.let { if (it in pen) taken += it }
        for (p in players) {
            if (p.isDead || p.isFinished) continue
            val t = staticOf(p.location)
            if (t != null && t in pen) continue
            val to = Finale.penTileFor(pen, taken, context.random) { Bridge.standable(live(it)) }
            taken += to
            p.resetWalkSteps()
            p.setLocation(live(to))
        }
    }

    private fun setOf(which: FinaleSword): EzekielIds.FinaleSet = when (which) {
        FinaleSword.LEFT -> EzekielIds.FINALE_SET_LEFT
        FinaleSword.RIGHT -> EzekielIds.FINALE_SET_RIGHT
        FinaleSword.FRONT -> EzekielIds.FINALE_SET_FRONT
    }

    private val spotVisual: Boolean get() = cfg.finale.swordVisual == EzekielConfig.SwordVisual.SPOTANIM

    override fun finaleSwordsDrop() {
        val f = cfg.finale
        for (which in FinaleSword.entries) {
            if (finaleSwords.containsKey(which)) continue
            val tile = f.swordTiles[which] ?: continue
            val set = setOf(which)
            val npc = FinaleSwordNpc(context, which, set, live(tile.x, tile.y), f.swordFacing.direction)
            npc.spawn()
            if (spotVisual) npc.setGraphics(Graphics(set.dropSpot)) else npc.setAnimation(Animation(set.drop))
            npc.busyUntil = now + EzekielIds.FINALE_SWORD_DROP_TICKS
            finaleSwords[which] = npc
        }
    }

    override fun finaleUpkeep(volleyActive: Boolean, castPlaying: Boolean) {
        val b = boss
        if (b != null && !b.isFinished) {
            b.setAnimation(EzekielIds.FINALE_LOOP)
            if (!castPlaying) b.setGraphics(if (volleyActive) EzekielIds.FINALE_CAST_LOOP else EzekielIds.FINALE_GLOW_LOOP)
        }
        if (now >= fenceHoldFrom) for (fence in fences) fence.setAnimation(EzekielIds.fenceIdle(fence.variant))
        for (s in finaleSwords.values) {
            if (now < s.busyUntil) continue
            if (spotVisual) s.setGraphics(Graphics(s.set.idleSpot)) else s.setAnimation(Animation(s.set.idle))
        }
    }

    override fun finaleTelegraph(attack: FinaleAttack) {
        for (which in attack.struck) {
            val s = finaleSwords[which] ?: continue
            if (spotVisual) s.setGraphics(Graphics(s.set.telegraphSpot)) else s.setAnimation(Animation(s.set.telegraph))
            s.busyUntil = now + EzekielIds.FINALE_SWORD_TELEGRAPH_TICKS
        }
        boss?.setGraphics(EzekielIds.FINALE_CAST)
    }

    /**
     * Finale strike (§4.2): every pen tile except the attack's safe tiles is struck (impact 4196 on the row next to him,
     * 4197 the middle row, 4198 the far row); players on them take a magic hit of `finale.damage`, × `pfmMult` under
     * Protect from Magic.
     */
    override fun finaleStrike(attack: FinaleAttack) {
        val b = boss ?: return
        val f = cfg.finale
        val struck = Finale.struckTiles(pen, f.safe(attack))
        for (t in struck) {
            val spot = when (Finale.impactSize(pen, t)) {
                ImpactSize.LARGE -> EzekielIds.IMPACT_LARGE_ID
                ImpactSize.MEDIUM -> EzekielIds.IMPACT_MEDIUM_ID
                ImpactSize.SMALL -> EzekielIds.IMPACT_SMALL_ID
            }
            Bridge.mapGraphic(spot, live(t))
        }
        val struckSet = struck.toHashSet()
        for (p in alivePlayers()) {
            val t = staticOf(p.location) ?: continue
            if (t !in struckSet) continue
            val dmg = if (protectedFrom(p, Prayer.PROTECT_FROM_MAGIC)) kotlin.math.floor(f.damage * f.pfmMult + 1e-9).toInt() else f.damage
            Bridge.hit(p, b, dmg, HitType.MAGIC)
        }
    }

    // ---- death ----------------------------------------------------------------------------------------------------

    override fun playDeath() {
        val b = boss ?: return
        b.resetWalkSteps()
        b.setFaceEntity(null)
        b.setAnimation(EzekielIds.DEATH)
        b.setGraphics(EzekielIds.DEATH_GLOW)
        for (s in finaleSwords.values) {
            if (spotVisual) s.setGraphics(Graphics(s.set.despawnSpot)) else s.setAnimation(Animation(s.set.despawn))
            s.busyUntil = Int.MAX_VALUE
        }
        leaving += finaleSwords.values
        finaleSwords.clear()
        effects.schedule(EzekielIds.FINALE_SWORD_DESPAWN_TICKS, TAG_FINALE_SWORDS) {
            for (s in leaving) if (!s.isFinished) s.finish()
            leaving.clear()
        }
    }

    override fun finishDeath() {
        val b = boss ?: return
        val tile = Location(b.location)
        // In the finale he faces west (finale.bossFacing); otherwise keep the way he faces now.
        val atFinaleTile = staticOf(b.location) == Tile(cfg.finale.bossTile.x, cfg.finale.bossTile.y)
        val facing = if (atFinaleTile) cfg.finale.bossFacing.direction else Direction.getClosestDirection(b.direction) ?: Direction.SOUTH
        clearFinale()
        if (deathSword == null) {
            deathSword = DeathSwordNpc(context, tile, facing).also { it.spawn() }
        }
        hud.closeAll()
        Bridge.hudHide(b)
        if (!b.isFinished) b.finish()
    }

    /** Removes the fences (and their collision) and the finale swords. */
    private fun clearFinale() {
        for (fence in fences) if (!fence.isFinished) fence.finish()
        fences.clear()
        fenceHoldFrom = Int.MAX_VALUE
        fenceClip.clear()
        for (s in finaleSwords.values) if (!s.isFinished) s.finish()
        finaleSwords.clear()
        for (s in leaving) if (!s.isFinished) s.finish()
        leaving.clear()
    }

    override fun log(message: String) {
        log.debug("Ezekiel: {}", message)
    }

    companion object {
        const val TAG_SWORD_HITS = "swordHits"
        const val TAG_FINALE_SWORDS = "finaleSwords"
    }
}
