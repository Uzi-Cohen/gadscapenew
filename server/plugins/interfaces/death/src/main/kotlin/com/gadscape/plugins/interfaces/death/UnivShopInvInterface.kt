package com.gadscape.plugins.interfaces.death

import com.gadscape.game.content.UniversalShop
import com.gadscape.game.content.shop.ShopCurrencyHandler
import com.gadscape.game.content.shop.UniversalShopCategory
import com.gadscape.game.world.entity.player.selectedUniversalShopCategory
import com.gadscape.scripts.interfaces.InterfaceScript
import com.zenyte.game.GameConstants
import com.zenyte.game.GameInterface
import com.zenyte.game.content.universalshop.UniversalShopInterface
import com.zenyte.game.item.Item
import com.zenyte.game.util.AccessMask
import com.zenyte.game.util.Colour
import com.zenyte.game.util.RSColour
import com.zenyte.game.util.Utils
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.container.impl.ContainerType
import com.zenyte.game.world.entity.player.dialogue.options
import com.zenyte.game.world.entity.player.dialogue.start
import com.zenyte.plugins.dialogue.OptionsMenuD
import kotlin.math.min

class UnivShopInvInterface : InterfaceScript() {
    init {
        GameInterface.UNIVERSAL_SHOP_INVENTORY {
            val itemLayer = "Item layer"(0) {
                player.selectItem(option, slotID)
            }
            opened {
                itemLayer.sendComponentSettings(this, 27, AccessMask.CLICK_OP1, AccessMask.CLICK_OP2, AccessMask.CLICK_OP10)
                packetDispatcher.sendClientScript(
                    149,
                    id shl 16 or itemLayer.componentID,
                    ContainerType.INVENTORY.id,
                    4,
                    7,
                    0,
                    -1,
                    "Value",
                    "Sell",
                    "",
                    "",
                    ""
                )
                sendInterface()
            }
        }
    }

    fun Player.selectItem(option: Int, slotID: Int) {
        if(selectedUniversalShopCategory < 1) {
            sendMessage("You cannot use this while not in a specific shop.")
            return
        }
        val item = inventory.container.items[slotID] ?: return
        val itemId = item.id
        val category = UniversalShop.getCategory(selectedUniversalShopCategory) ?: return
        if(option == 1) {
            if(category != UniversalShop.General) {
                val price = UniversalShop.sellPrice(category, itemId)
                if (price > 0) {
                    sendMessage("${item.name} can be sold for $price ${UniversalShop.getCategoryCurrency(selectedUniversalShopCategory)}"); return
                } else {
                    sendMessage("This item cannot be sold to this shop."); return
                }
            } else {
                if(GameConstants.RESTRICTED_TRADE_ITEMS.contains(item.id) || item.id == 995 || item.id == 13224 || !item.definitions.isGrandExchange) {
                    sendMessage("This item cannot be sold."); return
                }
                val price = (item.definitions.price / 3)
                val message = if(item.amount > 1 || item.isStackable)
                    "${item.name} can be sold for $price each (stack value: ${formatQuantity((item.amount * price).toLong())})"
                else
                    "${item.name} can be sold for $price"
                sendMessage(message)
                return
            }
        } else if(option == 2) {
            sendInputInt("How many would you like to sell? (max ${item.amount})") { requested ->
                if(category == UniversalShop.General) {
                    val potential = (requested * (item.definitions.price / 3)).toLong()

                    if(requested > 100_000_000) { sendMessage("You can not sell this many at any given time"); return@sendInputInt }
                    if(!inventory.hasFreeSlots()) { sendMessage("You must have at least one free inventory space to sell here."); return@sendInputInt }

                    if(potential > Int.MAX_VALUE) {
                        sendMessage("You cannot sell this many at once."); return@sendInputInt
                    } else {
                        dialogueManager.start {
                            options("Are you sure you want to sell $requested x ${item.name}") {
                                "Yes" {
                                    attemptSaleGold(player, requested, item)
                                }
                                "No" {
                                    UniversalShopInterface.openInterfaceToTab(player, selectedUniversalShopCategory)
                                }
                            }
                        }
                    }
                } else {
                    if (UniversalShop.sellPrice(category, itemId) > 0) {
                        dialogueManager.start {
                            options("Are you sure you want to sell $requested x ${item.name}") {
                                "Yes" {
                                    attemptSaleCategory(player, requested, item, category)
                                }
                                "No" {
                                    UniversalShopInterface.openInterfaceToTab(player, selectedUniversalShopCategory)
                                }
                            }
                        }
                    } else {
                        sendMessage("This item cannot be sold to this shop."); return@sendInputInt
                    }
                }
            }

        }
    }

    private fun attemptSaleCategory(player: Player, requested: Int, item: Item, category: UniversalShopCategory) {
        val currency = UniversalShop.getCategoryCurrency(category.uniqueIndex)
        // Checked before anything leaves the inventory: an unhandled currency (Slayer points before) deleted the item and
        // then threw in ShopCurrencyHandler.add, so the player lost it for nothing.
        val price = UniversalShop.sellPrice(category, item.id)
        if (price <= 0 || !ShopCurrencyHandler.isSupported(currency)) {
            player.sendMessage("This item cannot be sold to this shop.")
            return
        }
        val amount = min(requested, Int.MAX_VALUE / price)
        if (amount <= 0) return
        val sold = player.inventory.deleteItem(Item(item.id, amount)).succeededAmount
        if (sold <= 0) return
        val payment = sold * price
        ShopCurrencyHandler.add(currency, player, payment)
        player.sendMessage(Colour.RS_GREEN.wrap("You have received $payment $currency"))
        UniversalShopInterface.openInterfaceToTab(player, player.selectedUniversalShopCategory)
    }

    private fun attemptSaleGold(player: Player, requested: Int, item: Item) {
        val success = player.inventory.deleteItem(Item(item.id, requested)).succeededAmount
        player.inventory.addItem(Item(995, success * (item.definitions.price / 3)))
        player.sendMessage(Colour.RS_GREEN.wrap("You have received ${formatQuantity((success * (item.definitions.price / 3)).toLong())} coins"))
        UniversalShopInterface.openInterfaceToTab(player, player.selectedUniversalShopCategory)
    }

    fun formatQuantity(amount: Long): String {
        var format = "Too high!"
        if (amount >= 0 && amount < 1000) {
            format = amount.toString()
        } else if (amount >= 1000 && amount < 1000000) {
            format = (amount / 1000).toString() + "K"
        } else if (amount >= 1000000 && amount < 1000000000L) {
            format = (amount / 1000000).toString() + "M"
        } else if (amount >= 1000000000L && amount < 1000000000000L) {
            format = (amount / 1000000000).toString() + "B"
        } else if (amount >= 1000000000000L && amount < 10000000000000000L) {
            format = (amount / 1000000000000L).toString() + "T"
        } else if (amount >= 10000000000000000L && amount < 1000000000000000000L) {
            format = (amount / 1000000000000000L).toString() + "QD"
        } else if (amount >= 1000000000000000000L && amount < Long.MAX_VALUE) {
            format = (amount / 1000000000000000000L).toString() + "QT"
        }
        return format
    }
}
