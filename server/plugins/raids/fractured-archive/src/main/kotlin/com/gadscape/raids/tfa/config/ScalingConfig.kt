package com.gadscape.raids.tfa.config

import com.gadscape.raids.tfa.config.Evidence.DOC
import com.gadscape.raids.tfa.config.Evidence.HOUSE
import com.gadscape.raids.tfa.config.Evidence.INF
import java.math.RoundingMode

/**
 * `scaling.json` (spec §2.6, §3.9, U1-U3). Read by [com.gadscape.raids.tfa.encounter.Scaling].
 *
 * ```json
 * { "minScale": 2, "maxScale": 8, "perPlayer": 0.42727, "rounding": "half_up",
 *   "overrides": { "xarth_corpse_hp": [20, 27, 34, 41, 48, 55, 62] },
 *   "combatLevelOverride": { "moia": 1064, "ocellus_p3": 1601 } }
 * ```
 */
data class ScalingConfig(
    /** Smallest scale; a solo raid counts as this (DOC: "solo counts as 2"). */
    val minScale: Int,
    /** Largest scale (party cap 8). */
    val maxScale: Int,
    /** HP growth per player above [minScale]: 0.42727 = 940 / 2200, fitted to Guardian shield 550 → 1490 at 6. */
    val perPlayer: Double,
    val rounding: Rounding,
    /** Per-key HP tables indexed by `n − minScale` (one entry per scale [minScale]..[maxScale]). */
    val overrides: Map<String, List<Int>>,
    /** Displayed combat levels by key (E12). Ocellus P1 is intentionally absent (cache 1121). */
    val combatLevelOverride: Map<String, Int>,
) {

    enum class Rounding(val mode: RoundingMode) {
        HALF_UP(RoundingMode.HALF_UP),
        HALF_EVEN(RoundingMode.HALF_EVEN),
        FLOOR(RoundingMode.FLOOR),
        CEIL(RoundingMode.CEILING),
    }

    /** Number of scales, i.e. the required length of every [overrides] table. */
    val scaleCount: Int get() = maxScale - minScale + 1

    companion object SPEC : TfaConfigSpec<ScalingConfig>("scaling.json") {

        /** Override keys the spec defines. Bosses may add their own. */
        const val XARTH_CORPSE_HP = "xarth_corpse_hp"
        const val MOIA = "moia"
        const val OCELLUS_P1 = "ocellus_p1"
        const val OCELLUS_P3 = "ocellus_p3"

        /** The spec defaults, without reading any file. */
        @JvmField
        val DEFAULT = ScalingConfig(
            minScale = 2,
            maxScale = 8,
            perPlayer = 0.42727,
            rounding = Rounding.HALF_UP,
            overrides = mapOf(XARTH_CORPSE_HP to listOf(20, 27, 34, 41, 48, 55, 62)),
            combatLevelOverride = mapOf(MOIA to 1064, OCELLUS_P3 to 1601),
        )

        override fun read(r: ConfigReader): ScalingConfig {
            var minScale = r.int("minScale", DEFAULT.minScale, DOC, min = 1, max = 8)
            var maxScale = r.int("maxScale", DEFAULT.maxScale, DOC, min = 1, max = 8)
            if (!r.check("maxScale", minScale <= maxScale) { "minScale $minScale is above maxScale $maxScale; using 2-8" }) {
                minScale = DEFAULT.minScale
                maxScale = DEFAULT.maxScale
            }
            val count = maxScale - minScale + 1
            return ScalingConfig(
                minScale = minScale,
                maxScale = maxScale,
                perPlayer = r.double("perPlayer", DEFAULT.perPlayer, HOUSE, min = 0.0, max = 10.0),
                rounding = r.enum("rounding", DEFAULT.rounding, HOUSE),
                overrides = r.intListMap("overrides", DEFAULT.overrides, INF, listSize = count..count, min = 1),
                combatLevelOverride = r.intMap("combatLevelOverride", DEFAULT.combatLevelOverride, DOC, min = 1, max = 9999),
            )
        }
    }
}
