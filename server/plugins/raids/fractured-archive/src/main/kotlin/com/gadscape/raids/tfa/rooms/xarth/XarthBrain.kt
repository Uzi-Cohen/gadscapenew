package com.gadscape.raids.tfa.rooms.xarth

import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.rooms.xarth.Timeline.Tag
import com.gadscape.raids.tfa.rooms.xarth.XarthConfig.CoverRule
import com.gadscape.raids.tfa.rooms.xarth.XarthConfig.LosTest
import com.gadscape.raids.tfa.rooms.xarth.XarthConfig.PileBusy
import java.util.EnumSet
import java.util.Random
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Xarth's script (spec §4.7 "State machine"), engine-free: every decision and timing, with the world behind
 * [XarthHost]. Driven by [XarthRoom.tick] after [Timeline.advance] (spec §2.5: timeline, then decisions, then hazards).
 *
 * | State | Behaviour | Exit |
 * |---|---|---|
 * | P1_AUTO | magic every `attackSpeed` t at a random player with line of sight (none → retry next tick) | a threshold is queued → P1_BLAST |
 * | P1_BLAST | root, bombs, charge, blast at t+7, pillars collapse; no autos | t+9: next queued threshold, P1_AUTO, or (last threshold) P2_TRANSITION |
 * | P2_TRANSITION | 15017 + 4032, standing pillars collapse, flames launched t+3 (land t+5) | t+8 → P2_AUTO; first summon at t+10 |
 * | P2_AUTO | melee (orthogonally adjacent) or ranged every 4 t; corpse summons by HP band; flames grow | a heal blast is queued → P2_HEAL_BLAST at the next attack slot |
 * | P2_HEAL_BLAST | 14989 + 4380, burst at t+2 with the shield-line rule | t+4 → P2_AUTO |
 * | DYING | 14995 + 4381, corpses die, flames removed | t+5 → DEAD (room complete) |
 *
 * Thresholds are fractions of the scaled max HP; [checkThresholds] runs after every processed hit, so a hit that
 * crosses several queues them in order (spec §2.5).
 */
internal class XarthBrain(
    private val cfg: XarthConfig,
    private val timeline: Timeline,
    private val random: Random,
    private val host: XarthHost,
    /** The party scale n (2..8); read when needed, so the scale the raid fixes at the start is the one used. */
    private val scaleOf: () -> Int,
) {

    enum class State { IDLE, P1_AUTO, P1_BLAST, P2_TRANSITION, P2_AUTO, P2_HEAL_BLAST, DYING, DEAD }

    var state: State = State.IDLE
        private set

    /** Thresholds whose sequence has been queued or run (each runs once). */
    val reached: MutableSet<Int> = LinkedHashSet()
    private val queued = ArrayDeque<Int>()

    /** The threshold whose blast is running, or null. */
    var currentBlast: Int? = null
        private set

    var nextAttackTick: Int = Int.MAX_VALUE
        private set
    private var engaged = false

    /** Tick his current attack / cast / blast seq ends; turn and defend seqs do not count. */
    var busyUntil: Int = 0
        private set
    private var turnUntil: Int = 0

    /** His face angle (0 south, 512 west, 1024 north, 1536 east). */
    var facing: Int = XarthArena.angleOf(cfg.arena.spawnFacing)
        private set
    private var preTarget: Fighter? = null
    private var meleeTarget: Fighter? = null

    /** Pillars still standing (cover). */
    val standing: MutableSet<Pillar> = EnumSet.allOf(Pillar::class.java)
    private val bound = LinkedHashSet<Fighter>()

    /** HP healed by corpses since the last heal blast (the excess over the threshold is kept). */
    var healCounter: Int = 0
        private set
    var healBlastsQueued: Int = 0
        private set
    var healBlastsDone: Int = 0
        private set
    var blastsDone: Int = 0
        private set

    var nextSummonTick: Int = Int.MAX_VALUE
        private set
    private var extraReadyTick = 0
    var nextFlameTick: Int = Int.MAX_VALUE
        private set
    val busyPiles: MutableSet<Pile> = EnumSet.noneOf(Pile::class.java)
    var pendingCorpses: Int = 0
        private set
    var summons: Int = 0
        private set
    var extraCorpses: Int = 0
        private set

    /** Set when the death seq has played out: the room is complete. */
    var deathComplete: Boolean = false
        private set

    val flames = FlameField(cfg.flames, object : FlameField.Sink {
        override fun spawn(tile: StaticTile) = host.flameSpawn(tile)
        override fun remove(tile: StaticTile) = host.flameRemove(tile)
    })

    val isDead: Boolean get() = state == State.DYING || state == State.DEAD
    val inPhase2: Boolean get() = state == State.P2_TRANSITION || state == State.P2_AUTO || state == State.P2_HEAL_BLAST
    val started: Boolean get() = state != State.IDLE

    /** The threshold that starts phase 2 after its blast (spec: 50%). */
    val p2Pct: Int get() = cfg.thresholds.last()

    val boundFighters: Set<Fighter> get() = bound

    /** Thresholds waiting to run, in order. */
    val queuedThresholds: List<Int> get() = queued.toList()

    // ================================================================================================ lifecycle

    /** The fight begins: first auto after `firstAttackDelay` ticks (or 1 t after the first player hit). */
    fun start() {
        if (state != State.IDLE) return
        state = State.P1_AUTO
        nextAttackTick = timeline.now + cfg.firstAttackDelay
        checkThresholds()
    }

    /** A player's hit landed on him (NOTES §7.1: the fight starts on the first hit). */
    fun onPlayerHit() {
        if (state == State.P1_AUTO && !engaged) {
            engaged = true
            nextAttackTick = minOf(nextAttackTick, timeline.now + 1)
        }
    }

    /** After every processed hit (or heal): queue every threshold now reached (spec §2.5 step 2). */
    fun checkThresholds() {
        if (state == State.IDLE || isDead) return
        val max = host.bossMaxHp
        val hp = host.bossHp
        if (max <= 0 || hp <= 0) return
        for (pct in cfg.thresholds) {
            if (pct in reached) continue
            if (hp.toLong() * 100 <= pct.toLong() * max) {
                reached += pct
                queued.addLast(pct)
            }
        }
    }

    /** Once per room tick, after the timeline. */
    fun tick() {
        val now = timeline.now
        for (f in bound) if (f.alive) host.holdBind(f)
        when (state) {
            State.IDLE, State.DYING, State.DEAD -> return
            State.P1_AUTO -> {
                val next = queued.removeFirstOrNull()
                when {
                    next != null -> startBlast(next)
                    now >= nextAttackTick -> magicAuto()
                    else -> maybeTurnToNext()
                }
            }
            State.P2_AUTO -> {
                if (now >= nextAttackTick) {
                    if (healBlastsQueued > 0) startHealBlast() else p2Auto()
                } else if (!maybeSummon()) {
                    maybeTurnToNext()
                }
            }
            State.P1_BLAST, State.P2_TRANSITION, State.P2_HEAL_BLAST -> {}
        }
        if (inPhase2) growFlames()
    }

    /** End of the tick (spec §2.5 step 4): flames hurt whoever stands on them. */
    fun tickHazards() {
        if (!inPhase2) return
        flames.tick(timeline.now, host.fighters(), random) { f, dmg, burn ->
            host.damage(f, dmg)
            if (burn) host.burn(f, 1)
        }
    }

    /** A player died or left the arena. */
    fun onFighterLost(f: Fighter) {
        if (bound.remove(f)) host.release(f)
        flames.forget(f)
        if (meleeTarget === f) meleeTarget = null
        if (preTarget === f) preTarget = null
    }

    /** His HP reached 0 (spec "DYING"): everything of his is cancelled; the room completes after the death seq. */
    fun onDeath() {
        if (isDead) return
        val now = timeline.now
        state = State.DYING
        queued.clear()
        healBlastsQueued = 0
        currentBlast = null
        preTarget = null
        timeline.cancel(Tag.BOSS)
        timeline.cancel(Tag.CORPSE)
        timeline.cancel(Tag.FLAME)
        releaseAll()
        host.bossTargetable(false)
        host.bossAnimate(XarthIds.SEQ_DEATH)
        host.bossGraphic(XarthIds.SPOT_DEATH)
        busyUntil = now + XarthIds.Ticks.DEATH
        host.killCorpses()
        flames.clear()
        for (pile in busyPiles.toList()) host.pileFire(pile, false)
        busyPiles.clear()
        pendingCorpses = 0
        timeline.at(now + cfg.death.completeTick, Tag.DEATH) {
            state = State.DEAD
            deathComplete = true
            host.bossDespawn()
        }
    }

    /** Whether a player hit may show his defend seq 15018 (spec [C+]: "when he is not attacking"). */
    fun canDefend(): Boolean {
        val now = timeline.now
        return (state == State.P1_AUTO || state == State.P2_AUTO) && now >= busyUntil && now >= turnUntil
    }

    // =================================================================================================== phase 1

    private fun magicAuto() {
        val now = timeline.now
        val candidates = host.fighters().filter { it.alive && exposed(it) }
        if (candidates.isEmpty()) return // nobody in sight: try again next tick
        val target = preTarget?.takeIf { it in candidates } ?: candidates[random.nextInt(candidates.size)]
        preTarget = null
        faceNow(target)
        host.bossAnimate(XarthIds.SEQ_MAGIC)
        busyUntil = now + XarthIds.Ticks.MAGIC
        host.projectile(Proj.MAGIC, target)
        val land = now + cfg.projectiles.magic.flightTicks(XarthArena.distanceFromCentre(target.tile))
        timeline.at(land, Tag.BOSS) {
            if (!target.alive) return@at
            val accurate = host.attack(target, AttackStyle.MAGIC, cfg.magicMax)
            host.playerGraphic(target, XarthIds.SPOT_MAGIC_IMPACT)
            // spec §4.7: a burn stack on every successful accuracy roll, even when Protect from Magic zeroes the hit.
            if (accurate && cfg.magicBurn > 0) host.burn(target, cfg.magicBurn)
        }
        engaged = true
        nextAttackTick = now + cfg.attackSpeed
    }

    private fun startBlast(pct: Int) {
        val t0 = timeline.now
        state = State.P1_BLAST
        currentBlast = pct
        preTarget = null
        host.bossAnimate(XarthIds.SEQ_ROOT)
        busyUntil = t0 + XarthIds.Ticks.ROOT
        // t+0: bind everyone (TD rule), choose their bomb tiles now (their tile + one of their 3x3).
        val bombs = LinkedHashMap<Fighter, List<StaticTile>>()
        for (f in host.fighters()) {
            if (!f.alive) continue
            host.playerGraphic(f, XarthIds.SPOT_ROOT)
            if (cfg.blast.bindTicks > 0) {
                host.bind(f, cfg.blast.bindTicks)
                bound += f
            }
            bombs[f] = XarthArena.bombTiles(f.tile, standing, cfg.blast.bombsPerPlayer, random)
        }
        // t+3: the aoe throw and the two searing projectiles per player (they land 2 t later).
        timeline.at(t0 + cfg.blast.aoeTick, Tag.BOSS) {
            host.bossAnimate(XarthIds.SEQ_AOE)
            host.bossGraphic(XarthIds.SPOT_AOE)
            busyUntil = timeline.now + XarthIds.Ticks.AOE
            val tiles = bombs.values.flatten()
            for (t in tiles) host.projectile(Proj.BOMB, t)
            val distinct = tiles.toSet()
            if (distinct.isNotEmpty()) {
                timeline.at(timeline.now + cfg.projectiles.bomb.flightTicks(0), Tag.BOSS) { landBombs(distinct) }
            }
        }
        // t+4: he charges the explosive blast (4380 holds 2 t, then bursts).
        timeline.at(t0 + cfg.blast.chargeTick, Tag.BOSS) {
            host.bossAnimate(XarthIds.SEQ_FLAME_BLAST)
            host.bossGraphic(XarthIds.SPOT_FLAME_BLAST)
            busyUntil = timeline.now + XarthIds.Ticks.FLAME_BLAST
            if (cfg.blast.coverRule == CoverRule.FACING) {
                facing = XarthArena.mostPlayersAngle(host.fighters().filter { it.alive }.map { it.tile }, facing)
                host.bossFaceTile(tileToward(facing))
            }
        }
        timeline.at(t0 + cfg.blast.resolveTick, Tag.BOSS) { resolveBlast() }
        timeline.at(t0 + cfg.blast.endTick, Tag.BOSS) { endBlast(pct) }
    }

    private fun landBombs(tiles: Set<StaticTile>) {
        for (t in tiles) host.tileGraphic(XarthIds.SPOT_BOMB_IMPACT, t)
        for (f in host.fighters()) {
            if (!f.alive || f.tile !in tiles) continue
            host.damage(f, roll(cfg.blast.bombDmg))
            if (cfg.blast.bombBurn > 0) host.burn(f, cfg.blast.bombBurn)
        }
    }

    /** t+7: clear line of sight takes the blast; the first pillar on a covered player's line collapses. */
    private fun resolveBlast() {
        val marked = LinkedHashSet<Pillar>()
        for (f in host.fighters()) {
            if (!f.alive) continue
            if (cfg.blast.coverRule == CoverRule.FACING && !XarthArena.inFacingHalfPlane(facing, f.tile)) continue
            val cover = coverOf(f)
            if (cover == null) {
                host.damage(f, roll(cfg.blast.blastDmg))
                if (cfg.blast.blastBurn > 0) host.burn(f, cfg.blast.blastBurn)
            } else {
                marked += cover
            }
        }
        for (p in marked) collapse(p)
        blastsDone++
    }

    private fun endBlast(pct: Int) {
        releaseAll()
        currentBlast = null
        if (pct == p2Pct) {
            startTransition()
        } else {
            state = State.P1_AUTO
            nextAttackTick = timeline.now
        }
    }

    private fun collapse(p: Pillar) {
        if (standing.remove(p)) host.collapsePillar(p)
    }

    /** The pillar covering [f] from his blast, or null when [f] is exposed (`blast.losTest`). */
    private fun coverOf(f: Fighter): Pillar? {
        val ray = XarthArena.coverPillar(f.tile, standing)
        if (cfg.blast.losTest == LosTest.RAY) return ray
        if (!host.losBlocked(f)) return null
        return ray ?: standing.minByOrNull { it.distance(f.tile) }
    }

    private fun exposed(f: Fighter): Boolean = coverOf(f) == null

    // =================================================================================================== phase 2

    private fun startTransition() {
        val t0 = timeline.now
        state = State.P2_TRANSITION
        queued.clear()
        reached.addAll(cfg.thresholds)
        preTarget = null
        host.bossAnimate(XarthIds.SEQ_SUMMON)
        busyUntil = t0 + XarthIds.Ticks.SUMMON
        if (cfg.transition.depleteSpot) {
            host.bossGraphic(XarthIds.SPOT_P2_DEPLETE)
            timeline.at(t0 + 1, Tag.BOSS) { host.bossGraphic(XarthIds.SPOT_P2_RESTORE) }
        } else {
            host.bossGraphic(XarthIds.SPOT_P2_RESTORE)
        }
        if (cfg.transition.collapseStanding) for (p in standing.toList()) collapse(p)
        timeline.at(t0 + cfg.transition.flamesLaunchTick, Tag.BOSS) { launchFlames(flames.initialCount(scaleOf())) }
        timeline.at(t0 + cfg.transition.autosTick, Tag.BOSS) {
            state = State.P2_AUTO
            nextAttackTick = timeline.now
        }
        nextSummonTick = t0 + cfg.transition.firstSummonTick
        nextFlameTick = t0 + cfg.transition.flamesLaunchTick + cfg.flames.addEvery
    }

    private fun launchFlames(count: Int) {
        val tiles = flames.pick(count, random)
        if (tiles.isEmpty()) return
        for (t in tiles) host.projectile(Proj.BOMB, t)
        timeline.at(timeline.now + cfg.projectiles.bomb.flightTicks(0), Tag.FLAME) {
            for (t in tiles) flames.land(t, timeline.now)
        }
    }

    private fun growFlames() {
        val now = timeline.now
        if (now < nextFlameTick) return
        nextFlameTick = now + cfg.flames.addEvery
        val n = flames.growth()
        if (n > 0) launchFlames(n)
    }

    private fun p2Auto() {
        val now = timeline.now
        val alive = host.fighters().filter { it.alive }
        if (alive.isEmpty()) return
        val adjacent = alive.filter { XarthArena.isMeleeAdjacent(it.tile) }
        val pre = preTarget
        preTarget = null
        if (adjacent.isNotEmpty()) {
            // Melee: his current target if it is still in reach, else a random adjacent player.
            val target = listOf(pre, meleeTarget).firstOrNull { it != null && it in adjacent }
                ?: adjacent[random.nextInt(adjacent.size)]
            meleeTarget = target
            faceNow(target)
            host.bossAnimate(XarthIds.SEQ_MELEE)
            busyUntil = now + XarthIds.Ticks.MELEE
            timeline.at(now + cfg.melee.hitDelay, Tag.BOSS) {
                if (target.alive) host.attack(target, AttackStyle.MELEE, cfg.meleeMax)
            }
        } else {
            val target = pre?.takeIf { it in alive } ?: alive[random.nextInt(alive.size)]
            faceNow(target)
            host.bossAnimate(XarthIds.SEQ_RANGED)
            host.bossGraphic(XarthIds.SPOT_RANGED_CAST)
            busyUntil = now + XarthIds.Ticks.RANGED
            host.projectile(Proj.RANGED, target)
            val land = now + cfg.projectiles.ranged.flightTicks(XarthArena.distanceFromCentre(target.tile))
            timeline.at(land, Tag.BOSS) {
                if (!target.alive) return@at
                host.attack(target, AttackStyle.RANGED, cfg.rangedMax)
                host.playerGraphic(target, XarthIds.SPOT_RANGED_IMPACT)
            }
        }
        nextAttackTick = now + cfg.attackSpeed
    }

    /** The interval to the next summon at his current HP (spec: 15 / 12 / 9 t above 35 / 20 / 0 %). */
    fun summonInterval(): Int {
        val max = host.bossMaxHp
        val pct = if (max <= 0) 0.0 else host.bossHp * 100.0 / max
        return (cfg.corpses.intervals.firstOrNull { pct > it.abovePct } ?: cfg.corpses.intervals.last()).ticks
    }

    /** Corpses per summon: `1 + floor((n − 1) / perSummonDiv)` (1 at n ≤ 3, 2 at 4-6, 3 at 7-8). */
    fun perSummon(): Int = 1 + (scaleOf() - 1) / cfg.corpses.perSummonDiv

    /** A summon when one is due and he is not mid-seq (never on an attack tick: the caller checked). */
    private fun maybeSummon(): Boolean {
        val now = timeline.now
        if (now < nextSummonTick || now < busyUntil) return false
        nextSummonTick = now + summonInterval()
        // DOC: "he will additionally reanimate corpses (using his magic attack animation)".
        host.bossAnimate(XarthIds.SEQ_MAGIC)
        busyUntil = now + XarthIds.Ticks.MAGIC
        reanimate(perSummon())
        summons++
        return true
    }

    /** Sets up to [k] free piles burning; each corpse appears `pileFireTicks` later. Returns how many. */
    private fun reanimate(k: Int): Int {
        val now = timeline.now
        val room = cfg.corpses.cap - host.corpsesAlive() - pendingCorpses
        val free = Pile.entries.filter { it !in busyPiles }.shuffled(random)
        val n = minOf(k, room, free.size)
        if (n <= 0) return 0
        for (pile in free.subList(0, n)) {
            busyPiles += pile
            pendingCorpses++
            host.pileFire(pile, true)
            timeline.at(now + cfg.corpses.pileFireTicks, Tag.CORPSE) {
                pendingCorpses--
                host.pileFire(pile, false)
                host.spawnCorpse(pile)
                if (cfg.corpses.pileBusy == PileBusy.SPAWNED) busyPiles -= pile
            }
        }
        return n
    }

    /** A fire spell hit a corpse (spec §4.7 [DOC]): one extra corpse at a random pile, at most once per cooldown. */
    fun requestExtraCorpse(): Boolean {
        if (!inPhase2) return false
        val now = timeline.now
        if (now < extraReadyTick) return false
        if (reanimate(1) == 0) return false
        extraReadyTick = now + cfg.corpses.fireExtraCooldown
        extraCorpses++
        return true
    }

    /** A corpse died or was absorbed: its pile frees up when `pileBusy = "dead"`. */
    fun onCorpseGone(pile: Pile) {
        if (cfg.corpses.pileBusy == PileBusy.DEAD) busyPiles -= pile
    }

    /** A corpse reached him with [hp] left (spec "Arrival"): he heals that much; 200 healed queues a heal blast. */
    fun onCorpseAbsorbed(hp: Int): Int {
        if (isDead || hp <= 0) return 0
        val healed = host.bossHeal(hp)
        healCounter += healed
        while (healCounter >= cfg.healBlast.threshold) {
            healCounter -= cfg.healBlast.threshold
            healBlastsQueued++
        }
        return healed
    }

    private fun startHealBlast() {
        val t0 = timeline.now
        healBlastsQueued--
        state = State.P2_HEAL_BLAST
        preTarget = null
        host.bossAnimate(XarthIds.SEQ_FLAME_BLAST)
        host.bossGraphic(XarthIds.SPOT_FLAME_BLAST)
        busyUntil = t0 + XarthIds.Ticks.FLAME_BLAST
        timeline.at(t0 + cfg.healBlast.burstTick, Tag.BOSS) { resolveHealBlast() }
        timeline.at(t0 + cfg.healBlast.resumeTick, Tag.BOSS) {
            state = State.P2_AUTO
            nextAttackTick = timeline.now
        }
    }

    /** The burst: unshielded players take the full roll + burn; players behind another on their line take less. */
    private fun resolveHealBlast() {
        val alive = host.fighters().filter { it.alive }
        for (p in alive) {
            val others = alive.filter { it !== p }.map { it.tile }
            val roll = roll(cfg.healBlast.dmg)
            if (XarthArena.isShielded(p.tile, others)) {
                host.damage(p, (roll * cfg.healBlast.shieldedMult).toInt())
                if (cfg.healBlast.shieldedBurn > 0) host.burn(p, cfg.healBlast.shieldedBurn)
            } else {
                host.damage(p, roll)
                if (cfg.healBlast.burn > 0) host.burn(p, cfg.healBlast.burn)
            }
        }
        healBlastsDone++
    }

    // ================================================================================================== facing

    private fun faceNow(target: Fighter) {
        facing = XarthArena.faceAngle(XarthArena.CENTRE, target.tile)
        host.bossFace(target)
    }

    /**
     * Spec [C+]: the turn seqs 14981 / 14982 play when he turns to face a target. He picks the next auto's target
     * `turn.leadTicks` before the attack (only while no other seq of his plays) and turns to it; the attack uses that
     * target if it is still valid.
     */
    private fun maybeTurnToNext() {
        if (!cfg.turn.enabled || preTarget != null) return
        val now = timeline.now
        if (now < busyUntil || nextAttackTick <= now || nextAttackTick - now > cfg.turn.leadTicks) return
        val alive = host.fighters().filter { it.alive }
        val pool = when (state) {
            State.P1_AUTO -> alive.filter { exposed(it) }
            State.P2_AUTO -> alive.filter { XarthArena.isMeleeAdjacent(it.tile) }.ifEmpty { alive }
            else -> emptyList()
        }
        if (pool.isEmpty()) return
        val target = (if (state == State.P2_AUTO) meleeTarget?.takeIf { it in pool } else null)
            ?: pool[random.nextInt(pool.size)]
        preTarget = target
        val angle = XarthArena.faceAngle(XarthArena.CENTRE, target.tile)
        val side = XarthArena.turnSide(facing, angle, cfg.turn.minAngle)
        facing = angle
        host.bossFace(target)
        when (side) {
            TurnSide.LEFT -> host.bossAnimate(XarthIds.SEQ_TURN_LEFT)
            TurnSide.RIGHT -> host.bossAnimate(XarthIds.SEQ_TURN_RIGHT)
            TurnSide.NONE -> return
        }
        turnUntil = now + XarthIds.Ticks.TURN
    }

    /** A tile 6 tiles from his centre in the direction of client angle [angle]. */
    private fun tileToward(angle: Int): StaticTile {
        val theta = angle / 325.949
        val dx = (-sin(theta) * 6).roundToInt()
        val dy = (-cos(theta) * 6).roundToInt()
        return XarthArena.CENTRE.offset(dx, dy)
    }

    private fun releaseAll() {
        for (f in bound) host.release(f)
        bound.clear()
    }

    private fun roll(range: IntRange): Int = range.first + random.nextInt(range.last - range.first + 1)

    // =================================================================================================== debug

    /** `::tfa skipto p1`: back to a fresh phase 1 (thresholds re-armed, pillars restored by the caller). */
    fun debugRestart() {
        val now = timeline.now
        clearForDebug()
        flames.clear()
        host.killCorpses()
        reached.clear()
        standing.addAll(Pillar.entries)
        nextSummonTick = Int.MAX_VALUE
        nextFlameTick = Int.MAX_VALUE
        healCounter = 0
        state = State.P1_AUTO
        nextAttackTick = now + 1
        busyUntil = now
    }

    /** `::tfa skipto blast<pct>`: thresholds above [pct] count as done; the [pct] blast runs on the next tick. */
    fun debugBlast(pct: Int): Boolean {
        if (pct !in cfg.thresholds || isDead || inPhase2) return false
        clearForDebug()
        reached.clear()
        reached.addAll(cfg.thresholds.filter { it >= pct })
        queued.addLast(pct)
        state = State.P1_AUTO
        busyUntil = timeline.now
        return true
    }

    /** `::tfa skipto p2`: straight into the phase-2 transition (P1 thresholds count as done). */
    fun debugToP2(): Boolean {
        if (isDead) return false
        if (inPhase2) return true
        clearForDebug()
        startTransition()
        return true
    }

    /** `::tfa skipto healblast`: into phase 2 if needed, then a heal blast at his next attack slot. */
    fun debugHealBlast(): Boolean {
        if (!debugToP2()) return false
        healBlastsQueued++
        return true
    }

    /** `::tfa xarth corpses`: a summon now (phase 2 only). */
    fun debugSummon(): Int = if (inPhase2 && !isDead) reanimate(perSummon()) else 0

    private fun clearForDebug() {
        timeline.cancel(Tag.BOSS)
        timeline.cancel(Tag.CORPSE)
        timeline.cancel(Tag.FLAME)
        releaseAll()
        queued.clear()
        currentBlast = null
        preTarget = null
        meleeTarget = null
        healBlastsQueued = 0
        for (pile in busyPiles.toList()) host.pileFire(pile, false)
        busyPiles.clear()
        pendingCorpses = 0
    }

    fun describe(): List<String> = buildList {
        val now = timeline.now
        add("  state $state, tick $now, hp ${host.bossHp}/${host.bossMaxHp}, facing $facing, busy until $busyUntil")
        add("  thresholds reached ${reached.sorted().reversed()}, queued $queued, blast $currentBlast, blasts done $blastsDone")
        add("  next attack ${if (nextAttackTick == Int.MAX_VALUE) "-" else nextAttackTick}, pillars standing ${standing.joinToString { it.name }}, bound ${bound.size}")
        add("  heal counter $healCounter/${cfg.healBlast.threshold}, heal blasts queued $healBlastsQueued, done $healBlastsDone")
        add("  summons $summons (+$extraCorpses extra), next summon ${if (nextSummonTick == Int.MAX_VALUE) "-" else nextSummonTick}, " +
            "busy piles ${busyPiles.joinToString { it.name }}, pending corpses $pendingCorpses")
        add("  flames ${flames.landedTiles.size} landed + ${flames.pendingTiles.size} in flight, next growth ${if (nextFlameTick == Int.MAX_VALUE) "-" else nextFlameTick}")
    }
}
