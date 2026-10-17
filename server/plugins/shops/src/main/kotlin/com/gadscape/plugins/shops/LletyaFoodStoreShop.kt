package com.gadscape.plugins.shops

import com.gadscape.scripts.shops.ShopScript
import com.zenyte.game.model.shop.*
import com.zenyte.game.model.shop.ShopPolicy
import com.zenyte.game.model.shop.ShopPolicy.*
import com.zenyte.game.model.shop.ShopCurrency
import com.zenyte.game.model.shop.ShopCurrency.*
import com.zenyte.game.item.ItemId
import com.zenyte.game.item.ItemId.*
import com.gadscape.game.content.universalshop.*
import com.gadscape.game.content.universalshop.UnivShopItem
import com.gadscape.game.content.universalshop.UnivShopItem.*

class LletyaFoodStoreShop : ShopScript() {
    init {
        "Lletya Food Store"(181, ShopCurrency.COINS, STOCK_ONLY) {
            BREAD(10, -1, 15)
            LOBSTER(15, -1, 195)
            JUG_OF_WINE(3, -1, 1)
            CHEESE(10, -1, 5)
            CAKE(5, -1, 65)
        }
    }
}