package com.gadscape.game.content.omnishop

import com.zenyte.game.world.entity.player.Player
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Omnishop buying rules (E4, WU-03) on the Alliance Supplies shops [CACHE 2746 dbrows 17149-17162]:
 * rows in `omnishop_shop_stock` order, costs from `omnishop_stock_cost`, buttons q1..q4 = 1, 5, 6, 2 (enum_5844:
 * buy 1, 2, 3, 5), stock per the wiki (§3.12 of the raid spec). Item ids here are placeholders: the rules only
 * look at rows and counts.
 */
class OmniShopRulesTest {

    private object Credits : OmniShopCurrency {
        override val singular = "Supply Credit"
        override val plural = "Supply Credits"
        override fun balance(player: Player): Int = error("not used by the rules")
        override fun remove(player: Player, amount: Int) = error("not used by the rules")
    }

    private val note = OmniShopStock(17152, 34729, 0, 1, buyable = false, viewOnly = true)
    private val leftovers = OmniShopStock(17153, 34730, 0, 1)
    private val brew = OmniShopStock(17159, 1, 3, 6)
    private val restore = OmniShopStock(17160, 2, 3, 4)
    private val prayer4 = OmniShopStock(17158, 3, 2, 4)
    private val manta = OmniShopStock(17155, 4, 2, 10)
    private val halibut = OmniShopStock(17156, 5, 2, 5)
    private val krill = OmniShopStock(17154, 6, 1, 10)
    private val prayer2 = OmniShopStock(17157, 7, 1, 1)
    private val ranging2 = OmniShopStock(17161, 8, 2, 1)
    private val combat2 = OmniShopStock(17162, 9, 2, 1)

    private val keys = intArrayOf(1, 5, 6, 2)

    private val normal = OmniShopDefinition(
        17150, "Alliance Supplies", 1030, 10, Credits,
        listOf(note, brew, restore, prayer4, manta, halibut, krill, prayer2, ranging2, combat2), keys,
    )
    private val failure = OmniShopDefinition(
        17151, "Alliance Supplies", 1030, 10, Credits,
        listOf(note, leftovers, brew, restore, prayer4, manta, halibut, krill, prayer2, ranging2, combat2), keys,
    )

    @Test
    fun `enum 5844 maps the buy keys`() {
        assertEquals(listOf(1, 2, 3, 5), keys.map(OmniShopRules::quantityForKey))
        assertEquals(10, OmniShopRules.quantityForKey(3))
        assertEquals(50, OmniShopRules.quantityForKey(4))
        assertEquals(0, OmniShopRules.quantityForKey(99))
    }

    @Test
    fun `buying one manta ray costs 2 credits`() {
        val remaining = normal.initialStock()
        val result = OmniShopRules.evaluate(normal, remaining, 4, 1, balance = 10, carryLimit = 28)
        assertIs<OmniShopPurchase.Ok>(result)
        assertEquals(manta, result.row)
        assertEquals(1, result.quantity)
        assertEquals(2, result.totalCost)
    }

    @Test
    fun `buy-2 key 5 buys two`() {
        val result = OmniShopRules.evaluate(normal, normal.initialStock(), 4, 5, balance = 10, carryLimit = 28)
        assertIs<OmniShopPurchase.Ok>(result)
        assertEquals(2, result.quantity)
        assertEquals(4, result.totalCost)
    }

    @Test
    fun `beyond stock or credits or space is refused with no item`() {
        val remaining = normal.initialStock()
        remaining[7] = 0 // prayer potion(2): bought already
        val outOfStock = OmniShopRules.evaluate(normal, remaining, 7, 1, balance = 50, carryLimit = 28)
        assertIs<OmniShopPurchase.Refused>(outOfStock)
        assertEquals("The shop has run out of stock.", outOfStock.message)

        // only 1 combat potion in stock, asked for 2
        val beyondStock = OmniShopRules.evaluate(normal, normal.initialStock(), 9, 5, balance = 50, carryLimit = 28)
        assertIs<OmniShopPurchase.Refused>(beyondStock)

        // 3 credits, 2 brews cost 6
        val poor = OmniShopRules.evaluate(normal, normal.initialStock(), 1, 5, balance = 3, carryLimit = 28)
        assertIs<OmniShopPurchase.Refused>(poor)
        assertEquals("You don't have enough Supply Credits.", poor.message)

        val broke = OmniShopRules.evaluate(normal, normal.initialStock(), 6, 1, balance = 0, carryLimit = 28)
        assertIs<OmniShopPurchase.Refused>(broke)

        val full = OmniShopRules.evaluate(normal, normal.initialStock(), 6, 6, balance = 50, carryLimit = 2)
        assertIs<OmniShopPurchase.Refused>(full)
        assertEquals("You don't have enough inventory space.", full.message)
    }

    @Test
    fun `the view-only note and forged keys are never sold`() {
        val note = OmniShopRules.evaluate(normal, normal.initialStock(), 0, 1, balance = 50, carryLimit = 28)
        assertIs<OmniShopPurchase.Refused>(note)
        assertTrue(note.forged)
        val badKey = OmniShopRules.evaluate(normal, normal.initialStock(), 4, 3, balance = 500, carryLimit = 28)
        assertIs<OmniShopPurchase.Refused>(badKey)
        assertTrue(badKey.forged)
        val badRow = OmniShopRules.evaluate(normal, normal.initialStock(), 42, 1, balance = 500, carryLimit = 28)
        assertIs<OmniShopPurchase.Refused>(badRow)
    }

    @Test
    fun `buying the whole normal stock costs exactly 83`() {
        val remaining = normal.initialStock()
        var balance = 83
        var spent = 0
        for ((index, row) in normal.stock.withIndex()) {
            if (!row.sellable) continue
            while (remaining[index] > 0) {
                val result = OmniShopRules.evaluate(normal, remaining, index, 1, balance, Int.MAX_VALUE)
                assertIs<OmniShopPurchase.Ok>(result)
                balance -= result.totalCost
                spent += result.totalCost
                remaining[index] -= result.quantity
            }
        }
        assertEquals(83, spent)
        assertEquals(0, balance)
    }

    @Test
    fun `the failure shop's slot 1 is Leftovers and costs the client's minimum of 1`() {
        val result = OmniShopRules.evaluate(failure, failure.initialStock(), 1, 1, balance = 5, carryLimit = 28)
        assertIs<OmniShopPurchase.Ok>(result)
        assertEquals(leftovers, result.row)
        assertEquals(1, result.totalCost) // script7261: max(cost, 1)
        // row 2 of the failure shop is the brew (the note takes no inv slot but keeps its list index)
        val brewBuy = OmniShopRules.evaluate(failure, failure.initialStock(), 2, 1, balance = 5, carryLimit = 28)
        assertIs<OmniShopPurchase.Ok>(brewBuy)
        assertEquals(brew, brewBuy.row)
    }

    @Test
    fun `the shop inv holds one slot per buyable row with stock`() {
        val normalInv = OmniShopRules.invContents(normal, normal.initialStock())
        assertEquals(9, normalInv.size)
        assertEquals(brew.itemId to 6, normalInv[0])
        assertTrue(normalInv.none { it.first == note.itemId })
        val failureInv = OmniShopRules.invContents(failure, failure.initialStock())
        assertEquals(10, failureInv.size)
        assertEquals(leftovers.itemId to 1, failureInv[0])
        val remaining = normal.initialStock().also { it[1] = 0 }
        assertTrue(OmniShopRules.invContents(normal, remaining).none { it.first == brew.itemId })
    }

    @Test
    fun `a definition must fit its inv`() {
        assertFailsWith<IllegalArgumentException> {
            OmniShopDefinition(1, "x", 1030, 2, Credits, listOf(brew, restore, manta), keys)
        }
    }
}
