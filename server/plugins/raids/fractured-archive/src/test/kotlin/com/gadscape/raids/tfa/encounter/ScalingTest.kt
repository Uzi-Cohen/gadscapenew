package com.gadscape.raids.tfa.encounter

import com.gadscape.raids.tfa.config.ScalingConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScalingTest {

    private val scaling = Scaling.DEFAULT

    /** Spec §2.6 "Scaled HP table": base (2-scale) and the values for n = 2..8. */
    private val table: Map<String, Pair<Int, List<Int>>> = linkedMapOf(
        "Guardian shield" to (550 to listOf(550, 785, 1020, 1255, 1490, 1725, 1960)),
        "Guardian main" to (1100 to listOf(1100, 1570, 2040, 2510, 2980, 3450, 3920)),
        "Ezekiel" to (3000 to listOf(3000, 4282, 5564, 6845, 8127, 9409, 10691)),
        "Bork" to (1500 to listOf(1500, 2141, 2782, 3423, 4064, 4705, 5345)),
        "The Smorks" to (600 to listOf(600, 856, 1113, 1369, 1625, 1882, 2138)),
        "Moia" to (1900 to listOf(1900, 2712, 3524, 4335, 5147, 5959, 6771)),
        "Dreadnoth" to (2100 to listOf(2100, 2997, 3895, 4792, 5689, 6586, 7484)),
        "Dagon'hai pool per break" to (640 to listOf(640, 913, 1187, 1460, 1734, 2007, 2281)),
        "Xarth" to (2600 to listOf(2600, 3711, 4822, 5933, 7044, 8155, 9265)),
        "Ocellus P1" to (2500 to listOf(2500, 3568, 4636, 5705, 6773, 7841, 8909)),
        "The Blade (P2)" to (1000 to listOf(1000, 1427, 1855, 2282, 2709, 3136, 3564)),
        "Ocellus P3/P4" to (5250 to listOf(5250, 7493, 9736, 11980, 14223, 16466, 18709)),
    )

    @Test
    fun liveDataPointGuardianShieldAtSix() {
        assertEquals(1490, scaling.scaledHp(550, 6))
    }

    @Test
    fun reproducesTheWholeSpecTable() {
        for ((name, row) in table) {
            val (base, expected) = row
            for ((i, hp) in expected.withIndex()) {
                val n = i + 2
                assertEquals(hp, scaling.scaledHp(base, n), "$name at n=$n")
                assertEquals(hp, scaling.hp(base, n), "$name at n=$n via hp()")
            }
        }
    }

    @Test
    fun soloCountsAsTwoAndPartiesAreCappedAtEight() {
        assertEquals(2, scaling.partyScale(1))
        assertEquals(2, scaling.partyScale(0))
        assertEquals(5, scaling.partyScale(5))
        assertEquals(8, scaling.partyScale(12))
        assertEquals(550, scaling.scaledHp(550, 1), "n below the range clamps to 2")
        assertEquals(1960, scaling.scaledHp(550, 9), "n above the range clamps to 8")
    }

    @Test
    fun forcedScaleWinsButIsClamped() {
        assertEquals(6, scaling.partyScale(1, forced = 6))
        assertEquals(8, scaling.partyScale(3, forced = 20))
        assertEquals(2, scaling.partyScale(7, forced = 0))
    }

    @Test
    fun xarthCorpseOverrideIs20Plus7PerPlayer() {
        for (n in 2..8) {
            assertEquals(20 + 7 * (n - 2), scaling.hp(20, n, ScalingConfig.XARTH_CORPSE_HP), "corpse at n=$n")
        }
        assertEquals(62, scaling.hp(20, 8, ScalingConfig.XARTH_CORPSE_HP), "the cache's 62 is the 8-player value")
        assertEquals(scaling.scaledHp(20, 5), scaling.hp(20, 5, "no_such_key"), "unknown keys use the formula")
    }

    @Test
    fun combatLevelOverrides() {
        assertEquals(1064, scaling.combatLevel(ScalingConfig.MOIA))
        assertEquals(1601, scaling.combatLevel(ScalingConfig.OCELLUS_P3))
        assertNull(scaling.combatLevel(ScalingConfig.OCELLUS_P1), "P1 keeps the cache level 1121")
    }

    @Test
    fun thresholdsAreFractionsOfScaledMax() {
        val max = scaling.scaledHp(1100, 6) // 2980
        assertTrue(scaling.crossed(1000, 894, max, 30.0), "30% of 2980 is 894")
        assertFalse(scaling.crossed(1000, 895, max, 30.0))
        assertFalse(scaling.crossed(894, 800, max, 30.0), "already below")
        // one hit crossing two thresholds sees both
        assertTrue(scaling.crossed(2000, 500, max, 65.0) && scaling.crossed(2000, 500, max, 30.0))
    }

    @Test
    fun roundingModesAreConfigurable() {
        // 5250 at n=5: 11979.5025
        assertEquals(11980, Scaling.formula(5250, 5, 0.42727, 2, ScalingConfig.Rounding.HALF_UP))
        assertEquals(11979, Scaling.formula(5250, 5, 0.42727, 2, ScalingConfig.Rounding.FLOOR))
        assertEquals(11980, Scaling.formula(5250, 5, 0.42727, 2, ScalingConfig.Rounding.CEIL))
        // an exact half: 1 x (1 + 0.5 x 3) = 2.5
        assertEquals(3, Scaling.formula(1, 5, 0.5, 2, ScalingConfig.Rounding.HALF_UP))
        assertEquals(2, Scaling.formula(1, 5, 0.5, 2, ScalingConfig.Rounding.HALF_EVEN))
    }

    @Test
    fun perPlayerFromConfigChangesTheCurve() {
        val flat = Scaling(ScalingConfig.DEFAULT.copy(perPlayer = 0.5))
        assertEquals(1000, flat.scaledHp(500, 4)) // 500 x (1 + 0.5 x 2)
    }
}
