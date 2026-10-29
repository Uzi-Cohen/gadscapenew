package utils

import com.displee.cache.CacheLibrary
import utils.gambling.If3
import utils.gambling.If3.FONT_BOLD
import utils.gambling.If3.FONT_REGULAR
import utils.gambling.If3.FONT_SMALL

/**
 * The in-game store (::donate), interface 5265, 600x390 with the client's steel border (vanilla script 227, as the
 * party / giveaway screens use it). The server's copy of the ids is server/core/.../store/StoreUi.kt; [Ids] is the same
 * list and [layout] checks it (`java -cp <editor jar> utils.StorePatch --server-ids` prints them).
 *
 * - Purchase options (left, 196x222): Donator pin $1 and the $10-$1000 bonds, 2 columns: item (the server sets it),
 *   price, - / amount / + buttons.
 * - Shopping cart (middle, 170x222): 7 lines "N x name", the subtotal, "Pay by card or PayPal", Checkout ($X.00)
 *   (921-928 border over sprite 897) and Open store website.
 * - Claim purchases (right top, 206x110): 4 rows of waiting rewards (item, text, Claim), < page >.
 * - Weekly offer (right bottom, 206x108): the offer text, this week's progress, "Ends in".
 * - Promotion checkpoints (bottom, 580x116): the week's total by a Donator pin, a dark track with a gold fill (the
 *   server moves the dark cover off the filled part), marks at $25 / $50 / $100 / $250 with their reward items above.
 *
 * Only vanilla graphics and scripts: buttons are the 1141-1149 frames, the big button the 921-928 border. No sprites
 * are added (21700-21799 stay free for later art). Interfaces 5266-5269 are kept for later store screens.
 *
 * Idempotent like the other patches: the interface is written only when what the cache has differs.
 *
 *     java -cp <editor fat jar> utils.StorePatch <cache folder>
 */
object StorePatch {
    private const val INTERFACES = 3

    const val INTERFACE_ID = 5265
    const val W = 600
    const val H = 390

    private const val ORANGE = 0xFF981F
    private const val WHITE = 0xFFFFFF
    private const val GREY = 0x9F9F9F
    private const val FOOTER_GREY = 0xC8C8C8
    private const val YELLOW = 0xFFFF00
    private const val GREEN = 0x00FF00
    private const val CYAN = 0x00FFFF
    private const val BOX = 0x5A5245
    private const val PANEL_FILL = 0x000000
    private const val PANEL_FILL_OPACITY = 190
    private const val GOLD = 0xD4AF37
    private const val TRACK = 0x14110E
    private const val TICK = 0x7A6F5F

    /** Vanilla script 227: steel border, title, close button. */
    private const val STEELBORDER = 227
    /** The vanilla nine-part button frame (top left, top, top right, left, middle, right, bottom left, bottom, bottom right). */
    private const val BUTTON = 1141
    /** 921-928: corners top left, top right, bottom left, bottom right, then the left, top, right and bottom edges. */
    private const val BORDER = 921
    /** The big button's fill, under the 921-928 border. */
    private const val BIG_BUTTON_FILL = 897
    private const val CORNER = 6

    const val PRODUCTS = 7
    const val CART_LINES = 7
    const val CLAIM_ROWS = 4
    const val MARKS = 4
    /** Reward items above each mark ($25, $50, $100, $250): one is centred on the mark, two sit either side. */
    private val MARK_ICONS = intArrayOf(1, 1, 2, 2)

    /** The track: x, width; the marks sit at the ends of four equal parts. */
    private const val TRACK_X = 70
    private const val TRACK_Y = 62
    const val TRACK_W = 460
    private const val TRACK_H = 10

    /** Component ids, as the server's StoreUi has them. */
    object Ids {
        const val ROOT = 0
        const val FRAME = 1
        const val PURCHASE = 2
        const val TILE_FIRST = 6
        const val TILE_STRIDE = 35
        const val TILE_ITEM = 10
        const val TILE_PRICE = 11
        const val TILE_MINUS = 12
        const val TILE_QTY = 23
        const val TILE_PLUS = 24
        const val CART = TILE_FIRST + PRODUCTS * TILE_STRIDE // 251
        const val CART_LINE_FIRST = CART + 4 // 255
        const val SUBTOTAL = CART_LINE_FIRST + CART_LINES // 262
        const val PAY_TEXT = SUBTOTAL + 1 // 263
        const val CHECKOUT = PAY_TEXT + 1 // 264
        const val CHECKOUT_LABEL = CHECKOUT + 10 // 274
        const val WEBSITE = CHECKOUT + 11 // 275
        const val CLAIM = WEBSITE + 11 // 286
        const val CLAIM_PREV = CLAIM + 4 // 290
        const val CLAIM_PAGE = CLAIM_PREV + 1 // 291
        const val CLAIM_NEXT = CLAIM_PAGE + 1 // 292
        const val CLAIM_EMPTY = CLAIM_NEXT + 1 // 293
        const val ROW_FIRST = CLAIM_EMPTY + 1 // 294
        const val ROW_STRIDE = 14
        const val ROW_ITEM = 1
        const val ROW_TEXT = 2
        const val ROW_CLAIM = 3
        const val OFFER = ROW_FIRST + CLAIM_ROWS * ROW_STRIDE // 350
        const val OFFER_TEXT = OFFER + 4 // 354
        const val OFFER_PROGRESS = OFFER_TEXT + 1 // 355
        const val OFFER_ENDS = OFFER_PROGRESS + 1 // 356
        const val PROMO = OFFER_ENDS + 1 // 357
        const val PIN_ITEM = PROMO + 4 // 361
        const val PIN_AMOUNT = PIN_ITEM + 1 // 362
        const val PIN_DOLLARS = PIN_AMOUNT + 1 // 363
        const val PIN_CAPTION = PIN_DOLLARS + 1 // 364
        const val TRACK = PIN_CAPTION + 1 // 365
        const val TRACK_FILL = TRACK + 1 // 366
        const val TRACK_COVER = TRACK_FILL + 1 // 367
        const val MARK_FIRST = TRACK_COVER + 1 // 368
        const val MARK_STRIDE = 7
        const val MARK_TICK = 0
        const val MARK_TICK_REACHED = 1
        const val MARK_LABEL = 2
        const val MARK_ICON0 = 3
        const val MARK_AMOUNT0 = 4
        const val MARK_ICON1 = 5
        const val MARK_AMOUNT1 = 6
        const val FOOTER = MARK_FIRST + MARKS * MARK_STRIDE // 396
        const val COUNT = FOOTER + 1
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 1 && args[0] == "--server-ids") {
            println(serverIds())
            return
        }
        if (args.size != 1) {
            println("usage: java -cp <editor jar> utils.StorePatch <build 241 cache folder>")
            kotlin.system.exitProcess(2)
        }
        val lib = CacheLibrary.create(args[0])
        try {
            println(apply(lib) ?: "Nothing to add: the cache already has the store screen.")
        } finally {
            lib.close()
        }
    }

    /** Adds or updates interface 5265 in [lib] (a build 241 cache). What was done, or null when nothing was. */
    fun apply(lib: CacheLibrary): String? {
        val comps = layout().encode()
        val archive = try { lib.index(INTERFACES).archive(INTERFACE_ID) } catch (e: Exception) { null }
        val existing = archive?.let { a -> a.fileIds().associateWith { a.file(it)?.data } }
        if (existing != null && existing.size == comps.size && comps.indices.all { existing[it]?.contentEquals(comps[it]) == true }) return null
        if (existing != null) lib.remove(INTERFACES, INTERFACE_ID)
        for ((i, c) in comps.withIndex()) lib.put(INTERFACES, INTERFACE_ID, i, c)
        lib.index(INTERFACES).update()
        return "Store: added or updated the store screen (interface $INTERFACE_ID)."
    }

    /** The screen: see the class comment. Component order is fixed by [Ids]; the requires keep it so. */
    fun layout(): If3.Builder {
        val b = If3.Builder(INTERFACE_ID)
        val root = b.layer(-1, 0, 0, W, H, "root") { xMode = 1; yMode = 1 }
        b.layer(root, 0, 0, 0, 0, "frame") {
            widthMode = 1; heightMode = 1
            // the server runs 227 again with "GadScape Store - <name>" when it opens the screen
            hooks["onLoad"] = listOf(STEELBORDER, If3.EVENT_COMPONENT, "GadScape Store")
        }
        require(root == Ids.ROOT && b["frame"] == Ids.FRAME)

        // ---- purchase options
        val purchase = b.panel(root, 8, 32, 196, 222, "Purchase options", "purchase", centred = true)
        require(purchase == Ids.PURCHASE)
        for (k in 0 until PRODUCTS) {
            val tile = b.layer(purchase, 4 + (k % 2) * 96, 22 + (k / 2) * 50, 92, 48, "tile$k")
            require(tile == Ids.TILE_FIRST + k * Ids.TILE_STRIDE)
            b.nineSlice(tile, 92, 48)
            val item = b.model(tile, 4, 8, 36, 32, "tile${k}_item")
            require(item == tile + Ids.TILE_ITEM)
            b.text(tile, 40, 4, 50, 16, "", FONT_BOLD, YELLOW, xAlign = 1, name = "tile${k}_price")
            val minus = b.smallButton(tile, 42, 26, 16, 16, "-", ORANGE, "Remove", "tile${k}_minus")
            require(minus == tile + Ids.TILE_MINUS)
            val qty = b.text(tile, 58, 26, 16, 16, "0", FONT_SMALL, WHITE, xAlign = 1, name = "tile${k}_qty")
            require(qty == tile + Ids.TILE_QTY)
            val plus = b.smallButton(tile, 74, 26, 16, 16, "+", ORANGE, "Add", "tile${k}_plus")
            require(plus == tile + Ids.TILE_PLUS)
        }

        // ---- shopping cart
        val cart = b.panel(root, 208, 32, 170, 222, "Shopping cart", "cart", centred = true)
        require(cart == Ids.CART)
        for (i in 0 until CART_LINES) {
            val line = b.text(cart, 8, 22 + i * 14, 154, 14, if (i == 0) "Your cart is empty." else "", FONT_SMALL, WHITE, name = "cart_line$i")
            require(line == Ids.CART_LINE_FIRST + i)
        }
        require(b.text(cart, 8, 124, 154, 16, "Subtotal: <col=00ff00>$0.00</col>", FONT_REGULAR, ORANGE, name = "subtotal") == Ids.SUBTOTAL)
        require(b.text(cart, 8, 140, 154, 24, "Pay by card or PayPal on gadscape.com", FONT_SMALL, GREY, xAlign = 1, name = "pay_text") == Ids.PAY_TEXT)
        require(b.bigButton(cart, 8, 166, 154, 28, "Checkout ($0.00)", GREEN, "Checkout", "checkout") == Ids.CHECKOUT)
        require(b.smallButton(cart, 8, 198, 154, 20, "Open store website", CYAN, "Open store website", "website") == Ids.WEBSITE)

        // ---- claim purchases
        val claim = b.panel(root, 382, 32, 206, 110, "Claim purchases", "claim", centred = false)
        require(claim == Ids.CLAIM)
        require(b.text(claim, 146, 2, 16, 18, "<", FONT_BOLD, ORANGE, xAlign = 1, name = "claim_prev") { hidden = true; ops.add("Previous page") } == Ids.CLAIM_PREV)
        require(b.text(claim, 162, 2, 26, 18, "", FONT_SMALL, ORANGE, xAlign = 1, name = "claim_page") == Ids.CLAIM_PAGE)
        require(b.text(claim, 188, 2, 16, 18, ">", FONT_BOLD, ORANGE, xAlign = 1, name = "claim_next") { hidden = true; ops.add("Next page") } == Ids.CLAIM_NEXT)
        require(b.text(claim, 6, 20, 194, 86, "Nothing to claim right now.", FONT_SMALL, GREY, xAlign = 1, name = "claim_empty") == Ids.CLAIM_EMPTY)
        for (r in 0 until CLAIM_ROWS) {
            val row = b.layer(claim, 4, 22 + r * 21, 198, 20, "row$r") { hidden = true }
            require(row == Ids.ROW_FIRST + r * Ids.ROW_STRIDE)
            require(b.model(row, 0, 0, 24, 20, "row${r}_item") == row + Ids.ROW_ITEM)
            require(b.text(row, 26, 0, 122, 20, "", FONT_SMALL, WHITE, name = "row${r}_text") == row + Ids.ROW_TEXT)
            require(b.smallButton(row, 150, 2, 46, 16, "Claim", GREEN, "Claim", "row${r}_claim") == row + Ids.ROW_CLAIM)
        }

        // ---- weekly offer
        val offer = b.panel(root, 382, 146, 206, 108, "Weekly offer", "offer", centred = true)
        require(offer == Ids.OFFER)
        require(b.text(offer, 6, 20, 194, 44, "", FONT_REGULAR, WHITE, xAlign = 1, name = "offer_text") == Ids.OFFER_TEXT)
        require(b.text(offer, 6, 64, 194, 16, "", FONT_SMALL, ORANGE, xAlign = 1, name = "offer_progress") == Ids.OFFER_PROGRESS)
        require(b.text(offer, 6, 84, 194, 18, "", FONT_BOLD, YELLOW, xAlign = 1, name = "offer_ends") == Ids.OFFER_ENDS)

        // ---- promotion checkpoints
        val promo = b.panel(root, 8, 258, 580, 116, "Promotion checkpoints", "promo", centred = false)
        require(promo == Ids.PROMO)
        require(b.model(promo, 14, 22, 36, 32, "pin_item") == Ids.PIN_ITEM)
        require(b.text(promo, 12, 20, 40, 12, "", FONT_SMALL, YELLOW, yAlign = 0, name = "pin_amount") == Ids.PIN_AMOUNT)
        require(b.text(promo, 2, 56, 60, 16, "$0", FONT_BOLD, GREEN, xAlign = 1, name = "pin_dollars") == Ids.PIN_DOLLARS)
        require(b.text(promo, 2, 72, 60, 14, "this week", FONT_SMALL, GREY, xAlign = 1, name = "pin_caption") == Ids.PIN_CAPTION)
        // the track clips its children: the gold fill under a dark cover the server moves to x = filled width
        val track = b.layer(promo, TRACK_X, TRACK_Y, TRACK_W, TRACK_H, "track")
        require(track == Ids.TRACK)
        require(b.rect(track, 0, 0, TRACK_W, TRACK_H, GOLD, 0, "track_fill") == Ids.TRACK_FILL)
        require(b.rect(track, 0, 0, TRACK_W, TRACK_H, TRACK, 0, "track_cover") == Ids.TRACK_COVER)
        val labels = listOf("$25", "$50", "$100", "$250")
        for (m in 0 until MARKS) {
            val x = TRACK_X + (m + 1) * TRACK_W / MARKS
            val base = Ids.MARK_FIRST + m * Ids.MARK_STRIDE
            require(b.rect(promo, x - 1, TRACK_Y - 4, 2, TRACK_H + 8, TICK, 0, "mark${m}_tick") == base + Ids.MARK_TICK)
            require(b.rect(promo, x - 1, TRACK_Y - 4, 2, TRACK_H + 8, GREEN, 0, "mark${m}_tick_reached") { hidden = true } == base + Ids.MARK_TICK_REACHED)
            require(b.text(promo, x - 30, TRACK_Y + 14, 60, 14, labels[m], FONT_BOLD, ORANGE, xAlign = 1, name = "mark${m}_label") == base + Ids.MARK_LABEL)
            val two = MARK_ICONS[m] == 2
            val x0 = if (two) x - 33 else x - 16
            require(b.model(promo, x0, 24, 32, 28, "mark${m}_icon0") == base + Ids.MARK_ICON0)
            require(b.text(promo, x0, 22, 32, 12, "", FONT_SMALL, YELLOW, yAlign = 0, name = "mark${m}_amount0") == base + Ids.MARK_AMOUNT0)
            require(b.model(promo, x + 1, 24, 32, 28, "mark${m}_icon1") { hidden = !two } == base + Ids.MARK_ICON1)
            require(b.text(promo, x + 1, 22, 32, 12, "", FONT_SMALL, YELLOW, yAlign = 0, name = "mark${m}_amount1") { hidden = !two } == base + Ids.MARK_AMOUNT1)
        }
        require(b.text(promo, 0, 96, 580, 16, "Counts money paid this week, not bonds redeemed. Rewards go to your claim list.",
            FONT_SMALL, FOOTER_GREY, xAlign = 1, name = "footer") == Ids.FOOTER)
        require(b.comps.size == Ids.COUNT)
        return b
    }

    // ------------------------------------------------------------------------------------------------ parts

    /** A graphic of a cache sprite by id, at the given size. */
    private fun If3.Builder.sprite(parent: Int, x: Int, y: Int, w: Int, h: Int, sprite: Int, tiled: Boolean = false): Int {
        val c = If3.Comp(comps.size, If3.GRAPHIC, parent)
        c.x = x; c.y = y; c.width = w; c.height = h; c.sprite = sprite; c.tiling = tiled
        comps.add(c)
        return c.id
    }

    /** The 1141-1149 frame filling a [w] x [h] parent (9 components). */
    private fun If3.Builder.nineSlice(parent: Int, w: Int, h: Int) {
        val c = CORNER
        sprite(parent, 0, 0, c, c, BUTTON)
        sprite(parent, c, 0, w - 2 * c, c, BUTTON + 1, true)
        sprite(parent, w - c, 0, c, c, BUTTON + 2)
        sprite(parent, 0, c, c, h - 2 * c, BUTTON + 3, true)
        sprite(parent, c, c, w - 2 * c, h - 2 * c, BUTTON + 4, true)
        sprite(parent, w - c, c, c, h - 2 * c, BUTTON + 5, true)
        sprite(parent, 0, h - c, c, c, BUTTON + 6)
        sprite(parent, c, h - c, w - 2 * c, c, BUTTON + 7, true)
        sprite(parent, w - c, h - c, c, c, BUTTON + 8)
    }

    /** A panel: layer, dark see-through fill, outline, title (4 components). Returns the layer. */
    private fun If3.Builder.panel(parent: Int, x: Int, y: Int, w: Int, h: Int, title: String, name: String, centred: Boolean): Int {
        val layer = layer(parent, x, y, w, h, name)
        rect(layer, 0, 0, w, h, PANEL_FILL, PANEL_FILL_OPACITY)
        rect(layer, 0, 0, w, h, BOX, 0) { filled = false }
        text(layer, 6, 2, w - 12, 18, title, FONT_BOLD, ORANGE, xAlign = if (centred) 1 else 0)
        return layer
    }

    /** A small button: layer with [op], the 1141-1149 frame, the label (11 components). Returns the layer. */
    private fun If3.Builder.smallButton(parent: Int, x: Int, y: Int, w: Int, h: Int, label: String, color: Int, op: String, name: String): Int {
        val layer = layer(parent, x, y, w, h, name) { ops.add(op) }
        nineSlice(layer, w, h)
        text(layer, 0, 0, w, h, label, FONT_SMALL, color, xAlign = 1, yAlign = 1)
        return layer
    }

    /** A big button: layer with [op], sprite 897 filling it, the 921-928 border, the label (11 components). */
    private fun If3.Builder.bigButton(parent: Int, x: Int, y: Int, w: Int, h: Int, label: String, color: Int, op: String, name: String): Int {
        val layer = layer(parent, x, y, w, h, name) { ops.add(op) }
        sprite(layer, 0, 0, w, h, BIG_BUTTON_FILL, true)
        sprite(layer, 0, 0, 9, 9, BORDER)
        sprite(layer, w - 9, 0, 9, 9, BORDER + 1)
        sprite(layer, 0, h - 9, 9, 9, BORDER + 2)
        sprite(layer, w - 9, h - 9, 9, 9, BORDER + 3)
        sprite(layer, 0, 9, 9, h - 18, BORDER + 4, true)
        sprite(layer, 9, 0, w - 18, 9, BORDER + 5, true)
        sprite(layer, w - 9, 9, 9, h - 18, BORDER + 6, true)
        sprite(layer, 9, h - 9, w - 18, 9, BORDER + 7, true)
        text(layer, 0, 0, w, h, label, FONT_BOLD, color, xAlign = 1, yAlign = 1)
        return layer
    }

    /** The named component ids, for the server's StoreUi. */
    fun serverIds(): String {
        val b = layout()
        val sb = StringBuilder("Store ID=$INTERFACE_ID")
        for ((name, id) in b.names) sb.append(' ').append(name.uppercase()).append('=').append(id)
        return sb.append('\n').toString()
    }
}
