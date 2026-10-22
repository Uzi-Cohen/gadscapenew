package com.gadscape.game.content

import com.gadscape.game.content.shop.ShopCurrencyHandler
import com.gadscape.game.content.shop.UniversalShopCategory
import com.gadscape.game.content.universalshop.UnivShopTable
import com.zenyte.game.item.Item
import com.zenyte.game.model.shop.ShopCurrency
import com.zenyte.game.world.entity.player.Player
import com.google.gson.GsonBuilder
import mgi.types.config.DBRowDefinition
import mgi.types.config.items.ItemDefinitions
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

object UniversalShop {
    @JvmStatic val priceOverrides = mutableMapOf<Pair<Int, Int>, Int>()
    @JvmStatic val disabledItems = mutableListOf<Int>()
    @JvmStatic val disabledCategory = mutableListOf<UniversalShopCategory>()
    private var cachedMappings = mutableMapOf<Int, Pair<Int, Int>>()

    private val logger = LoggerFactory.getLogger(UniversalShop::class.java)
    /** Changes made in game with ::usm, kept across restarts. */
    private val stateFile: Path = Path.of("data", "universal-shop.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var loaded = false

    private class PriceOverride(val category: Int, val row: Int, val price: Int)
    private class SavedState(
        val prices: List<PriceOverride> = emptyList(),
        val disabledItems: List<Int> = emptyList(),
        val disabledCategories: List<Int> = emptyList(),
    )


    val Melee = UniversalShopCategory(1, "universal_shop_tbl", 1001, 5, table = UnivShopTable.Melee)
    val Ranged = UniversalShopCategory(2, "universal_shop_ranged", 1008, 9, table = UnivShopTable.Ranged)
    val Magic = UniversalShopCategory(3, "universal_shop_magic", 1007, 13, table = UnivShopTable.Magic)
    val Supplies = UniversalShopCategory(4, "universal_shop_supplies", 1011, 17, table = UnivShopTable.Supplies)
    val Skilling = UniversalShopCategory(5, "universal_shop_skilling", 1010, 21, table = UnivShopTable.Skilling)
    val Jewelry = UniversalShopCategory(6, "universal_shop_teleports", 1012, 25, table = UnivShopTable.Jewelry)
    val General = UniversalShopCategory(7, "universal_shop_barrows", 1003, 29, table = UnivShopTable.General)
    val Slayer = UniversalShopCategory(8, "universal_shop_blessed", 1004, 33, table = UnivShopTable.Slayer)
    val BountyHunter = UniversalShopCategory(9, "universal_shop_bounty_hunter", 1005, 37, table = UnivShopTable.BountyHunter)
    val Capes = UniversalShopCategory(10, "universal_shop_capes", 1006, 41, table = UnivShopTable.Capes)
    val BloodMoney = UniversalShopCategory(11, "universal_shop_runesouls", 1009, 45, table = UnivShopTable.BloodMoney)
    val Loyalty = UniversalShopCategory(12, "universal_shop_untradeables", 1013,  49, table = UnivShopTable.Loyalty)
    val Vote = UniversalShopCategory(13, "universal_shop_vote", 1014, 53, table = UnivShopTable.Vote)


    val Categories = listOf(
        Melee,
        Ranged,
        Magic,
        Supplies,
        Skilling,
        Jewelry,
        General,
        Slayer,
        BountyHunter,
        Capes,
        BloodMoney,
        Loyalty,
        Vote
    )

    val categoriesToIds = mapOf(
        1 to 1001, //Melee
        2 to 1008, //Ranged
        3 to 1007, //Magic
        4 to 1011, //Supplies
        5 to 1010, //Skilling
        6 to 1012, //Teleports
        7 to 1003, //Barrows
        8 to 1004, //Blessed
        9 to 1005, //BountyHunter
        10 to 1006, //Capes
        11 to 1009, //Rune Souls
        12 to 1013, //Untradeables
        13 to 1014, //Vote
    )

    val categoriesToTable = mapOf(
        1 to Melee, //Melee
        2 to Ranged, //Ranged
        3 to Magic, //Magic
        4 to Supplies, //Supplies
        5 to Skilling, //Skilling
        6 to Jewelry, //Teleports
        7 to General, //Barrows
        8 to Slayer, //Blessed
        9 to BountyHunter, //BountyHunter
        10 to Capes, //Capes
        11 to BloodMoney, //Rune Souls
        12 to Loyalty, //Untradeables
        13 to Vote, //Vote
    )

    private fun shopIdToTableId(idx: Int): Int {
        return Categories.find { it.uniqueIndex == idx }?.tableId ?: 1001
    }

    fun getCategory(idx: Int) : UniversalShopCategory? {
        return categoriesToTable[idx]
    }

    fun getCategoryRowIndexPair(selectedCategory: Int, targetChildId: Int): Pair<Int, Int> {
        if (selectedCategory != 0 && selectedCategory != 1) {
            val size = categoriesToTable[selectedCategory]?.table?.items?.size ?: return 99 to 1
            if (targetChildId !in 0 until size) return 99 to 1
            return selectedCategory to targetChildId
        }
        // A click on a header/spacer child of the "all" view matches no category: 99 = do nothing.
        val category = Categories.firstOrNull { it.componentIds.contains(targetChildId) } ?: return 99 to 1
        val rowIndex = targetChildId - category.componentIds.first
        if (rowIndex !in 0 until category.table.items.size) return 99 to 1
        return category.uniqueIndex to rowIndex
    }

    fun attemptPurchaseMenu(player: Player, opIndex: Int, categoryId: Int, itemId: Int, price: Int) {
        if(disabledCategory.contains(categoriesToTable[categoryId])) {
            player.sendMessage("This shop is currently disabled. Please try again later.")
            return
        }
        val table = UnivShopTable.tables.first { it.category.index == categoryId }
        if(table.items.first { it.id == itemId }.ironmanRestricted && player.isIronman) {
            player.sendMessage("You cannot buy this item as an Ironman.")
            return
        }
        if(opIndex == 10) //Examine
            return
        val currency: ShopCurrency = getCategoryCurrency(categoryId)
        val availableCurrency = ShopCurrencyHandler.getAmount(currency, player)
        val quantity = when(opIndex) {
            2 -> 1
            3 -> 10
            6 -> 50
            else -> 1
        }
        val fullPrice: Long = price.toLong() * quantity
        val debit: Int
        if(fullPrice > Int.MAX_VALUE) {
            player.sendMessage("You cannot purchase that many of this item at once.")
            return
        } else {
            debit = fullPrice.toInt()
        }

        handlePurchase(player, debit, availableCurrency, itemId, quantity, price, currency)
    }

    fun attemptPurchaseDialogue(player: Player, quantity: Int, categoryId: Int, itemId: Int, price: Int, isNote: Boolean) {
        if(disabledCategory.contains(categoriesToTable[categoryId])) {
            player.sendMessage("This shop is currently disabled. Please try again later.")
            return
        }
        val table = UnivShopTable.tables.first { it.category.index == categoryId }
        if(table.items.first { it.id == itemId }.ironmanRestricted && player.isIronman) {
            player.sendMessage("You cannot buy this item as an Ironman.")
            return
        }
        val currency: ShopCurrency = getCategoryCurrency(categoryId)
        val availableCurrency = ShopCurrencyHandler.getAmount(currency, player)

        val fullPrice: Long = price.toLong() * quantity
        val debit: Int

        if(fullPrice > Int.MAX_VALUE || (price >= 1_000_000 && quantity >= 1_000)) {
            player.sendMessage("You cannot purchase that many of this item at once.")
            return
        } else {
            debit = fullPrice.toInt()
        }

        handlePurchase(player, debit, availableCurrency, itemId, quantity, price, currency, isNote)
    }

    private fun handlePurchase(player: Player, debit: Int, availableCurrency: Int, itemId: Int, quantity: Int, price: Int, currency: ShopCurrency, isNote: Boolean = false) {
        if(disabledItems.contains(itemId) || Item.itemBlacklist.contains(itemId)) {
            player.sendMessage("This item is currently disabled. Please check discord for updates.")
            return
        }
        if(debit > availableCurrency) {
            player.sendMessage("You do not have enough $currency to buy that many.")
            return
        } else if(isNote && ItemDefinitions.getOrThrow(Item.notedId(itemId)).isStackable()){
            val notedId = Item.notedId(itemId)
            // Pay only when the noted stack fits (an existing stack or a free slot); it used to be paid and then lost.
            if (!player.inventory.containsItem(notedId) && !player.inventory.hasFreeSlots()) {
                player.sendMessage("You don't have enough inventory space.")
                return
            }
            ShopCurrencyHandler.remove(currency, player, debit)
            player.inventory.addItem(notedId, quantity)
        } else if(ItemDefinitions.getOrThrow(itemId).isStackable()){
            if (!player.inventory.containsItem(itemId) && !player.inventory.hasFreeSlots()) {
                player.sendMessage("You don't have enough inventory space.")
                return
            }
            ShopCurrencyHandler.remove(currency, player, debit)
            player.inventory.addItem(itemId, quantity)
        } else if(player.inventory.checkSpace(quantity)) {
            ShopCurrencyHandler.remove(currency, player, debit)
            player.inventory.addItem(itemId, quantity)
        } else {
            val newQuantity = player.inventory.freeSlots
            if (newQuantity <= 0) {
                player.sendMessage("You don't have enough inventory space.")
                return
            }
            val newDebit = price * newQuantity
            ShopCurrencyHandler.remove(currency, player, newDebit)
            player.inventory.addItem(itemId, newQuantity)
            player.sendMessage("You purchase a total of $newQuantity items for $newDebit $currency")
            return
        }
        player.sendMessage("You purchase a total of $quantity items for $debit $currency")
    }

    fun getCategoryCurrency(categoryId: Int): ShopCurrency {
        return when (categoryId) {
            1, 2, 3, 4, 5, 6, 7, 10 -> ShopCurrency.COINS
            8 -> ShopCurrency.SLAYER_POINTS
            9 -> ShopCurrency.BH_POINTS
            11 -> ShopCurrency.BLOOD_MONEY
            12 -> ShopCurrency.LOYALTY_POINTS
            13 -> ShopCurrency.VOTE_POINTS
            else -> ShopCurrency.COINS
        }
    }

    /**
     * What [category] pays, in its own currency, for one [itemId], or -1 when it doesn't buy the item.
     *
     * A category doesn't buy an item that another category sells: otherwise the shop turns one currency into another
     * (the Slayer tab paid 275 slayer points for Darklight, 1688 coins in the Melee tab; 500 for the rune pouch and herb
     * sack, 35/24 vote points in the Vote tab; 5 for 11738, 1 vote point).
     */
    fun sellPrice(category: UniversalShopCategory, itemId: Int): Int {
        val row = category.table.items.firstOrNull { it.id == itemId } ?: return -1
        if (!row.canSell || soldByAnotherCategory(category, itemId)) return -1
        return row.sellPrice
    }

    private fun soldByAnotherCategory(category: UniversalShopCategory, itemId: Int): Boolean =
        Categories.any { other ->
            other.uniqueIndex != category.uniqueIndex && (
                other.table.items.any { it.id == itemId && it.canBuy } ||
                    // The purchase side reads the cache tables (1001-1014): check them too, in case they gain rows.
                    runCatching { DBRowDefinition.findItemOnTable(other.tableId, itemId) }.getOrDefault(false)
                )
        }


    fun defaultBuyLocation(itemId: Int): Pair<Int, Int> {
        if(cachedMappings.contains(itemId))
            return cachedMappings[itemId]!!
        val cat = Categories.find { DBRowDefinition.findItemOnTable(it.tableId, itemId) } ?:return 99 to 1
        return when(cat.uniqueIndex) {
            8,9,11,12,13 -> 100 to -1
            else -> cat.uniqueIndex to DBRowDefinition.getRowIndex(cat.tableId, itemId)
        }.also { cachedMappings[itemId] = it }
    }

    fun determinePrice(category: Int, item: Int): Int {
        if(priceOverrides.containsKey(category to item) && priceOverrides[category to item] != null) return priceOverrides[category to item]!!
        return DBRowDefinition.getRowColumnByIndexesInt(shopIdToTableId(category), item, 4)
    }

    fun populateCategories() {
        Categories.forEach { tbl -> tbl.itemCount = DBRowDefinition.getRowCount(tbl.tableId) }
        load()
    }

    /** Row of [itemId] in a category's table, or null when the category doesn't sell it. */
    fun rowOf(category: UniversalShopCategory, itemId: Int): Int? =
        if (DBRowDefinition.findItemOnTable(category.tableId, itemId)) DBRowDefinition.getRowIndex(category.tableId, itemId) else null

    private fun load() {
        if (loaded) return
        loaded = true
        if (!Files.exists(stateFile)) return
        try {
            val state = gson.fromJson(Files.readString(stateFile), SavedState::class.java) ?: return
            state.prices.forEach { priceOverrides[it.category to it.row] = it.price }
            disabledItems.addAll(state.disabledItems)
            disabledCategory.addAll(state.disabledCategories.mapNotNull { categoriesToTable[it] })
        } catch (e: Exception) {
            logger.error("Couldn't read {}", stateFile, e)
        }
    }

    fun save() {
        val state = SavedState(
            prices = priceOverrides.map { (key, price) -> PriceOverride(key.first, key.second, price) },
            disabledItems = disabledItems.toList(),
            disabledCategories = disabledCategory.map { it.uniqueIndex },
        )
        try {
            Files.createDirectories(stateFile.parent)
            Files.writeString(stateFile, gson.toJson(state))
        } catch (e: Exception) {
            logger.error("Couldn't save {}", stateFile, e)
        }
    }
}
