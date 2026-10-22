package com.gadscape.raids.tfa.encounter

import com.gadscape.raids.tfa.config.ScalingConfig
import java.math.BigDecimal

/**
 * Party-size scaling (spec §2.6, §3.9).
 *
 * - `n = clamp(members inside the raid instance when the encounter starts, minScale, maxScale)`; solo counts as 2.
 * - `scaledHp(base, n) = round(base × (1 + perPlayer × (n − minScale)))`, base = the 2-player HP. Computed in
 *   decimal arithmetic so the §2.6 table is reproduced exactly (e.g. `scaledHp(550, 6) = 1490`).
 * - A key in `scaling.json overrides` replaces the formula with a table (Xarth corpses: `20 + 7 × (n − 2)`).
 * - Thresholds are fractions of the scaled max HP ([crossed]); damage does not scale.
 * - Displayed combat levels come from `combatLevelOverride` ([combatLevel]); absent keys keep the cache level.
 *
 * Take the instance from the raid's config snapshot (`Scaling(snapshot.scaling)`) so a running raid is not
 * affected by `::tfareload`.
 */
class Scaling(val config: ScalingConfig) {

    /**
     * The scale for an encounter: [members] clamped to `minScale..maxScale`. [forced] (from `::tfa scale`, see
     * `TfaDebug.forcedScale`) wins when set, clamped the same way.
     */
    fun partyScale(members: Int, forced: Int? = null): Int =
        (forced ?: members).coerceIn(config.minScale, config.maxScale)

    /** The formula HP of a [base] (2-player) HP at scale [n] (clamped to the scale range). */
    fun scaledHp(base: Int, n: Int): Int =
        formula(base, n.coerceIn(config.minScale, config.maxScale), config.perPlayer, config.minScale, config.rounding)

    /**
     * The HP to spawn with: the `overrides` table for [overrideKey] if `scaling.json` has one, else [scaledHp].
     * Bosses pass their own key (e.g. `"xarth_corpse_hp"`); most pass null.
     */
    fun hp(base: Int, n: Int, overrideKey: String? = null): Int {
        val table = overrideKey?.let { config.overrides[it] }
        if (table != null) {
            val index = n.coerceIn(config.minScale, config.maxScale) - config.minScale
            if (index in table.indices) return table[index]
        }
        return scaledHp(base, n)
    }

    /** The overridden displayed combat level for [key] (`"moia"`, `"ocellus_p3"`), or null to keep the cache's. */
    fun combatLevel(key: String): Int? = config.combatLevelOverride[key]

    /**
     * True when a hit takes HP from above `percent`% of [scaledMax] to at or below it. Use it for every HP
     * threshold so several thresholds crossed by one hit are all seen (queue them in order, §2.5).
     */
    fun crossed(hpBefore: Int, hpAfter: Int, scaledMax: Int, percent: Double): Boolean {
        val mark = scaledMax * percent / 100.0
        return hpBefore > mark && hpAfter <= mark
    }

    companion object {

        /** Scaling with the spec defaults (for tests and tools; raids use their snapshot). */
        @JvmField
        val DEFAULT = Scaling(ScalingConfig.DEFAULT)

        /** `round(base × (1 + perPlayer × (n − minScale)))` with [rounding]; pure, for tests and tools. */
        @JvmStatic
        fun formula(base: Int, n: Int, perPlayer: Double, minScale: Int, rounding: ScalingConfig.Rounding): Int {
            val factor = BigDecimal.ONE + BigDecimal.valueOf(perPlayer) * BigDecimal.valueOf((n - minScale).toLong())
            return (BigDecimal.valueOf(base.toLong()) * factor).setScale(0, rounding.mode).intValueExact()
        }
    }
}
