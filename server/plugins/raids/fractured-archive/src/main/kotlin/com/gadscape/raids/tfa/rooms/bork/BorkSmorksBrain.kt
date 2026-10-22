package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.rooms.bork.Timeline.Tag
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The scripted fight of Bork and the Smorks (spec §4.3), engine-free: [BorkHost] is the world. Driven once per tick by
 * [BorkSmorksRoom.tick] after the [timeline] has run its due actions (impacts, sequence steps):
 *
 * **Bork** (16640): CHASE his target (the first transporter user, then a random other player after every stomp or gong
 * sequence: "Bork smash <name> now." [DOC]) and beat his drum every tempo tick (7 / 6 / 4 by his own HP):
 * - small beat (target within the 6x6 around his 4x4): the 6x6; large beat (target out of reach and the previous beat
 *   was not large [DOC]): radius min(distance, 5). Seq 14542 + spot 4037; lands +2 t (impact synth at cycle 58) on
 *   every player then on the area, except tiles in the cart's shadow. Crush, accuracy roll, max 34, PfM × 0.5.
 * - DAZED when a heavy cannonball lands on any tile of his 4x4: 2 × tempo ticks (14 / 12 / 8 t, seq 14545 / 14544 /
 *   14543 + spot 4041 / 4040 / 4038 + stars 4035), flat armour and magic resistance 0, player melee skips accuracy.
 *   Then "Bork very angry!" and the stomp sequence from the daze pose (14549), or 50/50 "Bork crush now." and the gong
 *   barrage from the daze pose (14546) [C+ U73].
 * - a heavy ball that misses him (while chasing) starts at once 50/50 "Bork think it stomp time now!" + stomps from
 *   idle (14556) or "Bork crush now." + gong from idle (14557).
 * - STOMP: in → 14550 (6 t, 2 stomps) → 14551 (7 t, 2 stomps and out) + spot 4039 with each; 4 stomps at +3/+6/+9/+12
 *   after the daze entry (1 t earlier after the idle entry); each hits every player in the arena outside the cart's
 *   shadow (max 40, PfM × 0.5); then a new target.
 * - GONG: in (1 t) → 14547 looping (one beat per tick) → 14548; 8 arena-wide hits, 1 per tick, max 12 (shadow safe).
 * - Smorks dead (enraged): no shadow anywhere; 2 large beats (radius 5), then a stomp sequence from idle, repeat.
 * - Death: 14541 (6.5 t) + spot 4036, despawn at +7 t.
 *
 * **The Smorks** (16641, static on the cart): every tempo tick (own HP) an attack on every player who is not Bork's
 * target (that player too when nobody else is alive): attacks 1-3 of every 4 a volley (14554 + 4042; bones 4056
 * ranged or potions 4057 magic, one style per volley, prayer checked on impact, max 28), attack 4 a cannonball (14555 +
 * 4043 + 4044), incendiary and heavy alternating, incendiary first:
 * - incendiary ("Burny gloop!"): 4045 onto a random target's tile (+ marker 4443), lands +2 t: 4046, 30 typeless in
 *   the 3x3, 5% of Bork's max HP if his 4x4 is in it, then 7 oil tiles in the 5x5 ([OilField]: 6-7 + a burn stack per
 *   tick, 30 t).
 * - heavy ("Big bomb. Bork watch out!"): shadow 4442 on the tile (locked at fire time), 4440 lands +4 t: 35 typeless in
 *   the 3x3, and the daze above if any tile of Bork's 4x4 is in it.
 * - Bork dead (enraged): speed 3, every player, max hits × 1.25, incendiary only.
 * - Death: spots 4060 / 4061 / 4062 on the cart, despawn at +6 t (the cart locs stay).
 *
 * Every number comes from [cfg]; every roll from [random].
 */
class BorkSmorksBrain(
    private val cfg: BorkSmorksConfig,
    private val timeline: Timeline,
    private val random: Random,
    private val host: BorkHost,
    private val shadow: CartShadow,
) {

    enum class BorkState { IDLE, CHASE, DAZED, STOMP, GONG, DYING, DEAD }
    enum class SmorksState { IDLE, ACTIVE, DYING, DEAD }

    /** Why a stomp sequence started (sets the overhead). */
    enum class Cause { DAZE, MISSED_HEAVY, ENRAGE }

    enum class Special { INCENDIARY, HEAVY }

    val oil = OilField(cfg.smorks.oilLifetime, cfg.smorks.oilDmg, object : OilField.Sink {
        override fun spawn(tile: StaticTile, rotation: Int) = host.oilSpawn(tile, rotation)
        override fun fade(tile: StaticTile, rotation: Int) = host.oilFade(tile, rotation)
        override fun remove(tile: StaticTile) = host.oilRemove(tile)
    })

    var borkState: BorkState = BorkState.IDLE
        private set
    var smorksState: SmorksState = SmorksState.IDLE
        private set

    /** Bork's current target ("tank"); null while nobody is alive in the arena. */
    var target: Fighter? = null
        private set

    var started: Boolean = false
        private set

    private var borkClock = 0
    private var lastBeatLarge = false
    private var enragedBeats = 0
    private var smorksClock = 0
    private var nextSpecial: Special = if (cfg.smorks.firstSpecial == "heavy") Special.HEAVY else Special.INCENDIARY

    // Counters for `::tfa dump` and the tests.
    var beats = 0; private set
    var largeBeats = 0; private set
    var stomps = 0; private set
    var gongHits = 0; private set
    var dazes = 0; private set
    var smorksAttacks = 0; private set
    var volleys = 0; private set
    var incendiaries = 0; private set
    var heavies = 0; private set
    var retargets = 0; private set

    val borkDead: Boolean get() = borkState == BorkState.DYING || borkState == BorkState.DEAD
    val smorksDead: Boolean get() = smorksState == SmorksState.DYING || smorksState == SmorksState.DEAD
    val isDazed: Boolean get() = borkState == BorkState.DAZED

    /** The Smorks are dead: Bork "continuously stomps" and nothing is shadowed. */
    val borkEnraged: Boolean get() = smorksDead && !borkDead

    /** Bork is dead: the Smorks attack faster, hit everyone, hit harder, fire incendiaries only. */
    val smorksEnraged: Boolean get() = borkDead && !smorksDead

    /** Both NPCs dead (and, with `completeAfterDeathAnims`, both death sequences played). */
    val bothDone: Boolean
        get() = if (cfg.completeAfterDeathAnims) borkState == BorkState.DEAD && smorksState == SmorksState.DEAD
        else borkDead && smorksDead

    // ======================================================================================== tempo and traits

    fun borkSpeed(): Int = Tempo.speed(cfg.tempo, host.borkHp, host.borkMaxHp)

    fun smorksSpeed(): Int =
        if (smorksEnraged) cfg.smorks.enragedSpeed else Tempo.speed(cfg.tempo, host.smorksHp, host.smorksMaxHp)

    /** Bork's flat armour now: 0 dazed, 10 at or below 66% HP, else 12 (spec §4.3, [HOUSE] step). */
    fun borkFlatArmour(): Int {
        if (isDazed) return 0
        val max = host.borkMaxHp
        val above = max <= 0 || host.borkHp * 100.0 / max > cfg.bork.armourStepPct
        return if (above) cfg.bork.flatArmour else cfg.bork.flatArmourBelow66
    }

    fun borkMagicResist(): Int = if (isDazed) 0 else cfg.bork.magicResist

    // ============================================================================================== lifecycle

    /** The fight begins: [firstTank] (the first transporter user) is Bork's target. */
    fun start(firstTank: Fighter?) {
        if (started) return
        started = true
        if (borkState == BorkState.IDLE) borkState = BorkState.CHASE
        if (smorksState == SmorksState.IDLE) smorksState = SmorksState.ACTIVE
        target = firstTank?.takeIf { it.alive }
        host.primaryTarget(target)
        borkClock = cfg.bork.firstAttackDelay
        smorksClock = cfg.smorks.firstAttackDelay
    }

    /** One tick, after the timeline ran: Bork, the Smorks, then the oil at the end of the tick (§2.5 order). */
    fun tick() {
        if (!started) return
        if (borkState == BorkState.CHASE) tickChase()
        if (smorksState == SmorksState.ACTIVE) tickSmorks()
        oil.tick(timeline.now, host.fighters(), random) { f, dmg ->
            host.damage(f, dmg)
            if (cfg.smorks.burnStacks > 0) host.burn(f, cfg.smorks.burnStacks)
        }
    }

    /**
     * A player died or left the arena. If it was Bork's target he picks (and announces) a new one on his next tick; the
     * Smorks stop shooting at a dead player at once ([Fighter.alive]).
     */
    fun onFighterLost(f: Fighter) {
        if (target === f) host.primaryTarget(null)
    }

    // ================================================================================================== Bork

    private fun tickChase() {
        var t = target
        if (t == null || !t.alive) {
            t = retarget(announce = t != null)
            if (t == null) {
                host.borkHalt()
                return
            }
        }
        val sw = host.borkSw ?: return
        host.borkFace(t)
        val dist = BorkArena.distToFootprint(sw, BorkArena.BORK_SIZE, t.tile)
        if (dist > 1) host.borkChase(t) else host.borkHalt()
        if (--borkClock > 0) return
        if (borkEnraged) {
            if (enragedBeats < cfg.bork.enragedLargeBeats) {
                enragedBeats++
                beat(sw, large = true, radius = cfg.bork.largeBeatRadius)
            } else {
                enragedBeats = 0
                startStomp(fromDaze = false, cause = Cause.ENRAGE)
                return
            }
        } else {
            val large = dist > 1 && !lastBeatLarge
            lastBeatLarge = large
            beat(sw, large, if (large) min(dist, cfg.bork.largeBeatRadius) else 1)
        }
        borkClock = borkSpeed()
    }

    /** Picks a new target: a random alive player, preferring anyone but the current/previous one. */
    private fun retarget(announce: Boolean): Fighter? {
        val alive = host.fighters().filter { it.alive }
        if (alive.isEmpty()) {
            if (target != null) host.primaryTarget(null)
            target = null
            return null
        }
        val previous = target
        val pool = alive.filter { it !== previous }.ifEmpty { alive }
        val pick = pool[random.nextInt(pool.size)]
        target = pick
        retargets++
        host.primaryTarget(pick)
        if (announce) host.borkSay(cfg.bork.texts.smash.replace("%s", pick.name))
        return pick
    }

    /** The beat area: arena tiles within [radius] of his 4x4, minus the cart's shadow while the Smorks live. */
    internal fun beatArea(sw: StaticTile, radius: Int): List<StaticTile> {
        val useShadow = !smorksDead
        return BorkArena.WALKABLE.filter {
            BorkArena.distToFootprint(sw, BorkArena.BORK_SIZE, it) <= radius &&
                !(useShadow && shadow.isShadowed(sw, BorkArena.BORK_SIZE, it))
        }
    }

    private fun beat(sw: StaticTile, large: Boolean, radius: Int) {
        beats++
        if (large) largeBeats++
        host.borkAnimate(BorkIds.SEQ_BEAT)
        host.borkSpot(BorkIds.SPOT_BEAT)
        val area = beatArea(sw, radius)
        val areaSet = area.toHashSet()
        later(cfg.bork.beatHitDelay, Tag.BORK) {
            if (cfg.wave.dust) dust(sw, area, long = large)
            for (f in host.fighters()) {
                if (f.alive && f.tile.flat() in areaSet) host.borkHit(f, BorkHit.BEAT, cfg.bork.beatMax, cfg.bork.accuracy.beat)
            }
        }
    }

    /**
     * A heavy cannonball landed on [centre]: dazes Bork if his 4x4 is in the square, otherwise (while he chases) he
     * starts a stomp or gong sequence. Returns true if he was dazed.
     */
    fun heavyLanded(centre: StaticTile): Boolean {
        if (borkState != BorkState.CHASE) return false
        val sw = host.borkSw ?: return false
        if (BorkArena.squareTouchesFootprint(centre, cfg.smorks.impactArea, sw, BorkArena.BORK_SIZE)) {
            daze()
            return true
        }
        if (random.nextDouble() < cfg.bork.gongChance) startGong(fromDaze = false) else startStomp(fromDaze = false, cause = Cause.MISSED_HEAVY)
        return false
    }

    /** Daze for 2 × his current tempo (spec §4.3). */
    fun daze() {
        if (borkDead) return
        dazes++
        timeline.cancel(Tag.BORK)
        val ticks = Tempo.dazeTicks(borkSpeed(), cfg.bork.dazeTicksPerSpeed)
        borkState = BorkState.DAZED
        host.borkHalt()
        host.borkFace(null)
        host.borkDazed(true)
        val (seq, spot) = dazeVisual(ticks)
        host.borkAnimate(seq)
        host.borkSpot(spot)
        host.borkSpotAtCentre(BorkIds.SPOT_STUN_STARS)
        later(ticks, Tag.BORK) { endDaze() }
    }

    private fun endDaze() {
        host.borkDazed(false)
        if (borkDead) return
        if (cfg.bork.gongAfterDaze && random.nextDouble() < cfg.bork.gongChance) startGong(fromDaze = true)
        else startStomp(fromDaze = true, cause = Cause.DAZE)
    }

    /** The daze seq + spot whose length is closest to [ticks] (8 / 12 / 14 t in the cache). */
    internal fun dazeVisual(ticks: Int): Pair<Int, Int> {
        val options = listOf(
            Triple(8, BorkIds.SEQ_DAZE_8, BorkIds.SPOT_DAZE_8),
            Triple(12, BorkIds.SEQ_DAZE_12, BorkIds.SPOT_DAZE_12),
            Triple(14, BorkIds.SEQ_DAZE_14, BorkIds.SPOT_DAZE_14),
        )
        val best = options.minBy { Math.abs(it.first - ticks) }
        return best.second to best.third
    }

    /** The 4-stomp sequence (spec §4.3). [fromDaze]: entry 14549 (2 t) instead of 14556 (1 t). */
    fun startStomp(fromDaze: Boolean, cause: Cause) {
        if (borkDead) return
        timeline.cancel(Tag.BORK)
        if (isDazed) host.borkDazed(false)
        borkState = BorkState.STOMP
        host.borkHalt()
        val b = cfg.bork
        val entry = if (fromDaze) b.stompInFromDazeTicks else b.stompInFromIdleTicks
        host.borkAnimate(if (fromDaze) BorkIds.SEQ_STOMP_IN_FROM_DAZE else BorkIds.SEQ_STOMP_IN_FROM_IDLE)
        host.borkSay(
            when (cause) {
                Cause.DAZE -> b.texts.veryAngry
                Cause.MISSED_HEAVY -> b.texts.stompTime
                Cause.ENRAGE -> b.texts.enragedStomp
            },
        )
        val pairs = (b.stomps + 1) / 2
        for (i in 0 until pairs) {
            val seq = if (i == pairs - 1) BorkIds.SEQ_STOMP_CYCLE_OUT else BorkIds.SEQ_STOMP
            later(entry + i * b.stompLoopTicks, Tag.BORK) {
                host.borkAnimate(seq)
                host.borkSpot(BorkIds.SPOT_STOMP)
            }
        }
        val shift = entry - b.stompInFromDazeTicks
        for (tick in b.stompTicks) later(max(1, tick + shift), Tag.BORK) { arenaHit(BorkHit.STOMP) }
        later(entry + (pairs - 1) * b.stompLoopTicks + b.stompOutTicks, Tag.BORK) { endSequence() }
    }

    /** The gong barrage ("Bork crush now."): in, 8 beats one per tick, out. */
    fun startGong(fromDaze: Boolean) {
        if (borkDead) return
        timeline.cancel(Tag.BORK)
        if (isDazed) host.borkDazed(false)
        borkState = BorkState.GONG
        host.borkHalt()
        val b = cfg.bork
        host.borkAnimate(if (fromDaze) BorkIds.SEQ_GONG_IN_FROM_DAZE else BorkIds.SEQ_GONG_IN_FROM_IDLE)
        host.borkSay(b.texts.crushNow)
        later(b.gongInTicks, Tag.BORK) { host.borkAnimate(BorkIds.SEQ_GONG) }
        for (i in 0 until b.gongHits) later(b.gongInTicks + 1 + i, Tag.BORK) { arenaHit(BorkHit.GONG) }
        later(b.gongInTicks + b.gongHits, Tag.BORK) { host.borkAnimate(BorkIds.SEQ_GONG_OUT) }
        later(b.gongInTicks + b.gongHits + b.gongOutTicks, Tag.BORK) { endSequence() }
    }

    /** One stomp or gong beat: every player in the arena except those in the cart's shadow (none once the Smorks die). */
    private fun arenaHit(kind: BorkHit) {
        val sw = host.borkSw ?: return
        if (kind == BorkHit.STOMP) stomps++ else gongHits++
        val useShadow = !smorksDead
        if (cfg.wave.dust) {
            val tiles = if (useShadow) BorkArena.WALKABLE.filterNot { shadow.isShadowed(sw, BorkArena.BORK_SIZE, it) } else BorkArena.WALKABLE
            dust(sw, tiles, long = kind == BorkHit.STOMP)
        }
        val max = if (kind == BorkHit.STOMP) cfg.bork.stompMax else cfg.bork.gongMax
        val roll = if (kind == BorkHit.STOMP) cfg.bork.accuracy.stomp else cfg.bork.accuracy.gong
        for (f in host.fighters()) {
            if (!f.alive) continue
            if (useShadow && shadow.isShadowed(sw, BorkArena.BORK_SIZE, f.tile.flat())) continue
            host.borkHit(f, kind, max, roll)
        }
    }

    /** The end of a stomp or gong sequence: a new target ("Bork smash <name> now."), then the chase resumes. */
    private fun endSequence() {
        if (borkDead) return
        borkState = BorkState.CHASE
        lastBeatLarge = false
        retarget(announce = true)
        borkClock = borkSpeed()
    }

    private fun dust(sw: StaticTile, tiles: Collection<StaticTile>, long: Boolean) {
        for (d in WaveDust.plan(sw, BorkArena.BORK_SIZE, tiles, long, cfg.wave)) host.graphic(d.spot, d.tile, d.delayCycles)
    }

    /** Bork's HP reached 0. */
    fun onBorkDeath() {
        if (borkDead) return
        timeline.cancel(Tag.BORK)
        val wasDazed = isDazed
        borkState = BorkState.DYING
        if (wasDazed) host.borkDazed(false)
        host.borkHalt()
        host.borkFace(null)
        host.borkTargetable(false)
        host.borkAnimate(BorkIds.SEQ_DEATH)
        host.borkSpot(BorkIds.SPOT_DEATH)
        target = null
        host.primaryTarget(null)
        later(cfg.bork.deathDespawnTicks, Tag.DEATH) {
            borkState = BorkState.DEAD
            host.borkDespawn()
        }
        // The Smorks enrage at once: their next attack comes at the enraged speed at the latest.
        if (!smorksDead) smorksClock = min(smorksClock, cfg.smorks.enragedSpeed)
    }

    // ================================================================================================ Smorks

    private fun tickSmorks() {
        if (--smorksClock > 0) return
        val targets = smorksTargets()
        if (targets.isEmpty()) {
            smorksClock = 1
            return
        }
        val n = smorksAttacks++
        if ((n + 1) % cfg.smorks.specialEvery == 0) special(targets) else volley(targets)
        smorksClock = smorksSpeed()
    }

    /** Every player not targeted by Bork; Bork's target too when nobody else is alive; everyone once Bork is dead. */
    fun smorksTargets(): List<Fighter> {
        val alive = host.fighters().filter { it.alive }
        if (alive.isEmpty() || borkDead) return alive
        val tank = target?.takeIf { it.alive }
        return alive.filter { it !== tank }.ifEmpty { alive }
    }

    private fun smorksMax(base: Int): Int = if (smorksEnraged) (base * cfg.smorks.enragedDmgMult).roundToInt() else base

    private fun volley(targets: List<Fighter>) {
        volleys++
        val style = if (random.nextBoolean()) VolleyStyle.RANGED else VolleyStyle.MAGIC
        host.smorksAnimate(BorkIds.SEQ_VOLLEY)
        host.smorksSpot(BorkIds.SPOT_VOLLEY)
        host.smorksFace(targets.first())
        val max = smorksMax(cfg.smorks.autoMax)
        val spot = if (style == VolleyStyle.RANGED) BorkIds.SPOT_BONE_PROJ else BorkIds.SPOT_POTION_PROJ
        val spec = if (style == VolleyStyle.RANGED) cfg.projectiles.bone else cfg.projectiles.potion
        for (t in targets) {
            val flight = host.smorksProjectile(spot, spec, t)
            later(max(1, flight), Tag.FLIGHT) { if (t.alive) host.smorksVolleyHit(t, style, max) }
        }
    }

    private fun special(targets: List<Fighter>) {
        val kind = if (smorksEnraged || borkDead) Special.INCENDIARY else nextSpecial.also {
            nextSpecial = if (it == Special.INCENDIARY) Special.HEAVY else Special.INCENDIARY
        }
        fireSpecial(kind, targets[random.nextInt(targets.size)])
    }

    /** Fires a cannonball special at [victim]'s tile now (also `::tfa skipto incendiary|heavy`). */
    fun fireSpecial(kind: Special, victim: Fighter) {
        val centre = victim.tile.flat()
        host.smorksAnimate(BorkIds.SEQ_BIG_CANNON)
        host.smorksSpot(BorkIds.SPOT_BIG_CANNON)
        host.smorksSpotAtCentre(BorkIds.SPOT_BIG_CANNON_FLASH)
        host.smorksFace(victim)
        when (kind) {
            Special.INCENDIARY -> {
                incendiaries++
                host.smorksSay(cfg.smorks.texts.incendiary)
                val flight = cfg.smorks.incendiaryFlight
                host.smorksLob(BorkIds.SPOT_INCENDIARY_PROJ, cfg.projectiles.incendiary, centre, flight)
                host.graphic(BorkIds.SPOT_INCENDIARY_MARKER, centre, max(0, flight * CYCLES_PER_TICK - INCENDIARY_MARKER_CYCLES))
                later(flight, Tag.FLIGHT) { incendiaryImpact(centre) }
            }
            Special.HEAVY -> {
                heavies++
                host.smorksSay(cfg.smorks.texts.heavy)
                host.graphic(BorkIds.SPOT_HEAVY_SHADOW, centre)
                host.smorksLob(BorkIds.SPOT_HEAVY_PROJ, cfg.projectiles.heavy, centre, cfg.smorks.heavyFlight)
                later(cfg.smorks.heavyFlight, Tag.FLIGHT) { heavyImpact(centre) }
            }
        }
    }

    private fun incendiaryImpact(centre: StaticTile) {
        val s = cfg.smorks
        host.graphic(BorkIds.SPOT_INCENDIARY_IMPACT, centre)
        for (f in host.fighters()) if (f.alive && BorkArena.inSquare(f.tile, centre, s.impactArea)) host.damage(f, s.incendiaryDmg)
        if (!borkDead) {
            val sw = host.borkSw
            if (sw != null && BorkArena.squareTouchesFootprint(centre, s.impactArea, sw, BorkArena.BORK_SIZE)) {
                host.borkDamage((host.borkMaxHp * s.incendiaryBorkPct / 100.0).roundToInt())
            }
        }
        val candidates = BorkArena.walkableSquare(centre, s.oilArea).toMutableList()
        java.util.Collections.shuffle(candidates, random)
        for (t in candidates.take(s.oilTiles)) oil.add(t, timeline.now, random.nextInt(4))
    }

    private fun heavyImpact(centre: StaticTile) {
        val s = cfg.smorks
        for (f in host.fighters()) if (f.alive && BorkArena.inSquare(f.tile, centre, s.impactArea)) host.damage(f, s.heavyDmg)
        heavyLanded(centre)
    }

    /** The Smorks' HP reached 0. */
    fun onSmorksDeath() {
        if (smorksDead) return
        timeline.cancel(Tag.SMORKS)
        smorksState = SmorksState.DYING
        host.smorksTargetable(false)
        host.smorksFace(null)
        for (spot in BorkIds.SPOT_SMORKS_DEATH) host.smorksSpotAtCentre(spot)
        enragedBeats = 0
        later(cfg.smorks.deathDespawnTicks, Tag.DEATH) {
            smorksState = SmorksState.DEAD
            host.smorksDespawn()
        }
    }

    // ================================================================================================= misc

    /** Runs [action] [delay] ticks after this one (0 = now). */
    private fun later(delay: Int, tag: Tag, action: () -> Unit) {
        if (delay <= 0) action() else timeline.after(delay, tag, action)
    }

    private fun StaticTile.flat(): StaticTile = if (plane == 0) this else StaticTile(x, y, 0)

    /** `::tfa skipto` support: back to the plain fight (both chasing / attacking), clocks reset. */
    fun debugResume() {
        timeline.cancel(Tag.BORK)
        timeline.cancel(Tag.SMORKS)
        if (isDazed) host.borkDazed(false)
        if (!borkDead) borkState = BorkState.CHASE
        if (!smorksDead) smorksState = SmorksState.ACTIVE
        lastBeatLarge = false
        enragedBeats = 0
        borkClock = cfg.bork.firstAttackDelay
        smorksClock = cfg.smorks.firstAttackDelay
    }

    /** Lines for `::tfa dump`. */
    fun describe(): List<String> = listOf(
        "  Bork: $borkState, hp ${host.borkHp}/${host.borkMaxHp}, speed ${borkSpeed()}, next beat in $borkClock t, " +
            "armour ${borkFlatArmour()}/${borkMagicResist()}, target ${target?.name ?: "none"}, enraged $borkEnraged",
        "  Smorks: $smorksState, hp ${host.smorksHp}/${host.smorksMaxHp}, speed ${smorksSpeed()}, next attack in $smorksClock t, " +
            "attack #${smorksAttacks + 1}, next special $nextSpecial, enraged $smorksEnraged",
        "  counts: beats $beats (large $largeBeats), dazes $dazes, stomps $stomps, gong $gongHits, volleys $volleys, " +
            "incendiary $incendiaries, heavy $heavies, retargets $retargets, oil ${oil.size}",
    )

    private companion object {
        const val CYCLES_PER_TICK = 30

        /** CACHE spotanim 4443's seq 9470 is 56 cycles: the marker is sent so that it ends as the ball lands. */
        const val INCENDIARY_MARKER_CYCLES = 56
    }
}
