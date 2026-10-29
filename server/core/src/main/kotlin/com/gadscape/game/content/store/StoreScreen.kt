package com.gadscape.game.content.store

import com.gadscape.game.item.CustomItemId
import com.gadscape.website.WebsiteService
import com.zenyte.game.GameInterface
import com.zenyte.game.item.Item
import com.zenyte.game.packet.out.IfSetPosition
import com.zenyte.game.world.entity.player.Player
import mgi.types.component.ComponentDefinitions

/** A reward the website has queued for the player (GET /api/game/claims/pending), shown in Claim purchases. */
data class SiteReward(val id: String?, val label: String, val amount: Int, val itemId: Int?)

/** What one player has picked on the store screen. */
class StoreSession {
    /** Amount in the cart by product. */
    val cart = IntArray(StoreProduct.all.size)
    var claimPage = 0
    /** The website's queued rewards; null = not known (loading, website down, or it can't list them). */
    var siteRewards: List<SiteReward>? = null
    var siteLoaded = false
    /** This week's total from the website; null = not known (the server's own count is used). */
    var siteCents: Int? = null
}

/**
 * The in-game store (::donate; interface 5265, editor utils.StorePatch): the cart for the website's store page, the
 * claim list (website purchases and [PendingRewards]), the weekly offer and the promotion checkpoints ([StoreWeek]).
 * Nothing is sold in game: Checkout and Open store website open the signed store link (WebsiteService.openStore).
 */
object StoreScreen {
    private const val SESSION_KEY = "gadscape store"
    private const val MAX_QTY = 99
    private const val ITEM_ZOOM = 100
    private const val SMALL_ITEM_ZOOM = 150
    private const val ROW_TEXT_CHARS = 24

    /** The cache has the screen (the server keeps the plain ::donate link otherwise). */
    @JvmStatic
    val available: Boolean get() = ComponentDefinitions.containsInterface(StoreUi.ID)

    private fun session(player: Player): StoreSession =
        player.temporaryAttributes.getOrPut(SESSION_KEY) { StoreSession() } as StoreSession

    private fun d(player: Player) = player.packetDispatcher

    /** ::donate */
    @JvmStatic
    fun open(player: Player) {
        if (player.isLocked) {
            player.sendMessage("You can't do that right now.")
            return
        }
        GameInterface.STORE.open(player)
    }

    /** The interface was sent: draws it, then asks the website for the claim list and the week's total. */
    fun opened(player: Player) {
        val s = session(player)
        s.siteLoaded = false
        d(player).sendClientScript(StoreUi.STEELBORDER_SCRIPT, (StoreUi.ID shl 16) or StoreUi.FRAME, "GadScape Store - ${player.name}")
        renderProducts(player)
        render(player)
        refresh(player)
    }

    /** Asks the website again (after a claim, or when the screen opens) and redraws. */
    fun refresh(player: Player) {
        val s = session(player)
        WebsiteService.storeClaimList(player) { list ->
            s.siteRewards = list
            s.siteLoaded = true
            if (visible(player)) render(player)
        }
        WebsiteService.storeWeekCents(player) { cents ->
            s.siteCents = cents
            StoreWeek.update(player, StoreWeek.total(player, cents))
            if (visible(player)) render(player)
        }
    }

    private fun visible(player: Player) = player.interfaceHandler.isVisible(StoreUi.ID)

    private fun dollars(cents: Int) = "\$" + "%d.%02d".format(cents / 100, cents % 100)

    /** The tiles' items and prices (they don't change while the screen is open). */
    private fun renderProducts(player: Player) {
        val d = d(player)
        for ((k, p) in StoreProduct.all.withIndex()) {
            val tile = StoreUi.tile(k)
            d.sendComponentItem(StoreUi.ID, tile + StoreUi.TILE_ITEM, p.itemId, ITEM_ZOOM)
            d.sendComponentText(StoreUi.ID, tile + StoreUi.TILE_PRICE, "\$${p.cents / 100}")
        }
    }

    /** Everything that changes: amounts, cart, claim list, offer, checkpoints. */
    fun render(player: Player) {
        val s = session(player)
        val d = d(player)
        // ---- tiles and cart
        for (k in StoreProduct.all.indices) d.sendComponentText(StoreUi.ID, StoreUi.tile(k) + StoreUi.TILE_QTY, s.cart[k].toString())
        val lines = StoreProduct.all.withIndex().filter { s.cart[it.index] > 0 }.map { (k, p) -> "${s.cart[k]} x ${p.itemName}" }
        for (i in 0 until StoreUi.CART_LINES) {
            d.sendComponentText(StoreUi.ID, StoreUi.CART_LINE_FIRST + i, when {
                lines.isEmpty() && i == 0 -> "<col=9f9f9f>Your cart is empty.</col>"
                else -> lines.getOrNull(i) ?: ""
            })
        }
        val subtotal = subtotalCents(s)
        d.sendComponentText(StoreUi.ID, StoreUi.SUBTOTAL, "Subtotal: <col=00ff00>${dollars(subtotal)}</col>")
        d.sendComponentText(StoreUi.ID, StoreUi.CHECKOUT_LABEL, "Checkout (${dollars(subtotal)})")

        renderClaims(player, s)

        // ---- weekly offer
        // the website's total; until it answered, the last one known this week (or what this server saw)
        val total = s.siteCents ?: StoreWeek.lastCents(player.username)
        val offer = StoreWeek.offer
        d.sendComponentText(StoreUi.ID, StoreUi.OFFER_TEXT, StoreWeek.offerText())
        d.sendComponentText(StoreUi.ID, StoreUi.OFFER_PROGRESS, if (offer.rewardItemId >= 0 && offer.everyCents > 0) {
            val earned = total / offer.everyCents
            val next = (earned + 1) * offer.everyCents
            "${dollars(total)} paid - $earned earned, next at ${dollars(next)}"
        } else "Paid this week: ${dollars(total)}")
        d.sendComponentText(StoreUi.ID, StoreUi.OFFER_ENDS, StoreWeek.endsIn())

        // ---- promotion checkpoints
        d.sendComponentItem(StoreUi.ID, StoreUi.PIN_ITEM, CustomItemId.DONATOR_PIN_1, ITEM_ZOOM)
        d.sendComponentText(StoreUi.ID, StoreUi.PIN_AMOUNT, (total / 100).toString())
        d.sendComponentText(StoreUi.ID, StoreUi.PIN_DOLLARS, "\$${total / 100}")
        player.send(IfSetPosition(StoreUi.ID, StoreUi.TRACK_COVER, fillWidth(total), 0))
        for ((m, cp) in StoreCheckpoints.all.withIndex().take(StoreUi.MARKS)) {
            val base = StoreUi.mark(m)
            val reached = total >= cp.cents
            d.sendComponentVisibility(StoreUi.ID, base + StoreUi.MARK_TICK, reached)
            d.sendComponentVisibility(StoreUi.ID, base + StoreUi.MARK_TICK_REACHED, !reached)
            d.sendComponentText(StoreUi.ID, base + StoreUi.MARK_LABEL, (if (reached) "<col=00ff00>" else "") + "\$${cp.cents / 100}")
            val icons = intArrayOf(StoreUi.MARK_ICON0, StoreUi.MARK_ICON1)
            val amounts = intArrayOf(StoreUi.MARK_AMOUNT0, StoreUi.MARK_AMOUNT1)
            for (j in 0 until 2) {
                val item = cp.rewards.getOrNull(j)
                // the patch shows a second icon only on the marks with two rewards
                if (item == null) {
                    if (j == 1) {
                        d.sendComponentVisibility(StoreUi.ID, base + icons[j], true)
                        d.sendComponentVisibility(StoreUi.ID, base + amounts[j], true)
                    }
                    continue
                }
                d.sendComponentItem(StoreUi.ID, base + icons[j], item.id, ITEM_ZOOM)
                d.sendComponentText(StoreUi.ID, base + amounts[j], (if (reached) "<col=00ff00>" else "") + item.amount)
            }
        }
    }

    /** The gold fill's width: the four marks split the track in equal parts ($0-25, 25-50, 50-100, 100-250). */
    private fun fillWidth(cents: Int): Int {
        val part = StoreUi.TRACK_W.toDouble() / StoreUi.MARKS
        var from = 0
        for ((m, cp) in StoreCheckpoints.all.withIndex().take(StoreUi.MARKS)) {
            if (cents < cp.cents) return (m * part + part * (cents - from) / (cp.cents - from)).toInt().coerceIn(0, StoreUi.TRACK_W)
            from = cp.cents
        }
        return StoreUi.TRACK_W
    }

    private fun subtotalCents(s: StoreSession): Int = StoreProduct.all.withIndex().sumOf { (k, p) -> s.cart[k] * p.cents }

    /** One line of the claim list: a website reward ([pendingId] null) or one of [PendingRewards]. */
    private class ClaimRow(val itemId: Int?, val text: String, val pendingId: String?)

    private fun rows(player: Player, s: StoreSession): List<ClaimRow> {
        val rows = ArrayList<ClaimRow>()
        if (WebsiteService.isEnabled) {
            val site = s.siteRewards
            when {
                !s.siteLoaded -> rows += ClaimRow(null, "<col=9f9f9f>Checking the website...</col>", null)
                // the website can't list its queue: one row claims it all (like ::claim)
                site == null -> rows += ClaimRow(CustomItemId.DONATOR_PIN_1, "Website purchases", null)
                else -> for (r in site) rows += ClaimRow(r.itemId, (if (r.amount > 1) "${r.amount} x " else "") + r.label, null)
            }
        }
        for (r in PendingRewards.list(player.username)) rows += ClaimRow(r.itemId, "${r.amount} x ${Item(r.itemId).name}", r.id)
        return rows
    }

    private fun renderClaims(player: Player, s: StoreSession) {
        val d = d(player)
        val rows = rows(player, s)
        val pages = maxOf(1, (rows.size + StoreUi.CLAIM_ROWS - 1) / StoreUi.CLAIM_ROWS)
        s.claimPage = s.claimPage.coerceIn(0, pages - 1)
        d.sendComponentVisibility(StoreUi.ID, StoreUi.CLAIM_EMPTY, rows.isNotEmpty())
        d.sendComponentVisibility(StoreUi.ID, StoreUi.CLAIM_PREV, s.claimPage <= 0)
        d.sendComponentVisibility(StoreUi.ID, StoreUi.CLAIM_NEXT, s.claimPage >= pages - 1)
        d.sendComponentText(StoreUi.ID, StoreUi.CLAIM_PAGE, if (pages > 1) "${s.claimPage + 1}/$pages" else "")
        for (r in 0 until StoreUi.CLAIM_ROWS) {
            val base = StoreUi.row(r)
            val row = rows.getOrNull(s.claimPage * StoreUi.CLAIM_ROWS + r)
            d.sendComponentVisibility(StoreUi.ID, base, row == null)
            if (row == null) continue
            d.sendComponentVisibility(StoreUi.ID, base + StoreUi.ROW_ITEM, row.itemId == null)
            if (row.itemId != null) d.sendComponentItem(StoreUi.ID, base + StoreUi.ROW_ITEM, row.itemId, SMALL_ITEM_ZOOM)
            d.sendComponentText(StoreUi.ID, base + StoreUi.ROW_TEXT, shorten(row.text))
            // "Checking the website..." has nothing to claim yet
            d.sendComponentVisibility(StoreUi.ID, base + StoreUi.ROW_CLAIM, row.pendingId == null && WebsiteService.isEnabled && !s.siteLoaded)
        }
    }

    private fun shorten(text: String) = if (text.contains('<') || text.length <= ROW_TEXT_CHARS) text else text.take(ROW_TEXT_CHARS - 2) + ".."

    /** A click on the store screen. */
    fun click(player: Player, component: Int) {
        val s = session(player)
        val tileOffset = component - StoreUi.TILE_FIRST
        if (tileOffset >= 0 && tileOffset < StoreUi.PRODUCTS * StoreUi.TILE_STRIDE) {
            val k = tileOffset / StoreUi.TILE_STRIDE
            if (k >= s.cart.size) return
            when (tileOffset % StoreUi.TILE_STRIDE) {
                StoreUi.TILE_MINUS -> s.cart[k] = (s.cart[k] - 1).coerceAtLeast(0)
                StoreUi.TILE_PLUS -> s.cart[k] = (s.cart[k] + 1).coerceAtMost(MAX_QTY)
                else -> return
            }
            render(player)
            return
        }
        val rowOffset = component - StoreUi.ROW_FIRST
        if (rowOffset >= 0 && rowOffset < StoreUi.CLAIM_ROWS * StoreUi.ROW_STRIDE) {
            if (rowOffset % StoreUi.ROW_STRIDE != StoreUi.ROW_CLAIM) return
            val row = rows(player, s).getOrNull(s.claimPage * StoreUi.CLAIM_ROWS + rowOffset / StoreUi.ROW_STRIDE) ?: return
            claim(player, s, row)
            return
        }
        when (component) {
            StoreUi.CHECKOUT -> checkout(player, s)
            StoreUi.WEBSITE -> WebsiteService.openStore(player, null)
            StoreUi.CLAIM_PREV -> { s.claimPage--; render(player) }
            StoreUi.CLAIM_NEXT -> { s.claimPage++; render(player) }
        }
    }

    private fun claim(player: Player, s: StoreSession, row: ClaimRow) {
        val pendingId = row.pendingId
        if (pendingId != null) {
            PendingRewards.claim(player, pendingId)
            render(player)
            return
        }
        if (!WebsiteService.isEnabled || !s.siteLoaded) return
        // website rewards go out as one ::claim batch (journalled and confirmed, never twice); the screen is refreshed after it
        WebsiteService.commandClaim(player)
    }

    /** Opens the store page with the cart (cart=pin1:2,bond10:1). */
    private fun checkout(player: Player, s: StoreSession) {
        val cart = StoreProduct.all.withIndex().filter { s.cart[it.index] > 0 }.joinToString(",") { (k, p) -> "${p.key}:${s.cart[k]}" }
        if (cart.isEmpty()) {
            player.sendMessage("Add something to your cart first - or use Open store website.")
            return
        }
        WebsiteService.openStore(player, cart)
    }

    /** WebsiteService finished a ::claim (anything given or not): the claim list and week total change. */
    @JvmStatic
    fun afterWebsiteClaim(player: Player) {
        if (visible(player)) refresh(player) else StoreWeek.check(player)
    }
}
