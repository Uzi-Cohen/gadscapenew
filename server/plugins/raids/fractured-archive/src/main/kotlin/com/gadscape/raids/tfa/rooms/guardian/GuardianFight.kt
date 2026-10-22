package com.gadscape.raids.tfa.rooms.guardian

/** States of the Archive Guardian (spec §4.1 state machine). */
enum class GuardianState(val debugName: String) {
    DORMANT("dormant"),
    WAKING("waking"),
    FLYING("flying"),
    LANDING("landing"),
    GROUNDED("grounded"),
    LIFTING("lifting"),
    ENRAGING("enraging"),
    ENRAGED("enraged"),
    DYING("dying"),
    DEAD("dead");
}

/** Which HP pool the boss npc currently carries. */
enum class GuardianPool { NONE, SHIELD, MAIN }

/** What the boss does on one beat of its attack clock. */
enum class GuardianAttack { AUTO, MUSHROOMS, LASER }

/**
 * The side effects of the fight, implemented by [ArchiveGuardianRoom] against the world and by a recorder in the
 * tests. The machine decides *when* and *what*; the host decides *how* (targets, tiles, hits, visuals).
 */
interface GuardianHost {
    /** The Inert Guardian wakes: seq 14755 + spot 4206; music. */
    fun playWake()

    /** Puts the boss npc in its shielded flying form with [shield] / [max] (spawns it the first time). */
    fun showShieldPool(shield: Int, max: Int)

    /** Puts the boss npc in its main-HP form with [hp] / [max]; [enraged] selects the enraged combat profile. */
    fun showMainPool(hp: Int, max: Int, enraged: Boolean)

    /** Current HP of the pool the npc carries. */
    fun poolHp(): Int

    /** Sets the npc's current pool HP (debug, and the enrage safety net). */
    fun setPoolHp(hp: Int)

    fun playLanding()
    fun playGroundIdle()
    fun playLifting()
    fun playEnrage()
    fun playDeath()

    /** The death pose npc 16658. */
    fun showDead()

    /** Flying / enraged per-tick upkeep: aggro facing and following. */
    fun followTarget()

    fun attack(kind: GuardianAttack)

    /** One tendril wave: a vine next to every living player in the arena. */
    fun tendrilWave()

    /** Battery discharge on the shield (a hit on the npc). */
    fun dischargeOnShield(amount: Int)

    /** Battery discharge on main HP in enrage; the hit may not leave less than [floorHp]. */
    fun dischargeOnMain(amount: Int, floorHp: Int)

    /** Removes mushrooms and tendrils (death, wipe). */
    fun clearHazards()

    /** Debug and log line. */
    fun log(message: String) {}
}

/**
 * The Archive Guardian state machine and its clocks (spec §4.1), driven by [tick] once per server tick and by HP
 * notifications from the npc ([onPoolHpChanged]). It owns no world state; everything visible goes through [host].
 *
 * Timings: wake 5 t, land 4 t, lift 3 t, enrage 14 t, death 9 t (the seq lengths, [GuardianAnims]); the attack clock
 * (`attackSpeed`), the tendril clock and the lift rule come from [GuardianConfig].
 */
class GuardianFight(
    private val cfg: GuardianConfig,
    /** Scaled max shield and main HP for this raid's party scale. */
    val shieldMax: Int,
    val mainMax: Int,
    private val host: GuardianHost,
    val timeline: GuardianTimeline = GuardianTimeline(),
) {
    var state: GuardianState = GuardianState.DORMANT
        private set

    /** The tick the current state began. */
    var stateSince: Int = 0
        private set

    var pool: GuardianPool = GuardianPool.NONE
        private set

    /** Main HP while the npc carries the shield (the npc's HP is the truth while it carries main HP). */
    var storedMainHp: Int = mainMax
        private set

    /** Battery damage waiting for the next shield (discharges while waking, landing or grounded). */
    var bankedDischarge: Int = 0
        private set

    /** Attacks made on the flying clock since the fight began (every Nth is a mushroom cast). */
    var flyingAttacks: Int = 0
        private set

    /** Attacks made on the enraged clock. */
    var enragedAttacks: Int = 0
        private set

    /** Main HP lift boundary (% of max) of the current grounded phase, or null. */
    var liftBoundaryPct: Int? = null
        private set

    /** How many times the boss has landed. */
    var landings: Int = 0
        private set

    val isComplete: Boolean get() = state == GuardianState.DEAD

    val now: Int get() = timeline.now

    /** The npc may be attacked (canAttack) in this state. */
    val isTargetable: Boolean
        get() = when (state) {
            GuardianState.FLYING, GuardianState.GROUNDED, GuardianState.LIFTING, GuardianState.ENRAGED -> true
            GuardianState.LANDING -> !cfg.landingUntargetable
            GuardianState.ENRAGING -> !cfg.enragingInvulnerable
            else -> false
        }

    /** Hits landing now are zeroed (landing, enraging, dying). */
    val isInvulnerable: Boolean
        get() = when (state) {
            GuardianState.LANDING -> cfg.landingUntargetable
            GuardianState.ENRAGING -> cfg.enragingInvulnerable
            GuardianState.DORMANT, GuardianState.WAKING, GuardianState.DYING, GuardianState.DEAD -> true
            else -> false
        }

    /** The boss is airborne (flying status: melee immune except halberds) in this state. */
    val isFlying: Boolean
        get() = state == GuardianState.FLYING || state == GuardianState.LIFTING ||
            state == GuardianState.ENRAGING || state == GuardianState.ENRAGED

    // ------------------------------------------------------------------------------------------------- driving

    /** The Touch: DORMANT → WAKING. Returns false if the fight already began. */
    fun start(): Boolean {
        if (state != GuardianState.DORMANT) return false
        enter(GuardianState.WAKING)
        host.playWake()
        timeline.schedule(GuardianAnims.WAKE_TICKS, TAG_PHASE) { enterFlying(first = true) }
        return true
    }

    /** One server tick: clocks and scheduled actions, then per-state upkeep. */
    fun tick() {
        timeline.advance()
        when (state) {
            GuardianState.FLYING, GuardianState.ENRAGED -> host.followTarget()
            else -> {}
        }
    }

    /**
     * The npc's pool HP went from [before] to [after] (a processed hit, a heal, or a debug change). Applies the
     * shield break, the lift and enrage thresholds and the death (spec §2.5: thresholds queue in order; one hit that
     * crosses the lift boundary and 30% enrages).
     */
    fun onPoolHpChanged(before: Int, after: Int) {
        when (state) {
            GuardianState.FLYING, GuardianState.LIFTING -> if (pool == GuardianPool.SHIELD && after <= 0) enterLanding()
            GuardianState.GROUNDED -> {
                if (crossed(before, after, cfg.enragePct.toDouble()) || after <= 0) {
                    if (after <= 0) host.setPoolHp(1)
                    enterEnraging()
                    return
                }
                val boundary = liftBoundaryPct
                if (boundary != null && crossed(before, after, boundary.toDouble())) enterLifting("main HP crossed $boundary%")
            }
            GuardianState.ENRAGED, GuardianState.ENRAGING -> if (after <= 0) enterDying()
            else -> {}
        }
    }

    /** A battery was destroyed ([byLaser]: the enraged laser hit it). */
    fun onBatteryDestroyed(byLaser: Boolean) {
        val amount = GuardianRules.discharge(shieldMax, cfg.battery.shieldPct)
        when (state) {
            GuardianState.FLYING, GuardianState.LIFTING -> host.dischargeOnShield(amount)
            GuardianState.DORMANT, GuardianState.WAKING, GuardianState.LANDING, GuardianState.GROUNDED -> {
                bankedDischarge += amount
                host.log("battery discharge $amount banked for the next shield (now $bankedDischarge)")
            }
            GuardianState.ENRAGING, GuardianState.ENRAGED ->
                host.dischargeOnMain(amount, GuardianRules.killRangeFloor(mainMax, cfg.laser.killRangePct))
            GuardianState.DYING, GuardianState.DEAD -> {}
        }
        if (byLaser) host.log("the laser destroyed a battery")
    }

    /** Stops every clock and action (wipe, teardown). */
    fun stop() {
        timeline.cancelAll()
    }

    // ------------------------------------------------------------------------------------------------- states

    private fun enter(next: GuardianState) {
        host.log("${state.debugName} -> ${next.debugName} at tick ${timeline.now}")
        state = next
        stateSince = timeline.now
    }

    private fun enterFlying(first: Boolean) {
        timeline.cancel(TAG_PHASE)
        enter(GuardianState.FLYING)
        if (first || pool != GuardianPool.SHIELD) {
            pool = GuardianPool.SHIELD
            host.showShieldPool(shieldMax, shieldMax)
        }
        if (bankedDischarge > 0) {
            val banked = bankedDischarge
            bankedDischarge = 0
            host.dischargeOnShield(banked)
        }
        timeline.every(cfg.firstAttackDelay, cfg.attackSpeed, TAG_ATTACK) {
            if (state != GuardianState.FLYING) return@every false
            flyingAttacks++
            host.attack(if (GuardianRules.isMushroomAttack(flyingAttacks, cfg.mushroomEveryNthAttack)) GuardianAttack.MUSHROOMS else GuardianAttack.AUTO)
            true
        }
    }

    private fun enterLanding() {
        timeline.cancel(TAG_PHASE, TAG_ATTACK)
        enter(GuardianState.LANDING)
        landings++
        pool = GuardianPool.MAIN
        host.showMainPool(storedMainHp, mainMax, enraged = false)
        host.playLanding()
        timeline.schedule(GuardianAnims.LAND_TICKS, TAG_PHASE) { enterGrounded() }
    }

    private fun enterGrounded() {
        enter(GuardianState.GROUNDED)
        val hp = host.poolHp()
        liftBoundaryPct = GuardianRules.liftBoundaryPct(hp * 100.0 / mainMax, cfg.lift.mainStepPct, cfg.enragePct)
        timeline.every(0, GuardianAnims.GROUND_IDLE_LOOP, TAG_GROUND_IDLE) {
            if (state != GuardianState.GROUNDED) return@every false
            host.playGroundIdle()
            true
        }
        timeline.every(cfg.tendril.firstDelay, cfg.tendril.interval, TAG_TENDRIL_WAVE) {
            if (state != GuardianState.GROUNDED) return@every false
            host.tendrilWave()
            true
        }
        timeline.schedule(cfg.lift.maxGroundedTicks, TAG_PHASE) { enterLifting("${cfg.lift.maxGroundedTicks} grounded ticks") }
    }

    private fun enterLifting(reason: String) {
        if (state != GuardianState.GROUNDED) return
        timeline.cancel(TAG_PHASE, TAG_GROUND_IDLE, TAG_TENDRIL_WAVE)
        host.log("lifting: $reason")
        storedMainHp = host.poolHp()
        enter(GuardianState.LIFTING)
        pool = GuardianPool.SHIELD
        host.showShieldPool(shieldMax, shieldMax)
        host.playLifting()
        timeline.schedule(GuardianAnims.LIFT_TICKS, TAG_PHASE) { enterFlying(first = false) }
    }

    private fun enterEnraging() {
        timeline.cancel(TAG_PHASE, TAG_ATTACK, TAG_GROUND_IDLE, TAG_TENDRIL_WAVE)
        if (pool != GuardianPool.MAIN) {
            pool = GuardianPool.MAIN
            host.showMainPool(storedMainHp, mainMax, enraged = true)
        } else {
            host.showMainPool(host.poolHp(), mainMax, enraged = true)
        }
        enter(GuardianState.ENRAGING)
        host.playEnrage()
        timeline.schedule(GuardianAnims.ENRAGE_TICKS, TAG_PHASE) { enterEnraged() }
    }

    private fun enterEnraged() {
        enter(GuardianState.ENRAGED)
        val laserFirst = cfg.enragedFirst == GuardianConfig.EnragedFirst.LASER
        timeline.every(cfg.firstAttackDelay, cfg.attackSpeed, TAG_ATTACK) {
            if (state != GuardianState.ENRAGED) return@every false
            val laser = (enragedAttacks % 2 == 0) == laserFirst
            enragedAttacks++
            host.attack(if (laser) GuardianAttack.LASER else GuardianAttack.MUSHROOMS)
            true
        }
        if (host.poolHp() <= 0) enterDying()
    }

    private fun enterDying() {
        if (state == GuardianState.DYING || state == GuardianState.DEAD) return
        timeline.cancelAll()
        enter(GuardianState.DYING)
        host.clearHazards()
        host.playDeath()
        timeline.schedule(GuardianAnims.DEATH_TICKS, TAG_PHASE) {
            enter(GuardianState.DEAD)
            host.showDead()
        }
    }

    private fun crossed(before: Int, after: Int, pct: Double): Boolean {
        val mark = mainMax * pct / 100.0
        return before > mark && after <= mark
    }

    // ------------------------------------------------------------------------------------------------- debug

    /**
     * `::tfa skipto <phase>`: jumps to [phase] from any state. Returns false for an unknown phase.
     * Phases: flying, landing (alias grounded), lifting, enrage, enraged, dying.
     */
    fun debugSkipTo(phase: String): Boolean {
        when (phase.lowercase()) {
            "flying", "shield" -> {
                if (state == GuardianState.DORMANT) {
                    start()
                }
                timeline.cancel(TAG_PHASE, TAG_ATTACK, TAG_GROUND_IDLE, TAG_TENDRIL_WAVE)
                if (pool == GuardianPool.MAIN) storedMainHp = host.poolHp()
                enterFlying(first = true)
            }
            "landing", "grounded", "ground" -> {
                if (state == GuardianState.DORMANT) start()
                if (state == GuardianState.WAKING) enterFlying(first = true)
                if (pool == GuardianPool.MAIN) storedMainHp = host.poolHp()
                enterLanding()
            }
            "lifting", "lift" -> {
                if (state != GuardianState.GROUNDED) {
                    debugSkipTo("grounded")
                    timeline.cancel(TAG_PHASE)
                    enterGrounded()
                }
                enterLifting("debug")
            }
            "enrage", "enraging" -> {
                if (state == GuardianState.DORMANT) start()
                if (state == GuardianState.WAKING) enterFlying(first = true)
                if (pool == GuardianPool.SHIELD) {
                    storedMainHp = (mainMax.toLong() * cfg.enragePct / 100).toInt().coerceAtLeast(1)
                } else {
                    host.setPoolHp((mainMax.toLong() * cfg.enragePct / 100).toInt().coerceAtLeast(1))
                }
                enterEnraging()
            }
            "enraged" -> {
                debugSkipTo("enrage")
                timeline.cancel(TAG_PHASE)
                enterEnraged()
            }
            "dying", "death", "kill" -> {
                if (state == GuardianState.DORMANT) start()
                if (state == GuardianState.WAKING) enterFlying(first = true)
                enterDying()
            }
            else -> return false
        }
        return true
    }

    /** Lines for `::tfa dump`. */
    fun describe(): List<String> = listOf(
        "Guardian state ${state.debugName} since tick $stateSince (now ${timeline.now}), pool $pool, " +
            "shield max $shieldMax, main ${if (pool == GuardianPool.MAIN) host.poolHp() else storedMainHp}/$mainMax",
        "attacks flying $flyingAttacks enraged $enragedAttacks, landings $landings, banked discharge $bankedDischarge, " +
            "lift boundary ${liftBoundaryPct?.let { "$it%" } ?: "none"}, timeline ${timeline.size} pending",
    )

    companion object {
        val PHASES = listOf("flying", "grounded", "lifting", "enrage", "enraged", "dying")

        const val TAG_PHASE = "phase"
        const val TAG_ATTACK = "attack"
        const val TAG_GROUND_IDLE = "groundIdle"
        const val TAG_TENDRIL_WAVE = "tendrilWave"
    }
}
