package com.gadscape.raids.tfa.rooms.ezekiel

import java.util.Random
import kotlin.math.roundToInt

/** States of Ezekiel (spec §4.2 state machine). */
enum class EzekielState(val debugName: String) {
    WAITING("waiting"),
    CYCLE("cycle"),
    STUNNED("stunned"),
    FINALE_TRANSITION("finale_transition"),
    FINALE("finale"),
    DYING("dying"),
    DEAD("dead");
}

/** What a launched sword did, as the host reports it back to the fight. */
data class SwordOutcome(
    val hitBoss: Boolean,
    /** Empower fraction 0..1 at the launch. */
    val empower: Double,
    /** Ticks after the launch when it reaches Ezekiel (with [hitBoss]). */
    val impactTicks: Int,
    /** Ticks after the launch when the sword is gone. */
    val despawnTicks: Int,
) {
    companion object {
        /** A sword that no longer exists (e.g. removed by debug): a plain miss that ends at once. */
        @JvmField
        val GONE = SwordOutcome(hitBoss = false, empower = 0.0, impactTicks = 1, despawnTicks = 1)
    }
}

/**
 * The side effects of the fight, implemented by [EzekielRoom] against the world and by a recorder in the tests. The
 * machine decides *when* and *what*; the host decides *how* (targets, tiles, hits, visuals).
 */
interface EzekielHost {
    // ---- queries ----------------------------------------------------------------------------------------------------
    /** The tank is alive in the arena and within his melee reach. */
    fun tankInReach(): Boolean

    fun bossHp(): Int

    /** Sets his HP (debug only; 0 runs the death through the npc). */
    fun setBossHp(hp: Int)

    // ---- cycle ------------------------------------------------------------------------------------------------------
    /** One step toward the tank when it is out of reach (between attacks; not while rooted). */
    fun follow()

    /** Seq 14685 at the tank; remembers the swing direction. */
    fun playCleave()

    /** The cleave lands on the ring (tank: rolled, Protect from Melee blocks; others: unprayable, bleed). */
    fun resolveCleave()

    /** Seq 14686 + spot 4167 on every player in the arena. */
    fun playLightning()

    /** Spot 4168 + a magic hit on every player the lightning was cast at. */
    fun resolveLightning()

    /** Seq 14687 + spot 4169 under him; returns the cross tiles, fixed now. */
    fun playSpecial(): Set<Tile>

    /** Spot 4170 on every cross tile (t0). */
    fun starTelegraph(tiles: Set<Tile>)

    /** Spot 4171 on every cross tile. */
    fun starMarker(tiles: Set<Tile>)

    /** Spot 4172 on every cross tile (it shows both bursts). */
    fun starExplosion(tiles: Set<Tile>)

    /** Burst [index] (0-based): typeless damage on players on the cross tiles. */
    fun starBurst(tiles: Set<Tile>, index: Int)

    /** Spawns one Animated Sword of volley [volley] with its launch (88) and empower (85) headbars. */
    fun spawnSword(volley: Int, sword: SwordSpec)

    /** Launches it: flight, dodges and player hits; reports what it does to Ezekiel. */
    fun launchSword(volley: Int, sword: SwordSpec): SwordOutcome

    /** The sword's flight is over: despawn it. */
    fun swordGone(volley: Int, sword: SwordSpec)

    /** A sword reaches Ezekiel: typeless [damage] on him (no player source). */
    fun damageBoss(damage: Int)

    /** Force-talk "Argh!" (wiki: "A successful block should result in him yelling 'Argh!'"). */
    fun argh()

    fun playStunStart()
    fun playStunLoop()
    fun playStunEnd()

    /** Seq 14688 + spots 4173 (on him) / 4174 (arena-wide). */
    fun playHeal()

    /** Heals him [amount] (green splat). */
    fun healBoss(amount: Int)

    /** Removes every Animated Sword (finale start, death, wipe). */
    fun clearSwords()

    // ---- finale -----------------------------------------------------------------------------------------------------
    /** Teleport to the finale tile facing west, seq 14694 + 4177, fences drop with collision, players into the pen. */
    fun beginFinale()

    /** The three finale swords drop (left, right, front). */
    fun finaleSwordsDrop()

    /**
     * Per-tick finale upkeep from the end of the imbue: Ezekiel's loop seq 14696 with the glow 4178 between volleys or
     * the cast loop 4176 during one (unless the 3-t cast 4175 plays), fence and finale-sword idle holds.
     */
    fun finaleUpkeep(volleyActive: Boolean, castPlaying: Boolean)

    /** The two struck swords' telegraph + Ezekiel's cast 4175. */
    fun finaleTelegraph(attack: FinaleAttack)

    /** Impacts 4196-4198 on the struck pen tiles and the magic hits. */
    fun finaleStrike(attack: FinaleAttack)

    // ---- death ------------------------------------------------------------------------------------------------------
    /** Seq 14697 + 4179; finale swords despawn. */
    fun playDeath()

    /** The death sword 16656 at his tile; he, the fences and their collision go. */
    fun finishDeath()

    /** Debug and log line. */
    fun log(message: String) {}
}

/**
 * Ezekiel's state machine and clocks (spec §4.2), driven by [tick] once per server tick and by HP notifications from
 * the npc ([onBossHpChanged], [onBossDeath]). It owns no world state; everything visible goes through [host], and every
 * delayed action is an entry in [timeline] (spec §2.5), so the finale, the death, a wipe or a teardown cancels it.
 *
 * - **CYCLE**: attacks on the `attackSpeed` clock (first after `firstAttackDelay`): slots 1 and 2 are a cleave when the
 *   tank is in reach, else lightning on all; slot 3 is the special (cross + swords). Between attacks he follows the
 *   tank (not while the special or the heal roots him).
 * - **Special** (seq 14687, 5 t): cross telegraph t0, marker `starMarkerTick`, bursts `starBurstTicks` (t3, t5),
 *   swords at `swordSpawnDelay` (t1), each launching after its charge.
 * - **Swords**: a sword reaching him deals `swordBossPct`% × max × (1 + e) and stuns him (`stunTicks`, clock paused,
 *   "Argh!"); when a volley's last sword is gone, `healRule` decides the heal (`healPct`% of max, seq 14688).
 * - **Finale** at `enragePct` (33.3%): FINALE_TRANSITION (`transitionTicks`) then volleys of the rotation's combos.
 * - **DYING** (14697, 6 t) then DEAD (`isComplete`).
 */
class EzekielFight(
    private val cfg: EzekielConfig,
    /** Scaled max HP for this raid's party scale. */
    val maxHp: Int,
    private val host: EzekielHost,
    private val random: Random,
    val timeline: EzekielTimeline = EzekielTimeline(),
) {

    var state: EzekielState = EzekielState.WAITING
        private set

    /** The tick the current state began. */
    var stateSince: Int = 0
        private set

    val now: Int get() = timeline.now

    /** Slot of the next attack in the cycle: 0 and 1 melee / lightning, 2 the special. */
    var attackSlot: Int = 0
        private set

    /** Tick of the next attack on the clock (shifted while stunned or healing). */
    var nextAttackAt: Int = 0
        private set

    /** He does not move before this tick (special, heal, stun). */
    var rootedUntil: Int = 0
        private set

    /** A priority animation (special, heal) plays until this tick; a stun waits for it. */
    var animLockUntil: Int = 0
        private set

    /** End of the current stun. */
    var stunEnd: Int = 0
        private set

    /** Attacks made in the cycle (for `::tfa dump`). */
    var attacks: Int = 0
        private set

    /** Counters for tests and dumps. */
    var stuns: Int = 0
        private set
    var heals: Int = 0
        private set

    /** The finale threshold was crossed; the transition starts on the next tick. */
    var finalePending: Boolean = false
        private set

    private var nextVolleyId = 1

    /** A live sword volley: its swords, how many are still out, how many hit him. */
    class Volley(val id: Int, val cast: SwordCast, val swords: List<SwordSpec>) {
        var pending: Int = swords.size
        var hits: Int = 0
    }

    private val volleys = LinkedHashMap<Int, Volley>()

    /** Live volleys (for dumps and tests). */
    val activeVolleys: Collection<Volley> get() = volleys.values

    // ---- finale state -------------------------------------------------------------------------------------------
    var finaleSchedule: Finale.Schedule? = null
        private set
    private var volleyActiveUntil = 0
    private var castUntil = 0
    private var upkeepFrom = Int.MAX_VALUE

    val isComplete: Boolean get() = state == EzekielState.DEAD
    val isActive: Boolean get() = state != EzekielState.WAITING && state != EzekielState.DEAD

    // ================================================================================================ lifecycle

    /** The fight begins (first transporter use). */
    fun start() {
        if (state != EzekielState.WAITING) return
        enter(EzekielState.CYCLE)
        attackSlot = 0
        nextAttackAt = now + cfg.firstAttackDelay
    }

    /** One server tick. */
    fun tick() {
        timeline.advance()
        if (finalePending && (state == EzekielState.CYCLE || state == EzekielState.STUNNED)) {
            finalePending = false
            beginFinale()
        }
        when (state) {
            EzekielState.CYCLE -> cycleTick()
            EzekielState.FINALE_TRANSITION, EzekielState.FINALE -> if (now >= upkeepFrom) {
                host.finaleUpkeep(volleyActive = now < volleyActiveUntil, castPlaying = now < castUntil)
            }
            else -> {}
        }
    }

    /** Stops everything (wipe, teardown). */
    fun stop() {
        timeline.cancelAll()
        volleys.clear()
    }

    // ================================================================================================ notifications

    /** A processed hit changed his HP from [before] to [after] (damage, heal, sword). */
    fun onBossHpChanged(before: Int, after: Int) {
        if (after <= 0) return // onBossDeath handles it
        if (state != EzekielState.CYCLE && state != EzekielState.STUNNED) return
        val mark = maxHp * cfg.enragePct / 100.0
        if (before > mark && after <= mark) {
            finalePending = true
            host.log("finale threshold crossed ($before -> $after of $maxHp)")
        }
    }

    /** His HP reached 0 (the npc's death hook). */
    fun onBossDeath() {
        if (state == EzekielState.DYING || state == EzekielState.DEAD || state == EzekielState.WAITING) return
        finalePending = false
        timeline.cancelAll()
        volleys.clear()
        enter(EzekielState.DYING)
        host.clearSwords()
        host.playDeath()
        timeline.schedule(EzekielIds.DEATH_TICKS, TAG_DEATH) {
            host.finishDeath()
            enter(EzekielState.DEAD)
        }
    }

    // ================================================================================================ cycle

    private fun cycleTick() {
        if (now >= nextAttackAt) {
            attack()
            return
        }
        if (now >= rootedUntil) host.follow()
    }

    private fun attack() {
        val slot = attackSlot
        attackSlot = (attackSlot + 1) % 3
        nextAttackAt = now + cfg.attackSpeed
        attacks++
        when {
            slot == 2 -> special()
            host.tankInReach() -> cleave()
            else -> lightning()
        }
    }

    private fun cleave() {
        host.playCleave()
        timeline.schedule(cfg.cleaveHitDelay, TAG_ATTACK) { host.resolveCleave() }
    }

    private fun lightning() {
        host.playLightning()
        timeline.schedule(cfg.lightningHitDelay, TAG_ATTACK) { host.resolveLightning() }
    }

    private fun special() {
        val tiles = host.playSpecial()
        animLockUntil = maxOf(animLockUntil, now + EzekielIds.SPECIAL_TICKS)
        if (cfg.specialRoots) rootedUntil = maxOf(rootedUntil, now + EzekielIds.SPECIAL_TICKS)
        host.starTelegraph(tiles)
        timeline.schedule(cfg.starMarkerTick, TAG_STAR) { host.starMarker(tiles) }
        cfg.starBurstTicks.forEachIndexed { i, t ->
            timeline.schedule(t, TAG_STAR) {
                if (i == 0) host.starExplosion(tiles)
                host.starBurst(tiles, i)
            }
        }
        timeline.schedule(cfg.swordSpawnDelay, TAG_SWORD) { spawnVolley(SwordPatterns.pick(cfg.swordCasts, random)) }
    }

    /** Spawns a volley of [cast] now (also `::tfa skipto swords`). */
    fun spawnVolley(cast: SwordCast) {
        if (state != EzekielState.CYCLE && state != EzekielState.STUNNED) return
        val specs = SwordPatterns.plan(cast, random, cfg.swordChargeTicks, cfg.swordFullStagger)
        val v = Volley(nextVolleyId++, cast, specs)
        volleys[v.id] = v
        host.log("volley ${v.id}: ${cast.key} (${specs.size} swords)")
        for (s in specs) {
            host.spawnSword(v.id, s)
            timeline.schedule(s.chargeTicks, TAG_SWORD) { launch(v, s) }
        }
    }

    private fun launch(v: Volley, s: SwordSpec) {
        val outcome = host.launchSword(v.id, s)
        if (outcome.hitBoss) {
            v.hits++
            timeline.schedule(outcome.impactTicks.coerceAtLeast(0), TAG_SWORD) { onSwordImpact(outcome.empower) }
        }
        timeline.schedule(outcome.despawnTicks.coerceAtLeast(0), TAG_SWORD) {
            host.swordGone(v.id, s)
            v.pending--
            if (v.pending <= 0) volleyDone(v)
        }
    }

    private fun onSwordImpact(empower: Double) {
        if (state != EzekielState.CYCLE && state != EzekielState.STUNNED) return
        host.damageBoss(SwordFlight.bossDamage(maxHp, cfg.swordBossPct, empower))
        if (state == EzekielState.CYCLE || state == EzekielState.STUNNED) stun()
    }

    private fun volleyDone(v: Volley) {
        volleys.remove(v.id)
        val healCount = when (cfg.healRule) {
            EzekielConfig.HealRule.NO_SWORD_HIT -> if (v.hits == 0) 1 else 0
            EzekielConfig.HealRule.PER_MISSING_SWORD -> (v.swords.size - v.hits).coerceAtLeast(0)
        }
        host.log("volley ${v.id} done: ${v.hits}/${v.swords.size} hit him -> ${if (healCount > 0) "heal x$healCount" else "no heal"}")
        if (healCount > 0) whenFree { heal(healCount * healAmount()) }
    }

    /** One heal: `healPct`% of the scaled max (150 at 2-scale, 406 at 6-scale). */
    fun healAmount(): Int = (maxHp * cfg.healPct / 100.0).roundToInt()

    // ================================================================================================ stun and heal

    /** Runs [action] now if he is free (in CYCLE, no special/heal playing), else when he is. */
    private fun whenFree(action: () -> Unit) {
        when (state) {
            EzekielState.CYCLE -> if (now >= animLockUntil) action() else timeline.schedule(animLockUntil - now, TAG_FREE) { whenFree(action) }
            EzekielState.STUNNED -> timeline.schedule((stunEnd - now).coerceAtLeast(1), TAG_FREE) { whenFree(action) }
            else -> {}
        }
    }

    /** A sword hit him: stunned for `stunTicks` with the attack clock paused (after a special or heal finishes). */
    fun stun() {
        when (state) {
            EzekielState.STUNNED -> {
                // A second hit while stunned: the stun runs `stunTicks` from this hit.
                val newEnd = now + cfg.stunTicks
                if (newEnd > stunEnd) {
                    nextAttackAt += newEnd - stunEnd
                    rootedUntil = maxOf(rootedUntil, newEnd)
                    stunEnd = newEnd
                    timeline.cancel(TAG_STUN)
                    scheduleStunEnd()
                }
            }
            EzekielState.CYCLE -> {
                if (now < animLockUntil) {
                    if (!timeline.hasPending(TAG_STUN_DEFERRED)) timeline.schedule(animLockUntil - now, TAG_STUN_DEFERRED) { stun() }
                    return
                }
                enter(EzekielState.STUNNED)
                stuns++
                stunEnd = now + cfg.stunTicks
                nextAttackAt = maxOf(nextAttackAt, now) + cfg.stunTicks
                rootedUntil = maxOf(rootedUntil, stunEnd)
                host.argh()
                host.playStunStart()
                timeline.cancel(TAG_STUN)
                timeline.schedule(EzekielIds.STUN_IN_TICKS, TAG_STUN) { if (state == EzekielState.STUNNED) host.playStunLoop() }
                scheduleStunEnd()
            }
            else -> {}
        }
    }

    private fun scheduleStunEnd() {
        val outAt = stunEnd - EzekielIds.STUN_OUT_TICKS
        if (outAt > now) timeline.schedule(outAt - now, TAG_STUN) { if (state == EzekielState.STUNNED) host.playStunEnd() }
        timeline.schedule((stunEnd - now).coerceAtLeast(1), TAG_STUN) {
            if (state == EzekielState.STUNNED && now >= stunEnd) enter(EzekielState.CYCLE)
        }
    }

    /** Heals him [amount] with seq 14688 (the heal roots him and, by default, pauses the clock for its 3 t). */
    fun heal(amount: Int) {
        if (state != EzekielState.CYCLE || amount <= 0) return
        heals++
        host.playHeal()
        animLockUntil = maxOf(animLockUntil, now + EzekielIds.HEAL_TICKS)
        rootedUntil = maxOf(rootedUntil, now + EzekielIds.HEAL_TICKS)
        if (cfg.healPausesClock) nextAttackAt = maxOf(nextAttackAt, now) + EzekielIds.HEAL_TICKS
        timeline.schedule(cfg.healDelay, TAG_HEAL) {
            if (state == EzekielState.CYCLE || state == EzekielState.STUNNED) host.healBoss(amount)
        }
    }

    // ================================================================================================ finale

    private fun beginFinale() {
        timeline.cancel(TAG_ATTACK, TAG_STAR, TAG_SWORD, TAG_STUN, TAG_STUN_DEFERRED, TAG_HEAL, TAG_FREE)
        volleys.clear()
        enter(EzekielState.FINALE_TRANSITION)
        host.clearSwords()
        host.beginFinale()
        val f = cfg.finale
        finaleSchedule = Finale.Schedule(f.combos, f.rotations.values.toList(), random)
        upkeepFrom = now + EzekielIds.FINALE_IMBUE_TICKS
        timeline.schedule(f.swordDropDelay, TAG_FINALE) { host.finaleSwordsDrop() }
        timeline.schedule(f.transitionTicks, TAG_FINALE) {
            enter(EzekielState.FINALE)
            volley()
        }
    }

    /** One finale volley: the next combo of the rotation, `nextAttack` apart, then `volleyPause`, then the next one. */
    private fun volley() {
        if (state != EzekielState.FINALE) return
        val f = cfg.finale
        val attacks = finaleSchedule?.next() ?: return
        val count = attacks.size.coerceAtMost(f.perVolley)
        for (k in 0 until count) {
            val attack = attacks[k]
            timeline.schedule(k * f.nextAttack, TAG_FINALE) {
                castUntil = now + EzekielIds.FINALE_CAST_TICKS
                host.finaleTelegraph(attack)
            }
            timeline.schedule(k * f.nextAttack + f.hitDelay, TAG_FINALE) { host.finaleStrike(attack) }
        }
        val length = count * f.nextAttack
        volleyActiveUntil = now + length
        timeline.schedule(length + f.volleyPause, TAG_FINALE) { volley() }
    }

    // ================================================================================================ debug

    /** Phase names for `::tfa skipto <phase>`. */
    fun debugSkipTo(phase: String): Boolean {
        when (phase.lowercase()) {
            "cycle" -> {
                if (state == EzekielState.WAITING) start()
                return state == EzekielState.CYCLE || state == EzekielState.STUNNED
            }
            "special", "star", "cross" -> {
                if (state == EzekielState.WAITING) start()
                if (state != EzekielState.CYCLE) return false
                attackSlot = 2
                nextAttackAt = now + 1
                return true
            }
            "swords", "sword" -> {
                if (state == EzekielState.WAITING) start()
                if (state != EzekielState.CYCLE && state != EzekielState.STUNNED) return false
                spawnVolley(SwordPatterns.pick(cfg.swordCasts, random))
                return true
            }
            "stun", "stunned" -> {
                if (state == EzekielState.WAITING) start()
                if (state != EzekielState.CYCLE && state != EzekielState.STUNNED) return false
                animLockUntil = now
                stun()
                return true
            }
            "heal" -> {
                if (state == EzekielState.WAITING) start()
                if (state != EzekielState.CYCLE) return false
                animLockUntil = now
                heal(healAmount())
                return true
            }
            "finale", "enrage" -> {
                if (state == EzekielState.WAITING) start()
                if (state != EzekielState.CYCLE && state != EzekielState.STUNNED) return false
                val mark = (maxHp * cfg.enragePct / 100.0).toInt()
                if (host.bossHp() > mark) host.setBossHp(mark.coerceAtLeast(1))
                finalePending = true
                return true
            }
            "dying", "death", "kill" -> {
                if (state == EzekielState.WAITING) start()
                if (state == EzekielState.DYING || state == EzekielState.DEAD) return false
                host.setBossHp(0)
                if (state != EzekielState.DYING) onBossDeath()
                return true
            }
        }
        return false
    }

    /** `::tfa dump` lines. */
    fun describe(): List<String> {
        val lines = ArrayList<String>()
        lines += "Ezekiel ${state.debugName} since t${stateSince} (now t$now), hp ${host.bossHp()}/$maxHp, finale at ${cfg.enragePct}%" +
            (if (finalePending) " (pending)" else "")
        lines += "attacks $attacks, next slot ${attackSlot + 1}/3 at t$nextAttackAt, rooted until t$rootedUntil, stuns $stuns, heals $heals"
        for (v in volleys.values) lines += "volley ${v.id} ${v.cast.key}: ${v.pending}/${v.swords.size} out, ${v.hits} hit him"
        finaleSchedule?.let { lines += "finale rotation r${it.rotationIndex + 1}, volleys ${it.volleys}" }
        lines += "timeline: ${timeline.size} pending"
        return lines
    }

    private fun enter(next: EzekielState) {
        state = next
        stateSince = now
        host.log("state ${next.debugName}")
    }

    companion object {
        /** `::tfa skipto` phases, in fight order. */
        @JvmField
        val PHASES = listOf("cycle", "special", "swords", "stun", "heal", "finale", "dying")

        const val TAG_ATTACK = "attack"
        const val TAG_STAR = "star"
        const val TAG_SWORD = "sword"
        const val TAG_STUN = "stun"
        const val TAG_STUN_DEFERRED = "stunDeferred"
        const val TAG_HEAL = "heal"
        const val TAG_FREE = "whenFree"
        const val TAG_FINALE = "finale"
        const val TAG_DEATH = "death"
    }
}
