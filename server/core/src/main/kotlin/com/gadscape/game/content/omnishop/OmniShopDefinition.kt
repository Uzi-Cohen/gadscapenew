package com.gadscape.game.content.omnishop

/**
 * One stock row of an omnishop, in the shop's `omnishop_shop_stock` order (dbtable `omnishop_stock_data`).
 *
 * @property dbrow the stock dbrow (for logs and checks; the client finds the row by its index in the shop row).
 * @property itemId `omnishop_stock_obj`: what a purchase gives.
 * @property cost the `omnishop_stock_cost` amount in the shop's currency (before [OmniShopRules.unitCost]).
 * @property stock how many a player may buy (per player, until [OmniShop.resetStock]); ignored for view-only rows.
 * @property buyable `omnishop_stock_buyable` (default true in the cache).
 * @property viewOnly `omnishop_stock_view_only`: listed (with no count) but never sold and never in the inv.
 */
data class OmniShopStock(
    val dbrow: Int,
    val itemId: Int,
    val cost: Int,
    val stock: Int,
    val buyable: Boolean = true,
    val viewOnly: Boolean = false,
) {
    /** Whether this row can ever be sold (and so takes an inv slot). */
    val sellable: Boolean get() = buyable && !viewOnly
}

/**
 * A server-side omnishop (E4): the stock `omnishop_main` interface (819) drawn by the client from the shop dbrow,
 * the shop inv (stock counts) and a currency var.
 *
 * The client reads everything it shows from the cache (names, costs, buttons, texts); the server only needs what
 * the cache cannot tell it: the per-player stock counts, which var holds the currency, and what to give.
 * [stock] must be in the shop row's `omnishop_shop_stock` order: the buy trigger names a row by that index.
 *
 * @property shopDbrow the `omnishop_shop_data` row (e.g. 17150 `tfa_midraid_supplies_shop`).
 * @property title the shop name (`omnishop_shop_name`), used by the classic fallback and messages.
 * @property invId `omnishop_shop_inv` (e.g. 1030); the client counts each row's obj in it (`inv_total`).
 * @property invCapacity the inv's size in the cache (e.g. 10).
 * @property currency where the money lives (e.g. [VarbitCurrency] for varbit 16381).
 * @property quantityKeys `omnishop_buy_q1..q4`: the buy buttons as `enum_5844` keys (cache defaults 1, 2, 3, 4;
 *   the TFA shops use 1, 5, 6, 2 = buy 1 / 2 / 3 / 5).
 * @property costModBuy `omnishop_shop_cost_mod_buy` (cache default 1000 = 100%).
 * @property allOrNothing refuse a purchase the player cannot fully pay, stock or carry (true), or sell as many as
 *   possible (false).
 */
class OmniShopDefinition @JvmOverloads constructor(
    val shopDbrow: Int,
    val title: String,
    val invId: Int,
    val invCapacity: Int,
    val currency: OmniShopCurrency,
    val stock: List<OmniShopStock>,
    val quantityKeys: IntArray = intArrayOf(1, 2, 3, 4),
    val costModBuy: Int = 1000,
    val allOrNothing: Boolean = true,
) {
    init {
        require(stock.isNotEmpty()) { "omnishop $shopDbrow has no stock rows" }
        require(quantityKeys.size == 4) { "omnishop $shopDbrow: 4 buy buttons expected, got ${quantityKeys.size}" }
        val slots = stock.count { it.sellable }
        require(slots <= invCapacity) { "omnishop $shopDbrow: $slots buyable rows but inv $invId has $invCapacity slots" }
    }

    /** The row at [index] (the trigger's stock index), or null. */
    fun row(index: Int): OmniShopStock? = stock.getOrNull(index)

    /** The initial per-player stock, one entry per row (0 for rows that are never sold). */
    fun initialStock(): IntArray = IntArray(stock.size) { if (stock[it].sellable) stock[it].stock.coerceAtLeast(0) else 0 }

    override fun toString(): String = "OmniShopDefinition($shopDbrow '$title', inv $invId, ${stock.size} rows, $currency)"
}

/** Where an omnishop's money lives. */
interface OmniShopCurrency {
    val singular: String
    val plural: String
    fun balance(player: com.zenyte.game.world.entity.player.Player): Int
    /** Removes [amount]; only called after [balance] covered it. */
    fun remove(player: com.zenyte.game.world.entity.player.Player, amount: Int)
    fun name(amount: Int): String = if (amount == 1) singular else plural
}

/**
 * A currency held in a varbit (the omnishop's `omnishop_var_getter` / `omnishop_var_setter` case for it), e.g.
 * varbit 16381 `tfa_supply_points` "Supply Credit(s)" [CACHE dbrow 17149]. Changes are sent at once so the open
 * shop redraws in the same tick.
 */
class VarbitCurrency(val varbit: Int, override val singular: String, override val plural: String) : OmniShopCurrency {
    override fun balance(player: com.zenyte.game.world.entity.player.Player): Int = player.varManager.getBitValue(varbit)
    override fun remove(player: com.zenyte.game.world.entity.player.Player, amount: Int) {
        player.varManager.sendBitInstant(varbit, (balance(player) - amount).coerceAtLeast(0))
    }
    override fun toString(): String = "VarbitCurrency($varbit '$plural')"
}

/** The result of one purchase request ([OmniShopRules.evaluate]). */
sealed class OmniShopPurchase {
    /** Give [quantity] of row [index] for [totalCost]. */
    data class Ok(val index: Int, val row: OmniShopStock, val quantity: Int, val totalCost: Int, val partial: Boolean) : OmniShopPurchase()

    /** Nothing happens; [message] (if any) goes to the player. [forged] marks requests the client cannot send. */
    data class Refused(val message: String?, val forged: Boolean = false) : OmniShopPurchase()
}

/**
 * The omnishop's buying rules, without a world: the server's mirror of the client scripts
 * [CACHE 2746 osrs-dumps 6e5033af: omnishop_main_list_item_op, omnishop_main_buttons_setup_buy, script10163,
 * omnishop_cost (7259), script7261, script7263, enum_5844].
 */
object OmniShopRules {

    /**
     * `enum_5844`: buy-button key -> quantity [CACHE 2746]. Key 4 (50) is hidden on KotS worlds only.
     * The buy trigger sends the key, not the quantity.
     */
    private val QUANTITY_BY_KEY = mapOf(0 to 0, 1 to 1, 5 to 2, 6 to 3, 2 to 5, 3 to 10, 4 to 50)

    @JvmStatic
    fun quantityForKey(key: Int): Int = QUANTITY_BY_KEY[key] ?: 0

    /**
     * The price of one item of [row], as the client shows it: `script7261(cost, haggle 0, cost_mod_buy, 0)` =
     * `max(1, scale(max(100, costModBuy), 1000, cost))`. Note the client never shows a price below 1, so a row
     * whose cache cost is 0 (TFA Leftovers) costs 1.
     */
    @JvmStatic
    fun unitCost(definition: OmniShopDefinition, row: OmniShopStock): Int {
        val modifier = definition.costModBuy.coerceAtLeast(100).toLong()
        val scaled = modifier * row.cost / 1000L
        return if (scaled < 0 || scaled > Int.MAX_VALUE) Int.MAX_VALUE else scaled.toInt().coerceAtLeast(1)
    }

    /**
     * Decides a buy request: row [index] of [definition], button [quantityKey], with [remaining] (per row) left
     * for this player, [balance] money and room for [carryLimit] more of the row's item (Int.MAX_VALUE = no limit).
     */
    @JvmStatic
    fun evaluate(
        definition: OmniShopDefinition,
        remaining: IntArray,
        index: Int,
        quantityKey: Int,
        balance: Int,
        carryLimit: Int,
    ): OmniShopPurchase {
        val row = definition.row(index) ?: return OmniShopPurchase.Refused(null, forged = true)
        if (!row.sellable) {
            return OmniShopPurchase.Refused(if (row.viewOnly) null else "You can't buy that.", forged = row.viewOnly)
        }
        if (quantityKey !in definition.quantityKeys) return OmniShopPurchase.Refused(null, forged = true)
        val wanted = quantityForKey(quantityKey)
        if (wanted <= 0) return OmniShopPurchase.Refused(null, forged = true)
        val left = remaining.getOrElse(index) { 0 }
        if (left <= 0) return OmniShopPurchase.Refused("The shop has run out of stock.")
        val unit = unitCost(definition, row)
        val affordable = if (unit <= 0) Int.MAX_VALUE else balance / unit
        val quantity = minOf(wanted, left, affordable, carryLimit)
        if (quantity < wanted && definition.allOrNothing || quantity <= 0) {
            return OmniShopPurchase.Refused(
                when {
                    wanted > left -> "The shop doesn't have that many in stock."
                    wanted > affordable || affordable <= 0 ->
                        "You don't have enough ${definition.currency.plural}."
                    else -> "You don't have enough inventory space."
                }
            )
        }
        return OmniShopPurchase.Ok(index, row, quantity, unit * quantity, quantity < wanted)
    }

    /**
     * The shop inv contents the client counts stock from (`inv_total(inv, obj)` per row): one slot per sellable
     * row with stock left, in row order, holding the remaining count. View-only rows are never in the inv (the
     * client then draws them with no count). Returns (itemId, count) pairs, at most [OmniShopDefinition.invCapacity].
     */
    @JvmStatic
    fun invContents(definition: OmniShopDefinition, remaining: IntArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(definition.invCapacity)
        for ((index, row) in definition.stock.withIndex()) {
            if (!row.sellable) continue
            val left = remaining.getOrElse(index) { 0 }
            if (left <= 0) continue
            if (out.size >= definition.invCapacity) break
            out += row.itemId to left
        }
        return out
    }
}
