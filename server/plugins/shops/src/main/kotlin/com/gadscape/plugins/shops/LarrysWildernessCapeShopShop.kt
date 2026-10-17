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

class LarrysWildernessCapeShopShop : ShopScript() {
    init {
        "Larry's Wilderness Cape Shop"(243, ShopCurrency.COINS, STOCK_ONLY) {
            TEAM3_CAPE(100, 30, 50)
            TEAM13_CAPE(100, 30, 50)
            TEAM23_CAPE(100, 30, 50)
            TEAM33_CAPE(100, 30, 50)
            TEAM43_CAPE(100, 30, 50)
        }
    }
}