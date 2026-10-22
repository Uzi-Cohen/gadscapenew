package com.gadscape.raids.tfa.supplies

import com.gadscape.game.content.omnishop.OmniShopDefinition
import com.gadscape.game.content.omnishop.OmniShopStock
import com.gadscape.game.content.omnishop.VarbitCurrency
import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.item.ItemId

/**
 * The two Alliance Supplies omnishops (spec §3.12, WU-17; core `OmniShop`, E4):
 * - normal dbrow **17150** and failure dbrow **17151** (adds Leftovers 34730 second) [CACHE];
 * - rows in `omnishop_shop_stock` order (the client's display order), not dbrow order [CACHE §3.12 C+];
 * - costs from `omnishop_stock_cost` [CACHE], stock from the wiki [DOC]; buying all of it costs 83 credits;
 * - the Alliance log 34729 row 17152 is view-only: shown first, never bought, never in inv 1030 [CACHE];
 * - currency varbit **16381** `tfa_supply_points` ("Supply Credit" / "Supply Credits", dbrow 17149) [CACHE];
 * - stock inv **1030** (10 slots), buy buttons 1 / 5 / 6 / 2 = enum_5844 keys for buy 1 / 2 / 3 / 5 [CACHE].
 */
object AllianceSuppliesShop {

    const val TITLE = "Alliance Supplies"

    /** One row: dbrow, item, cost, stock. */
    private data class Row(val dbrow: Int, val item: Int, val cost: Int, val stock: Int)

    /** Buyable rows after the note, in shop order (normal shop) [CACHE costs, DOC stock]. */
    private fun rows(): List<Row> {
        val d = TfaIds.Dbrow
        return listOf(
            Row(d.STOCK_SARADOMIN_BREW_4, ItemId.SARADOMIN_BREW4, 3, 6),
            Row(d.STOCK_SUPER_RESTORE_4, ItemId.SUPER_RESTORE4, 3, 4),
            Row(d.STOCK_PRAYER_POTION_4, ItemId.PRAYER_POTION4, 2, 4),
            Row(d.STOCK_MANTA_RAY, ItemId.MANTA_RAY, 2, 10),
            Row(d.STOCK_HALIBUT, TfaIds.StockObj.HALIBUT, 2, 5),
            Row(d.STOCK_GIANT_KRILL, TfaIds.StockObj.GIANT_KRILL, 1, 10),
            Row(d.STOCK_PRAYER_POTION_2, ItemId.PRAYER_POTION2, 1, 1),
            Row(d.STOCK_RANGING_POTION_2, ItemId.RANGING_POTION2, 2, 1),
            Row(d.STOCK_SUPER_COMBAT_2, ItemId.SUPER_COMBAT_POTION2, 2, 1),
        )
    }

    /** The shop definition: normal (17150) or failure (17151, Leftovers second). */
    @JvmStatic
    fun definition(failure: Boolean): OmniShopDefinition {
        val d = TfaIds.Dbrow
        val stock = buildList {
            add(OmniShopStock(d.STOCK_NOTE, TfaIds.Obj.ALLIANCE_LOG, 0, 1, buyable = false, viewOnly = true))
            if (failure) add(OmniShopStock(d.STOCK_LEFTOVERS, TfaIds.Obj.LEFTOVERS, 0, 1))
            for (r in rows()) add(OmniShopStock(r.dbrow, r.item, r.cost, r.stock))
        }
        return OmniShopDefinition(
            shopDbrow = if (failure) d.SHOP_FAILURE else d.SHOP_NORMAL,
            title = TITLE,
            invId = TfaIds.Inv.SUPPLIES_SHOP,
            invCapacity = 10,
            currency = VarbitCurrency(TfaIds.Varbit.SUPPLY_POINTS, "Supply Credit", "Supply Credits"),
            stock = stock,
            quantityKeys = intArrayOf(1, 5, 6, 2),
        )
    }

    /** Credits needed to buy the whole normal stock (83 [CACHE costs × DOC stock]). */
    @JvmStatic
    fun totalCost(): Int = rows().sumOf { it.cost * it.stock }
}
