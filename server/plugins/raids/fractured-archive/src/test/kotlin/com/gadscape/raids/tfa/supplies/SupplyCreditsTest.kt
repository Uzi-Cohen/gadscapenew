package com.gadscape.raids.tfa.supplies

import com.gadscape.raids.tfa.config.RaidConfig
import com.gadscape.raids.tfa.config.TfaConfigTestKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupplyCreditsTest {

    private val supplies: RaidConfig.Supplies = TfaConfigTestKit.load(listOf(RaidConfig), bundled = emptyMap())[RaidConfig].supplies

    @Test
    fun formulaFollowsTheSpec() {
        // clamp(40 + round(43 × min(1, share / (16000 / size))) − 10 × deaths, 0, 83)
        assertEquals(83, SupplyCredits.credits(supplies, 4000, 4, 0))     // fair share exactly
        assertEquals(83, SupplyCredits.credits(supplies, 16000, 4, 0))    // above fair share: capped at 1
        assertEquals(62, SupplyCredits.credits(supplies, 2000, 4, 0))     // half: 40 + round(21.5) = 62
        assertEquals(40, SupplyCredits.credits(supplies, 0, 4, 0))
        assertEquals(42, SupplyCredits.credits(supplies, 2000, 4, 2))     // two deaths: -20
        assertEquals(0, SupplyCredits.credits(supplies, 0, 4, 9))         // never negative
    }

    @Test
    fun soloUsesItsOwnFairShare() {
        // raidSize 1: fair share = 16000.
        assertEquals(40 + 22, SupplyCredits.credits(supplies, 8000, 1, 0))
        assertEquals(83, SupplyCredits.credits(supplies, 16000, 1, 0))
    }

    @Test
    fun neverAboveTheCap() {
        for (size in 1..8) for (points in listOf(0, 1000, 16000, 100_000)) {
            val c = SupplyCredits.credits(supplies, points, size, 0)
            assertTrue(c in 0..83, "size $size points $points -> $c")
        }
    }

    @Test
    fun failureShopThreshold() {
        // points after room 3 < 25% of 16000 / size: 4 players → 1000.
        assertTrue(SupplyCredits.usesFailureShop(supplies, 25, 999, 4))
        assertFalse(SupplyCredits.usesFailureShop(supplies, 25, 1000, 4))
    }

    @Test
    fun buyingEverythingCosts83() {
        assertEquals(83, AllianceSuppliesShop.totalCost())
    }

    @Test
    fun shopRowsAreInStockOrderWithTheNoteFirst() {
        val normal = AllianceSuppliesShop.definition(failure = false)
        val failure = AllianceSuppliesShop.definition(failure = true)
        assertEquals(17150, normal.shopDbrow)
        assertEquals(17151, failure.shopDbrow)
        assertEquals(10, normal.stock.size)
        assertEquals(11, failure.stock.size)
        assertEquals(17152, normal.stock[0].dbrow)   // the Alliance log note, view-only
        assertTrue(normal.stock[0].viewOnly)
        assertFalse(normal.stock[0].buyable)
        assertEquals(17153, failure.stock[1].dbrow)  // Leftovers second in the failure shop
        assertEquals(17159, normal.stock[1].dbrow)   // then Saradomin brew(4)
        assertEquals(17162, normal.stock.last().dbrow) // Super combat potion(2) last
        assertEquals(listOf(1, 5, 6, 2), normal.quantityKeys.toList())
    }
}
