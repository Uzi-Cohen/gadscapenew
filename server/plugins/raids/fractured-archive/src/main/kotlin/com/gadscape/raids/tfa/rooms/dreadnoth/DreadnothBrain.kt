package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.rooms.dreadnoth.Timeline.Tag
import java.util.Random

/**
 * Dreadnoth's script (spec §4.5 state machine and attack table), free of engine types so it is unit tested with a
 * recording [DreadnothHost]. The room calls [tick] once per server tick after advancing the [timeline], and
 * [checkThresholds] after every hit on the boss (and each tick, so `::tfa hp` and heals are seen too).
 *
 * ```
 * IDLE --start--> CHASE --85/65/45%--> THRESHOLD(k) --k cycles--> CHASE ... --25%--> ENRAGE --HP 0--> DYING --+5 t--> DEAD
 * ```
 * - CHASE: one step toward the target on every [DreadnothConfig.moveEveryTicks]-th tick; the target is the nearest
 *   player (random tie-break), re-chosen on every attack. AoE melee (seq 14779) every `attackSpeed` ticks while anyone
 *   is within `melee.reach` of the footprint; the hit lands `melee.hitDelay` later on everyone who was in reach.
 *   Every n-th melee drops a blood tile under a random player.
 * - THRESHOLD(k), k = 1, 2, 3 for 85, 65, 45%: k cycles of [hop 14777 → arena-wide slam → 3 volleys of 4211], a jelly
 *   batch at the end of each cycle (`thresholdMode = "multihit"`: k hops, then 3 volleys and one batch). No movement,
 *   no melee. Thresholds crossed together queue in order and run back to back; the enrage queues behind them.
 * - ENRAGE (25%): a jump every `jump.period` ticks onto a random player: shadow 4209 and leap 14780 at t0
 *   (untargetable), landing at `jump.landTick` (40-70 inside the 5x5, halved by Protect from Melee, heals 30-40 per
 *   player hit; a 45 melee hit elsewhere without Protect from Melee), ranged 4211 + splatter 4212 at
 *   `jump.followUpTick`, blood where the splatter lands.
 * - DYING: seq 14782, the death wave at `death.waveTick`, despawn at `death.despawnTick`. Every pending boss action
 *   (including projectiles in flight) is cancelled.
 */
class DreadnothBrain(
    private val cfg: DreadnothConfig,
    private val timeline: Timeline,
    private val random: Random,
    private val host: DreadnothHost,
    /** The party scale n (2..8) the encounter started with: jelly counts. */
    private val scale: Int,
) {

    enum class State { IDLE, CHASE, THRESHOLD, ENRAGE, DYING, DEAD }

    /** A queued boss sequence. */
    sealed class Step {
        /** The threshold at [pct]% that runs [cycles] cycles. */
        data class Threshold(val pct: Int, val cycles: Int) : Step()
        object Enrage : Step() {
            override fun toString() = "Enrage"
        }
    }

    var state: State = State.IDLE
        private set

    /** The sequence running now (THRESHOLD / ENRAGE), or null. */
    var current: Step? = null
        private set

    private val queue = ArrayDeque<Step>()
    private val fired = LinkedHashSet<Int>()
    private var enrageQueued = false

    /** The chase target. */
    var target: Fighter? = null
        private set

    private var nextAttackTick = 0

    /** AoE melee attacks so far (every n-th drops blood). */
    var meleeCount = 0
        private set

    /** Enrage jumps so far. */
    var jumpCount = 0
        private set

    /** True between a jump's take-off and its landing. */
    var airborne = false
        private set

    /** South-west tile of the current or last landing area. */
    var landing: StaticTile? = null
        private set

    /** True once the death wave has been spawned. */
    var deathWaveSpawned = false
        private set

    /** Queued sequences, in run order. */
    val queued: List<Step> get() = queue.toList()

    /** Threshold percentages already reached. */
    val reached: Set<Int> get() = fired.toSet()

    val isDead: Boolean get() = state == State.DYING || state == State.DEAD

    // ------------------------------------------------------------------------------------------------ lifecycle

    /** The fight begins (first transporter use). */
    fun start() {
        if (state != State.IDLE) return
        state = State.CHASE
        nextAttackTick = timeline.now
        checkThresholds()
    }

    /** Once per tick, after [Timeline.advance]. */
    fun tick() {
        when (state) {
            State.IDLE, State.DYING, State.DEAD -> return
            State.CHASE -> {
                checkThresholds()
                chase()
            }
            State.THRESHOLD, State.ENRAGE -> {
                checkThresholds()
                faceTarget()
            }
        }
    }

    /**
     * Queues every threshold the boss's HP is at or below that has not run yet, highest first, then the enrage
     * (spec §2.5 step 2). Sequences start on the boss's next tick.
     */
    fun checkThresholds() {
        if (isDead) return
        val hp = host.bossHp.toLong()
        val max = host.bossMaxHp.toLong()
        if (hp <= 0 || max <= 0) return
        cfg.thresholds.forEachIndexed { index, pct ->
            if (pct !in fired && hp * 100 <= pct * max) {
                fired += pct
                queue.addLast(Step.Threshold(pct, index + 1))
            }
        }
        if (!enrageQueued && hp * 100 <= cfg.enragePct * max) {
            enrageQueued = true
            queue.addLast(Step.Enrage)
        }
    }

    /** The boss's HP reached 0. */
    fun onDeath() {
        if (isDead) return
        timeline.cancel(Tag.BOSS)
        queue.clear()
        current = null
        airborne = false
        state = State.DYING
        host.bossHalt()
        host.bossAnimate(DreadnothIds.SEQ_DEATH)
        host.bossTargetable(false)
        val now = timeline.now
        timeline.at(now + cfg.death.waveTick, Tag.DEATH) {
            deathWaveSpawned = true
            if (cfg.jelly.deathWave > 0) host.spawnDeathWave(cfg.jelly.deathWave)
        }
        timeline.at(now + cfg.death.despawnTick, Tag.DEATH) {
            state = State.DEAD
            host.bossDespawn()
        }
    }

    /** A player died or left the arena. */
    fun onFighterLost(fighter: Fighter) {
        if (target == fighter) target = null
    }

    // ----------------------------------------------------------------------------------------------- chase

    private fun chase() {
        if (queue.isNotEmpty()) {
            beginNext()
            return
        }
        val now = timeline.now
        val alive = host.fighters().filter { it.alive }
        if (alive.isEmpty()) {
            target = null
            host.bossFace(null)
            return
        }
        val sw = host.bossSw
        val current = target
        if (current == null || !current.alive || current !in alive) target = nearest(alive, sw)
        if (now >= nextAttackTick) {
            val zone = alive.filter { DreadnothArena.inMeleeZone(sw, it.tile, cfg.melee.reach) }
            if (zone.isNotEmpty()) {
                melee(zone)
                nextAttackTick = now + cfg.attackSpeed
                target = nearest(alive, sw) // re-evaluated every attack
            }
        }
        host.bossFace(target)
        val t = target ?: return
        if (now % cfg.moveEveryTicks == 0 && !DreadnothArena.inMeleeZone(sw, t.tile, cfg.melee.reach)) {
            val (dx, dy) = DreadnothArena.stepToward(sw, t.tile)
            if (dx != 0 || dy != 0) host.bossStep(dx, dy)
        }
    }

    /** The nearest player to the footprint; ties broken at random. */
    private fun nearest(alive: List<Fighter>, sw: StaticTile): Fighter? {
        if (alive.isEmpty()) return null
        val best = alive.minOf { DreadnothArena.distanceToFootprint(sw, it.tile) }
        val tied = alive.filter { DreadnothArena.distanceToFootprint(sw, it.tile) == best }
        return if (tied.size == 1) tied[0] else tied[random.nextInt(tied.size)]
    }

    private fun faceTarget() {
        val t = target
        if (t != null && !t.alive) target = null
        host.bossFace(target)
    }

    private fun melee(zone: List<Fighter>) {
        host.bossAnimate(DreadnothIds.SEQ_MELEE)
        meleeCount++
        val victims = zone.toList()
        val land = timeline.now + cfg.melee.hitDelay
        timeline.at(land, Tag.BOSS) {
            for (v in victims) if (v.alive) host.attack(v, AttackStyle.MELEE, cfg.meleeMax)
        }
        val every = cfg.blood.periodicEveryNthMelee
        if (every > 0 && meleeCount % every == 0) {
            timeline.at(land, Tag.BOSS) {
                val pool = host.fighters().filter { it.alive }
                if (pool.isNotEmpty()) host.blood(pool[random.nextInt(pool.size)].tile)
            }
        }
    }

    // ------------------------------------------------------------------------------------------- sequences

    private fun beginNext() {
        val step = queue.removeFirstOrNull()
        current = step
        when (step) {
            null -> state = State.CHASE
            is Step.Threshold -> runThreshold(step.cycles)
            Step.Enrage -> enterEnrage()
        }
    }

    private fun sequenceDone() {
        current = null
        if (queue.isNotEmpty()) {
            beginNext()
            return
        }
        state = State.CHASE
        nextAttackTick = maxOf(nextAttackTick, timeline.now)
    }

    private fun runThreshold(k: Int) {
        state = State.THRESHOLD
        host.bossHalt()
        val start = timeline.now
        val total = JellyPlanner.count(cfg.jelly, scale)
        val end: Int
        when (cfg.thresholdMode) {
            DreadnothConfig.ThresholdMode.CYCLES -> {
                val split = JellyPlanner.split(total, k)
                for (c in 0 until k) {
                    val base = start + c * cfg.cycleLength
                    timeline.at(base, Tag.BOSS) { hop() }
                    timeline.at(base + cfg.slam.hitDelay, Tag.BOSS) { slam() }
                    for (v in cfg.volleyTicks) timeline.at(base + v, Tag.BOSS) { volley() }
                    val batch = split[c]
                    timeline.at(base + cfg.cycleLength, Tag.BOSS) { if (batch > 0) host.spawnJellies(batch) }
                }
                end = start + k * cfg.cycleLength
            }
            DreadnothConfig.ThresholdMode.MULTIHIT -> {
                for (i in 0 until k) {
                    val base = start + 2 * i
                    timeline.at(base, Tag.BOSS) { hop() }
                    timeline.at(base + cfg.slam.hitDelay, Tag.BOSS) { slam() }
                }
                val vBase = start + 2 * (k - 1)
                for (v in cfg.volleyTicks) timeline.at(vBase + v, Tag.BOSS) { volley() }
                timeline.at(vBase + cfg.cycleLength, Tag.BOSS) { if (total > 0) host.spawnJellies(total) }
                end = vBase + cfg.cycleLength
            }
        }
        timeline.at(end, Tag.BOSS) { sequenceDone() }
    }

    /** The threshold slam's take-off: hop 14777 in place (1.8 t). */
    private fun hop() {
        host.bossAnimate(DreadnothIds.SEQ_HOP)
    }

    /** Arena-wide slam: a melee hit on every player in the arena; Protect from Melee blocks it. */
    private fun slam() {
        for (f in host.fighters()) if (f.alive) host.attack(f, AttackStyle.MELEE, cfg.slamMax)
    }

    /** One ranged volley: projectile 4211 to every player (or the target), the hit landing on arrival. */
    private fun volley() {
        if (cfg.volley.seq) host.bossAnimate(DreadnothIds.SEQ_MELEE)
        val alive = host.fighters().filter { it.alive }
        val targets = if (cfg.volley.allPlayers) alive else listOfNotNull(target?.takeIf { it.alive } ?: nearest(alive, host.bossSw))
        for (f in targets) shootRanged(f)
    }

    private fun shootRanged(f: Fighter) {
        val spec = cfg.projectiles.ranged
        val distance = DreadnothArena.chebyshev(DreadnothArena.centreOf(host.bossSw), f.tile)
        host.projectile(DreadnothIds.SPOT_RANGED_PROJ, spec, f)
        timeline.after(spec.flightTicks(distance), Tag.BOSS) {
            if (f.alive) host.attack(f, AttackStyle.RANGED, cfg.rangedMax)
        }
    }

    // ------------------------------------------------------------------------------------------------ enrage

    private fun enterEnrage() {
        state = State.ENRAGE
        host.bossHalt()
        host.onEnrage()
        timeline.at(timeline.now + cfg.jump.firstDelay, Tag.BOSS) { jump() }
    }

    private fun jump() {
        val alive = host.fighters().filter { it.alive }
        if (alive.isEmpty()) {
            timeline.after(1, Tag.BOSS) { jump() }
            return
        }
        val p = alive[random.nextInt(alive.size)]
        val from = host.bossSw
        val area = DreadnothArena.landingArea(p.tile)
        jumpCount++
        airborne = true
        landing = area
        target = p
        host.bossFace(p)
        host.bossAnimate(DreadnothIds.SEQ_JUMP)
        host.graphic(DreadnothIds.SPOT_JUMP_SHADOW, DreadnothArena.centreOf(area))
        host.bossTargetable(false)
        host.bossLeap(from, area, cfg.jump.moveStartCycle, cfg.jump.moveEndCycle)
        val start = timeline.now
        timeline.at(start + cfg.jump.landTick, Tag.BOSS) { land(area) }
        timeline.at(start + cfg.jump.followUpTick, Tag.BOSS) { followUp(p) }
        timeline.at(start + cfg.jump.period, Tag.BOSS) { jump() }
    }

    private fun land(area: StaticTile) {
        airborne = false
        host.bossTargetable(true)
        val jump = cfg.jump
        for (f in host.fighters()) {
            if (!f.alive) continue
            if (DreadnothArena.inFootprint(area, f.tile)) {
                var dmg = roll(jump.landingDmg)
                if (f.praying(Protect.MELEE)) dmg = (dmg * jump.pfmMult).toInt()
                val dealt = host.damage(f, dmg)
                if (dealt > 0) {
                    val heal = roll(jump.heal)
                    if (heal > 0) host.bossHeal(heal)
                }
            } else if (!f.praying(Protect.MELEE)) {
                host.attack(f, AttackStyle.MELEE, jump.noPrayerMax)
            }
        }
    }

    private fun followUp(p: Fighter) {
        val targets = if (cfg.jump.followUpOnlyTarget) listOf(p) else host.fighters()
        for (f in targets) if (f.alive) shootRanged(f)
        if (!p.alive) return
        val tile = p.tile
        val spec = cfg.projectiles.splatter
        val distance = DreadnothArena.chebyshev(DreadnothArena.centreOf(host.bossSw), tile)
        host.projectile(DreadnothIds.SPOT_SPLATTER_PROJ, spec, tile)
        timeline.after(spec.flightTicks(distance), Tag.BLOOD) { host.blood(tile) }
    }

    private fun roll(range: IntRange): Int =
        if (range.last <= range.first) range.first else range.first + random.nextInt(range.last - range.first + 1)

    // ------------------------------------------------------------------------------------------------- debug

    /**
     * `::tfa skipto`: drops the running sequence and the queue, back to CHASE, and treats the thresholds above
     * [skipAbovePct] as already passed (and the enrage as not reached). The room then sets the HP.
     */
    fun debugReset(skipAbovePct: Int) {
        if (isDead) return
        timeline.cancel(Tag.BOSS)
        queue.clear()
        current = null
        airborne = false
        host.bossTargetable(true)
        fired.clear()
        fired += cfg.thresholds.filter { it > skipAbovePct }
        enrageQueued = cfg.enragePct > skipAbovePct
        state = State.CHASE
        nextAttackTick = timeline.now
    }

    fun describe(): List<String> = listOf(
        "Dreadnoth: $state${current?.let { " ($it)" } ?: ""}, queue $queued, reached ${fired.toList()}, enrage queued $enrageQueued",
        "  hp ${host.bossHp}/${host.bossMaxHp}, sw ${host.bossSw}, target ${target?.tile}, next melee at $nextAttackTick (now ${timeline.now})",
        "  melee $meleeCount, jumps $jumpCount, airborne $airborne, landing $landing, death wave $deathWaveSpawned",
    )
}
