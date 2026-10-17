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

class TheHaymakersArmsShop : ShopScript() {
    init {
        "The Haymaker's Arms"(204, ShopCurrency.COINS, STOCK_ONLY) {
            BEER(10, -1, 2)
            CIDER(10, -1, 2)
            JUG_OF_WINE(5, -1, 1)
            CUP_OF_TEA(5, -1, 10)
        }
    }
}