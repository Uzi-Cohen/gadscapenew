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

class NathifasBakeStallShop : ShopScript() {
    init {
        "Nathifa's Bake Stall"(141, ShopCurrency.COINS, STOCK_ONLY) {
            BREAD(10, 6, 12)
            CAKE(3, 27, 50)
            CHOCOLATE_SLICE(8, 16, 30)
            WATERSKIN4(50, 16, 30)
        }
    }
}