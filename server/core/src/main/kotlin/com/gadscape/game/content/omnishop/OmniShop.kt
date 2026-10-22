package com.gadscape.game.content.omnishop

import com.gadscape.network.rsprot.IfScriptTriggerRouter
import com.gadscape.network.rsprot.RsprotSession
import com.gadscape.network.rsprot.ScriptTrigger
import com.zenyte.game.model.ui.InterfacePosition
import com.zenyte.game.util.ItemUtil
import com.zenyte.game.world.entity.player.Player
import com.zenyte.plugins.dialogue.OptionsMenuD
import mgi.types.config.items.ItemDefinitions
import net.rsprot.protocol.common.game.outgoing.inv.InventoryObject
import net.rsprot.protocol.game.outgoing.inv.UpdateInvFull
import org.slf4j.LoggerFactory

/**
 * The omnishop service (E4): opens the stock `omnishop_main` (819) with a shop dbrow, keeps a per-player stock,
 * and sells through the client's buy trigger.
 *
 * Client contract [CACHE 2746, osrs-dumps 6e5033af]:
 * - open 819 as the main modal and `omnishop_side` (806) in the side panel, then run
 *   `omnishop_side_init(dbrow shop)` (7246) and `omnishop_main_init(dbrow shop, inv, dbrow previousShop)` (7140);
 *   the client reads names, costs, texts and buttons from the shop dbrow and redraws on every transmit of the shop
 *   inv and of the currency var;
 * - the stock count of a row is `inv_total(shop inv, row obj)`, so the server sends the shop inv (inv id from the
 *   definition, no component) with one slot per row that still has stock ([OmniShopRules.invContents]);
 * - Buy: IF_SCRIPT_TRIGGER crc 407137374 on 819:4 with (dbrow shop, int stockIndex, int quantityKey), quantity
 *   key per `enum_5844` (proc omnishop_main_buy, called from omnishop_main_list_item_op ops 2-5 and the four buy
 *   buttons);
 * - Examine: crc 3336794041 on 819:3 with (obj);
 * - request info: crc 1754529215 on 819:5 with (dbrow shop, int stockIndex), answered with
 *   `omnishop_main_info_update` (9772).
 *
 * Selling is not supported (the TFA shops have `omnishop_shop_allow_selling = 0`).
 *
 * Fallback: when [enabled] is false (or the player has no rsprot session) [open] shows the same shop as a
 * classic option menu with the same rows, prices, per-player stock, currency and buy quantities.
 *
 * Stock is per player: [open] takes the player's [OmniShopStockState] (keep it with the activity, e.g. the raid,
 * and drop it when the activity ends), or keeps one in the player's temporary attributes keyed by the shop dbrow.
 */
object OmniShop {

    private val log = LoggerFactory.getLogger(OmniShop::class.java)

    const val INTERFACE_MAIN = 819
    const val INTERFACE_SIDE = 806
    const val COMPONENT_TRIGGER_EXAMINE = 3
    const val COMPONENT_TRIGGER_BUY = 4
    const val COMPONENT_TRIGGER_REQUEST_INFO = 5

    /** `omnishop_main_init(dbrow, inv, dbrow)` [CACHE 7140]. */
    const val SCRIPT_MAIN_INIT = 7140
    /** `omnishop_side_init(dbrow)` [CACHE 7246]. */
    const val SCRIPT_SIDE_INIT = 7246
    /** `omnishop_main_info_update(dbrow, inv, int, int, boolean, boolean, string)` [CACHE 9772]. */
    const val SCRIPT_INFO_UPDATE = 9772

    const val CRC_BUY = 407137374
    const val CRC_EXAMINE = -958173255 // 3336794041
    const val CRC_REQUEST_INFO = 1754529215

    /** Feature flag: false = classic option-menu fallback for every omnishop. */
    @JvmStatic
    @Volatile
    var enabled: Boolean = java.lang.Boolean.parseBoolean(System.getProperty("gadscape.omnishop.enabled", "true"))

    private const val SESSION = "omnishop_session"
    private const val STOCK_PREFIX = "omnishop_stock_"
    private const val LAST_SHOP = "omnishop_last_shop"

    /** One open shop. */
    class Session internal constructor(val definition: OmniShopDefinition, val stock: OmniShopStockState, val classic: Boolean)

    @Volatile
    private var registered = false

    /** Registers the omnishop triggers with [IfScriptTriggerRouter] (idempotent; [open] calls it). */
    @JvmStatic
    fun registerTriggers() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            IfScriptTriggerRouter.register(CRC_BUY, "dbrow int int", INTERFACE_MAIN, COMPONENT_TRIGGER_BUY) { player, trigger ->
                onBuy(player, trigger)
            }
            IfScriptTriggerRouter.register(CRC_EXAMINE, "obj", INTERFACE_MAIN, COMPONENT_TRIGGER_EXAMINE) { player, trigger ->
                onExamine(player, trigger)
            }
            IfScriptTriggerRouter.register(CRC_REQUEST_INFO, "dbrow int", INTERFACE_MAIN, COMPONENT_TRIGGER_REQUEST_INFO) { player, trigger ->
                onRequestInfo(player, trigger)
            }
            registered = true
        }
    }

    // ------------------------------------------------------------------ open / close

    /** Opens [definition] for [player] with the per-player stock kept in the player's temporary attributes. */
    @JvmStatic
    fun open(player: Player, definition: OmniShopDefinition) = open(player, definition, stockState(player, definition))

    /** Opens [definition] for [player] with [stock] (the caller keeps it, e.g. per raid). */
    @JvmStatic
    fun open(player: Player, definition: OmniShopDefinition, stock: OmniShopStockState) {
        registerTriggers()
        require(stock.size == definition.stock.size) { "stock state for ${definition.shopDbrow} has ${stock.size} rows" }
        if (!enabled || RsprotSession.of(player) == null) {
            openClassic(player, definition, stock)
            return
        }
        val previous = player.temporaryAttributes[LAST_SHOP] as? Int ?: -1
        player.interfaceHandler.closeInterfaces()
        sendInv(player, definition, stock)
        player.interfaceHandler.sendInterface(InterfacePosition.CENTRAL, INTERFACE_MAIN)
        player.interfaceHandler.sendInterface(InterfacePosition.SINGLE_TAB, INTERFACE_SIDE)
        player.packetDispatcher.sendClientScript(SCRIPT_SIDE_INIT, definition.shopDbrow)
        player.packetDispatcher.sendClientScript(SCRIPT_MAIN_INIT, definition.shopDbrow, definition.invId, previous)
        player.temporaryAttributes[SESSION] = Session(definition, stock, classic = false)
        player.temporaryAttributes[LAST_SHOP] = definition.shopDbrow
        player.setCloseInterfacesEvent { onClosed(player) }
    }

    /** The open shop session of [player], or null. */
    @JvmStatic
    fun session(player: Player): Session? = player.temporaryAttributes[SESSION] as? Session

    /** Closes the shop if [player] has one open. */
    @JvmStatic
    fun close(player: Player) {
        if (session(player) == null) return
        player.interfaceHandler.closeInterface(InterfacePosition.CENTRAL)
        onClosed(player)
    }

    private fun onClosed(player: Player) {
        val session = player.temporaryAttributes.remove(SESSION) as? Session ?: return
        if (!session.classic && player.interfaceHandler.isVisible(INTERFACE_SIDE)) {
            player.interfaceHandler.closeInterface(InterfacePosition.SINGLE_TAB)
        }
    }

    // ------------------------------------------------------------------ stock

    /** [player]'s stock for [definition] kept in temporary attributes (created full on first use). */
    @JvmStatic
    fun stockState(player: Player, definition: OmniShopDefinition): OmniShopStockState {
        val key = STOCK_PREFIX + definition.shopDbrow
        val existing = player.temporaryAttributes[key] as? OmniShopStockState
        if (existing != null && existing.size == definition.stock.size) return existing
        return OmniShopStockState(definition.initialStock()).also { player.temporaryAttributes[key] = it }
    }

    /** Refills [player]'s temporary-attribute stock for [definition] (and redraws an open shop). */
    @JvmStatic
    fun resetStock(player: Player, definition: OmniShopDefinition) {
        val state = stockState(player, definition)
        state.reset(definition)
        val session = session(player)
        if (session != null && session.stock === state && !session.classic) sendInv(player, definition, state)
    }

    /** Sends the shop inv (stock counts) to [player]. */
    @JvmStatic
    fun sendInv(player: Player, definition: OmniShopDefinition, stock: OmniShopStockState) {
        val contents = OmniShopRules.invContents(definition, stock.remaining)
        val capacity = definition.invCapacity
        val message = UpdateInvFull(definition.invId, capacity) { slot ->
            val entry = contents.getOrNull(slot)
            if (entry == null) InventoryObject.NULL else InventoryObject.pack(entry.first, entry.second)
        }
        RsprotSession.of(player)?.queue(message)
    }

    // ------------------------------------------------------------------ buying

    /**
     * Buys row [index] with button [quantityKey] for [player] from the open shop. Returns true if items were given.
     * Called by the buy trigger and by the classic fallback.
     */
    @JvmStatic
    fun buy(player: Player, session: Session, index: Int, quantityKey: Int): Boolean {
        val definition = session.definition
        val stock = session.stock
        val row = definition.row(index)
        val carry = if (row == null) 0 else carryLimit(player, row.itemId)
        val balance = definition.currency.balance(player)
        when (val result = OmniShopRules.evaluate(definition, stock.remaining, index, quantityKey, balance, carry)) {
            is OmniShopPurchase.Refused -> {
                if (result.forged) {
                    log.debug("{}: refused omnishop {} buy of row {} key {}", player, definition.shopDbrow, index, quantityKey)
                }
                result.message?.let { player.sendMessage(it) }
                return false
            }
            is OmniShopPurchase.Ok -> {
                definition.currency.remove(player, result.totalCost)
                stock.take(result.index, result.quantity)
                val added = player.inventory.addItem(result.row.itemId, result.quantity)
                val missing = result.quantity - (added?.succeededAmount ?: result.quantity)
                if (missing > 0) {
                    // should not happen (space was checked); never lose what was paid for
                    player.inventory.addOrDrop(result.row.itemId, missing)
                }
                if (result.partial) {
                    player.sendMessage("You could only buy ${result.quantity}.")
                }
                if (!session.classic) sendInv(player, definition, stock)
                return true
            }
        }
    }

    /** How many more of [itemId] fit in [player]'s inventory. */
    private fun carryLimit(player: Player, itemId: Int): Int {
        val stackable = try {
            ItemDefinitions.get(itemId)?.isStackable() ?: false
        } catch (e: Exception) {
            false
        }
        return if (stackable) {
            if (player.inventory.containsItem(itemId) || player.inventory.freeSlots > 0) Int.MAX_VALUE else 0
        } else {
            player.inventory.freeSlots
        }
    }

    private fun onBuy(player: Player, trigger: ScriptTrigger) {
        val session = session(player) ?: return
        if (session.classic || trigger.int(0) != session.definition.shopDbrow) {
            log.debug("{}: omnishop buy for shop {} but {} is open", player, trigger.int(0), session.definition.shopDbrow)
            return
        }
        buy(player, session, trigger.int(1), trigger.int(2))
    }

    private fun onExamine(player: Player, trigger: ScriptTrigger) {
        val session = session(player) ?: return
        val obj = trigger.int(0)
        if (session.definition.stock.none { it.itemId == obj }) return
        ItemUtil.sendItemExamine(player, obj)
    }

    private fun onRequestInfo(player: Player, trigger: ScriptTrigger) {
        val session = session(player) ?: return
        val definition = session.definition
        if (trigger.int(0) != definition.shopDbrow) return
        val index = trigger.int(1)
        val exists = if (definition.row(index) != null) 1 else 0
        // (dbrow, inv, index, exists, skip the request next time, boolean5 false, extra text "")
        player.packetDispatcher.sendClientScript(SCRIPT_INFO_UPDATE, definition.shopDbrow, definition.invId, index, exists, 1, 0, "")
    }

    // ------------------------------------------------------------------ classic fallback

    /** The option-menu shop: the same rows, prices, stock, currency and buy quantities as the omnishop. */
    @JvmStatic
    fun openClassic(player: Player, definition: OmniShopDefinition, stock: OmniShopStockState) {
        registerTriggers()
        val session = Session(definition, stock, classic = true)
        val rows = definition.stock.withIndex().filter { it.value.sellable }
        val balance = definition.currency.balance(player)
        val options = rows.map { (index, row) ->
            val unit = OmniShopRules.unitCost(definition, row)
            "${itemName(row.itemId)} - $unit ${definition.currency.name(unit)} (${stock.remaining[index]} left)"
        }
        val title = "${definition.title}: $balance ${definition.currency.name(balance)}"
        player.dialogueManager.start(object : OptionsMenuD(player, title, *options.toTypedArray()) {
            override fun handleClick(slotId: Int) {
                val (index, row) = rows.getOrNull(slotId) ?: return
                chooseQuantity(player, session, index, row)
            }

            override fun cancelOption(): Boolean = true
        })
    }

    private fun chooseQuantity(player: Player, session: Session, index: Int, row: OmniShopStock) {
        val definition = session.definition
        val keys = definition.quantityKeys.filter { OmniShopRules.quantityForKey(it) > 0 }
        val unit = OmniShopRules.unitCost(definition, row)
        val options = keys.map { key ->
            val quantity = OmniShopRules.quantityForKey(key)
            "Buy $quantity (${unit * quantity} ${definition.currency.name(unit * quantity)})"
        }
        player.dialogueManager.start(object : OptionsMenuD(player, itemName(row.itemId), *options.toTypedArray()) {
            override fun handleClick(slotId: Int) {
                val key = keys.getOrNull(slotId) ?: return
                buy(player, session, index, key)
                openClassic(player, definition, session.stock)
            }

            override fun cancelOption(): Boolean = true
        })
    }

    private fun itemName(itemId: Int): String = try {
        ItemDefinitions.get(itemId)?.name ?: "item $itemId"
    } catch (e: Exception) {
        "item $itemId"
    }
}

/** A player's remaining stock in one omnishop, one count per stock row. World thread only. */
class OmniShopStockState(initial: IntArray) {
    val remaining: IntArray = initial.copyOf()
    val size: Int get() = remaining.size

    fun take(index: Int, quantity: Int) {
        remaining[index] = (remaining[index] - quantity).coerceAtLeast(0)
    }

    fun reset(definition: OmniShopDefinition) {
        val full = definition.initialStock()
        for (i in remaining.indices) remaining[i] = full.getOrElse(i) { 0 }
    }

    override fun toString(): String = "OmniShopStockState(${remaining.contentToString()})"
}
