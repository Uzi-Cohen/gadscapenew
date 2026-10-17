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

class RoksChocsBoxShop : ShopScript() {
    init {
        "Rok's Chocs Box"(135, ShopCurrency.COINS, STOCK_ONLY) {
            CHOCICE(30, 19, 30)
            CHOCOLATE_BAR(25, 6, 10)
        }
    }
}