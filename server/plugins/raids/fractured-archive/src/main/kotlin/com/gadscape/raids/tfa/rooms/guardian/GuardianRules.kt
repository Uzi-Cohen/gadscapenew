package com.gadscape.raids.tfa.rooms.guardian

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Random

/**
 * Pure numbers of the Archive Guardian fight (spec §4.1), free of world state so they are unit-tested.
 */
object GuardianRules {

    // ---- laser --------------------------------------------------------------------------------------------------

    /** Laser total T = [perAlive] × [alive] (T(2) = 90: one player survives at 99 HP; 3+ alive kills unshared). */
    @JvmStatic
    fun laserTotal(alive: Int, perAlive: Int): Int = perAlive * alive.coerceAtLeast(1)

    /**
     * One player's laser damage: T / k (k players on the line, rounded down), × [pfmMult] under Protect from Magic
     * (rounded down).
     */
    @JvmStatic
    fun laserShare(total: Int, playersOnLine: Int, protectFromMagic: Boolean, pfmMult: Double): Int {
        val share = total / playersOnLine.coerceAtLeast(1)
        return if (protectFromMagic) kotlin.math.floor(share * pfmMult + 1e-9).toInt() else share
    }

    // ---- batteries ----------------------------------------------------------------------------------------------

    /** A battery's discharge: round([pct]% × the scaled max shield), half up (596 of 1490 at 6-scale). */
    @JvmStatic
    fun discharge(maxShield: Int, pct: Int): Int =
        BigDecimal.valueOf(maxShield.toLong() * pct).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).toInt()

    /** The lowest main HP battery damage may leave: ceil([killRangePct]% × [maxMain]) ("kill range"). */
    @JvmStatic
    fun killRangeFloor(maxMain: Int, killRangePct: Int): Int = ((maxMain.toLong() * killRangePct + 99) / 100).toInt()

    /** Battery damage to main HP after the kill-range clamp: never below [floor], never negative. */
    @JvmStatic
    fun clampToFloor(hp: Int, damage: Int, floor: Int): Int = damage.coerceAtMost((hp - floor).coerceAtLeast(0)).coerceAtLeast(0)

    // ---- phases -------------------------------------------------------------------------------------------------

    /**
     * The lift boundary (% of max main HP) below [hpPct]: the largest `100 − k × step` (k ≥ 1) under [hpPct] and
     * above [enragePct]; null when the next boundary would be the enrage (only the timer or the enrage end that
     * grounded phase). 100 → 65 with the defaults.
     */
    @JvmStatic
    fun liftBoundaryPct(hpPct: Double, stepPct: Int, enragePct: Int): Int? {
        if (stepPct <= 0) return null
        var b = 100 - stepPct
        while (b >= hpPct - 1e-9) b -= stepPct
        return if (b > enragePct) b else null
    }

    /** Mushrooms per cast: base + perTwoAlive × floor(alive / 2), never above the [room] left under the cap. */
    @JvmStatic
    fun mushroomCount(alive: Int, base: Int, perTwoAlive: Int, room: Int): Int =
        (base + perTwoAlive * (alive.coerceAtLeast(0) / 2)).coerceAtMost(room.coerceAtLeast(0))

    /** The 1-based attack on the flying clock is a mushroom cast when it is a multiple of [everyNth] (0 = never). */
    @JvmStatic
    fun isMushroomAttack(attackIndex: Int, everyNth: Int): Boolean = everyNth > 0 && attackIndex % everyNth == 0

    // ---- damage rolls -------------------------------------------------------------------------------------------

    /** Uniform roll in [min]..[max] inclusive. */
    @JvmStatic
    fun roll(random: Random, min: Int, max: Int): Int = if (max <= min) min else min + random.nextInt(max - min + 1)

    /** A tendril swipe max: [maxPrayed] under Protect from Melee, else [max]. */
    @JvmStatic
    fun tendrilMax(protectFromMelee: Boolean, max: Int, maxPrayed: Int): Int = if (protectFromMelee) maxPrayed else max

    /** An accuracy-rolled hit: 0 when the roll misses, else uniform 0..[max]. */
    @JvmStatic
    fun accuracyRoll(random: Random, accuracy: Double, max: Int): Int =
        if (random.nextDouble() >= accuracy) 0 else roll(random, 0, max)

    // ---- aggro --------------------------------------------------------------------------------------------------

    /** One candidate for the shared aggro rule (§4 shared rules). */
    data class AggroCandidate<T>(val who: T, val recentDamage: Int, val distance: Int, val slot: Int)

    /**
     * The aggro target: most damage in the last window, then the nearest, then the lowest slot (§4 shared rules).
     */
    @JvmStatic
    fun <T> pickAggro(candidates: List<AggroCandidate<T>>): T? =
        candidates.sortedWith(compareByDescending<AggroCandidate<T>> { it.recentDamage }
            .thenBy { it.distance }.thenBy { it.slot }).firstOrNull()?.who
}
