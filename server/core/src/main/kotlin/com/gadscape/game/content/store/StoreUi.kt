package com.gadscape.game.content.store

/**
 * Interface and component ids of the store screen, as the cache patch makes them (editor utils.StorePatch;
 * `java -cp <editor jar> utils.StorePatch --server-ids` prints them). A layout change in the patch must change these too.
 */
@Suppress("unused")
object StoreUi {
    const val ID = 5265

    /** The steel border layer (script 227 draws the title on it). */
    const val FRAME = 1
    /** Vanilla script 227: (component, title) steel border. */
    const val STEELBORDER_SCRIPT = 227

    const val PRODUCTS = 7
    /** Product tile k: its layer is TILE_FIRST + k * TILE_STRIDE, plus the offsets below. */
    const val TILE_FIRST = 6
    const val TILE_STRIDE = 35
    const val TILE_ITEM = 10
    const val TILE_PRICE = 11
    const val TILE_MINUS = 12
    const val TILE_QTY = 23
    const val TILE_PLUS = 24

    const val CART_LINES = 7
    const val CART_LINE_FIRST = 255
    const val SUBTOTAL = 262
    const val PAY_TEXT = 263
    const val CHECKOUT = 264
    const val CHECKOUT_LABEL = 274
    const val WEBSITE = 275

    const val CLAIM_PREV = 290
    const val CLAIM_PAGE = 291
    const val CLAIM_NEXT = 292
    const val CLAIM_EMPTY = 293
    const val CLAIM_ROWS = 4
    /** Claim row r: its layer is ROW_FIRST + r * ROW_STRIDE, plus the offsets below. */
    const val ROW_FIRST = 294
    const val ROW_STRIDE = 14
    const val ROW_ITEM = 1
    const val ROW_TEXT = 2
    const val ROW_CLAIM = 3

    const val OFFER_TEXT = 354
    const val OFFER_PROGRESS = 355
    const val OFFER_ENDS = 356

    const val PIN_ITEM = 361
    const val PIN_AMOUNT = 362
    const val PIN_DOLLARS = 363
    const val PIN_CAPTION = 364
    const val TRACK = 365
    const val TRACK_FILL = 366
    /** The dark cover over the gold fill: moved to x = the filled width. */
    const val TRACK_COVER = 367
    const val TRACK_W = 460
    const val MARKS = 4
    /** Mark m (the $25, $50, $100, $250 checkpoints): MARK_FIRST + m * MARK_STRIDE, plus the offsets below. */
    const val MARK_FIRST = 368
    const val MARK_STRIDE = 7
    const val MARK_TICK = 0
    const val MARK_TICK_REACHED = 1
    const val MARK_LABEL = 2
    const val MARK_ICON0 = 3
    const val MARK_AMOUNT0 = 4
    const val MARK_ICON1 = 5
    const val MARK_AMOUNT1 = 6
    const val FOOTER = 396

    fun tile(k: Int) = TILE_FIRST + k * TILE_STRIDE
    fun row(r: Int) = ROW_FIRST + r * ROW_STRIDE
    fun mark(m: Int) = MARK_FIRST + m * MARK_STRIDE
}
