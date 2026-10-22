package com.zenyte.game.content.universalshop

import com.gadscape.api.service.vote.totalVoteCredits
import com.gadscape.game.content.UniversalShop
import com.gadscape.game.world.entity.player.bountyHunterPoints
import com.gadscape.game.world.entity.player.selectedUniversalShopCategory
import com.gadscape.game.world.entity.player.univShopDoubleProcess
import com.gadscape.game.world.entity.player.univShopSearchActive
import com.zenyte.GameToggles.UNIVERSAL_SHOP_FLOODGATE
import com.zenyte.game.GameInterface
import com.zenyte.game.item.Item
import com.zenyte.game.model.ui.Interface
import com.zenyte.game.util.AccessMask
import com.zenyte.game.util.Colour
import com.zenyte.game.world.entity.player.Player
import com.zenyte.plugins.Plugin
import mgi.types.config.DBRowDefinition

@Suppress("unused")
class UniversalShopInterface : Interface(), Plugin {
    companion object{
        /** Highest category-list child index given IF events (14 rows x 4 children, with room to spare). */
        private const val CATEGORY_CHILDREN = 63
        /** Highest stock-list child index given IF events (the "all" view: 587 rows + 13 x 4 header children + 4). */
        private const val STOCK_CHILDREN = 1023

        @JvmStatic fun openInterfaceToTab(player: Player, tab: Int) {
            if(!UNIVERSAL_SHOP_FLOODGATE) {
                player.sendMessage("Universal Shop has been toggled off. Please check discord for updates.")
                return
            }
            GameInterface.UNIVERSAL_SHOP.open(player)
            player.varManager.sendVarInstant(9998, tab)
            player.selectedUniversalShopCategory = tab
        }
    }


    override fun open(player: Player) {
        player.univShopSearchActive = false
        player.univShopDoubleProcess = false
        GameInterface.UNIVERSAL_SHOP_INVENTORY.open(player)
        super.open(player)
        // The category rows (5003:7) and stock rows (5003:12) are children made by client scripts 41009/41004/41016,
        // so they carry no cache op flags: without server IF events the 241 client never sends their clicks.
        // Categories: 4 children per row, "Select" (op1) on child 1 of each row. Stock: the "all" view holds every table
        // (587 rows) plus 4 header children per category; ops Value/Buy 1/Buy 10/Buy X/Buy X (note)/Buy 50 = 1-6,
        // Examine = 10 (script 41016).
        val dispatcher = player.packetDispatcher
        dispatcher.sendComponentSettings(id, getComponent("categories_list"), 0, CATEGORY_CHILDREN, AccessMask.CLICK_OP1)
        dispatcher.sendComponentSettings(
            id, getComponent("stock_list"), 0, STOCK_CHILDREN,
            AccessMask.CLICK_OP1, AccessMask.CLICK_OP2, AccessMask.CLICK_OP3, AccessMask.CLICK_OP4,
            AccessMask.CLICK_OP5, AccessMask.CLICK_OP6, AccessMask.CLICK_OP10
        )
    }

    override fun attach() {
        UniversalShop.populateCategories()
        put(3, "search_button")
        put(7, "categories_list")
        put(12, "stock_list")
    }

        override fun build() {
            bind("search_button") { player: Player, _: Int, _: Int, _: Int ->
                if(!player.univShopDoubleProcess) {
                    player.univShopSearchActive = !player.univShopSearchActive
                    player.univShopDoubleProcess = true
                } else {
                    player.univShopDoubleProcess = false
                }
            }
            bind("categories_list") { player: Player, slotId: Int, _: Int, _: Int ->
                player.selectedUniversalShopCategory = ((slotId - 1) / 4)
                player.univShopSearchActive = false
                player.varManager.sendVarInstant(9998, player.selectedUniversalShopCategory)
            }
            bind("stock_list") { player: Player, slotId: Int, interfaceItem: Int, option: Int ->
                val trueTarget = if(player.selectedUniversalShopCategory > 1) slotId - 4 else slotId
                val categoryRow =
                    if(player.univShopSearchActive) UniversalShop.defaultBuyLocation(interfaceItem)
                    else UniversalShop.getCategoryRowIndexPair(player.selectedUniversalShopCategory, trueTarget)

                val categoryId = categoryRow.first

                if(categoryId == 100) {
                    player.sendMessage("You cannot interact with items from this shop while searching.")
                    return@bind
                }

                val itemId = DBRowDefinition.getRowColumnByIndexesInt(UniversalShop.categoriesToIds.getOrDefault(categoryId, 1001), categoryRow.second, 3)

                when (categoryRow.first) {
                    99 -> {}
                    in 1..13 -> {
                        val price = UniversalShop.determinePrice(categoryRow.first, categoryRow.second)
                        if (option == 1) {
                            val item = Item(itemId)
                            player.sendMessage("A ${item.name} costs $price ${UniversalShop.getCategoryCurrency(categoryId)}${appendCategoryCurrency(player, categoryId)}")
                        }
                        else if (option != 4 && option != 5) {
                            UniversalShop.attemptPurchaseMenu(player, option, categoryId, itemId, price)
                        } else {
                            player.sendInputInt("How many would you like to buy?") {
                                UniversalShop.attemptPurchaseDialogue(player, it, categoryId, itemId, price, option == 5)
                            }
                        }
                    }
                }
            }

        }

    private fun appendCategoryCurrency(player: Player, categoryId: Int): String {
        return when(categoryId) {
            8 -> " (You have ${Colour.RS_RED.wrap(player.slayer.slayerPoints)})"
            9 -> " (You have ${Colour.RS_RED.wrap(player.bountyHunterPoints)})"
            12 -> " (You have ${Colour.RS_RED.wrap(player.loyaltyManager.loyaltyPoints)})"
            13 -> " (You have ${Colour.RS_RED.wrap(player.totalVoteCredits)})"
            else -> ""
        }
    }

    override fun getInterface(): GameInterface {
            return GameInterface.UNIVERSAL_SHOP
        }
}