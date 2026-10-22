package com.gadscape.raids.tfa.rooms.moia

/**
 * Pure timing rules of the Moia room (spec §4.4 attack table; `moia.json projectiles` / `special`), shared by the
 * room and its tests. Distances: `footprint` = Chebyshev distance from Moia's 3x3 to the target (the hit-delay
 * distance), `middle` = from her centre tile (what the projectile packet measures).
 */
object MoiaTiming {

    private const val CYCLES_PER_TICK = 30

    /** Ticks from a throw to its hit: the documented formula, but never before the projectile visibly lands. */
    fun landingDelay(spec: MoiaConfig.ProjectileSpec, footprint: Int, middle: Int): Int =
        maxOf(spec.hitDelay(footprint), ceilTicks(spec.flightCycles(middle)))

    /** Ticks from the blue cast to its hit (`special.hitDelay`, 3 by default: lands on T+6, never with a dagger). */
    fun magicDelay(config: MoiaConfig, middle: Int): Int =
        maxOf(config.special.hitDelay, ceilTicks(config.projectiles.magic.flightCycles(middle)))

    /** HP at or below which phase 1 ends: floor(scaled max × fraction). */
    fun enrageMark(scaledMax: Int, fraction: Double): Int = kotlin.math.floor(scaledMax * fraction).toInt()

    /** The angle the Zenyte `Projectile` wants (degrees) for a client slope (0-64). */
    fun slopeDegrees(clientSlope: Int): Int = Math.round(clientSlope * 90.0 / 64.0).toInt()

    fun ceilTicks(cycles: Int): Int = (cycles + CYCLES_PER_TICK - 1) / CYCLES_PER_TICK
}
