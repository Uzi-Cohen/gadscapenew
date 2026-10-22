package com.gadscape.raids.tfa.supplies

import com.gadscape.raids.tfa.config.RaidConfig
import com.gadscape.raids.tfa.points.ContributionLedger
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Supply Credits granted once, when room 3 (the section-2 room) completes (spec §3.12 [HOUSE], `tfa.json supplies`):
 * ```
 * credits = clamp(base + round(share × min(1, points / fair)) − deathPenalty × deaths, 0, max)
 * fair    = fairSharePoints / raidSize
 * ```
 * `points` = the player's banked contribution after rooms 1-3; `deaths` = their deaths in rooms 1-3. Credits live in
 * varbit 16381 (7 bits, 0-127) and are reset to 0 when the raid ends or the player leaves it.
 *
 * The failure shop (dbrow 17151, with Leftovers) is used when `points < failureShopPct % × fairSharePoints / raidSize`
 * (`points.json failureShopPct`, §3.10).
 *
 * Pure: unit-tested in `SupplyCreditsTest`.
 */
object SupplyCredits {

    @JvmStatic
    fun credits(supplies: RaidConfig.Supplies, points: Int, raidSize: Int, deaths: Int): Int {
        val size = raidSize.coerceAtLeast(1)
        // points / (fair / size) = points × size / fair, capped at 1
        val ratio = BigDecimal.valueOf(points.toLong() * size)
            .divide(BigDecimal.valueOf(supplies.fairSharePoints.toLong().coerceAtLeast(1)), 10, RoundingMode.HALF_UP)
            .min(BigDecimal.ONE)
            .max(BigDecimal.ZERO)
        val bonus = BigDecimal.valueOf(supplies.shareCredits.toLong()).multiply(ratio).setScale(0, RoundingMode.HALF_UP).toInt()
        val raw = supplies.baseCredits + bonus - supplies.deathPenalty * deaths.coerceAtLeast(0)
        return raw.coerceIn(0, supplies.maxCredits.coerceAtMost(MAX_VARBIT))
    }

    /** True when the player gets the failure shop (17151) instead of the normal one (17150). */
    @JvmStatic
    fun usesFailureShop(supplies: RaidConfig.Supplies, failureShopPct: Int, points: Int, raidSize: Int): Boolean =
        ContributionLedger.isBelow(points, failureShopPct, supplies.fairSharePoints, raidSize)

    /** Varbit 16381 is 7 bits [CACHE]. */
    const val MAX_VARBIT = 127
}
