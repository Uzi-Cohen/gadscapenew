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

class WilliamsWildernessCapeShopShop : ShopScript() {
    init {
        "William's Wilderness Cape Shop"(251, ShopCurrency.COINS, STOCK_ONLY) {
            TEAM1_CAPE(100, 30, 50)
            TEAM11_CAPE(100, 30, 50)
            TEAM21_CAPE(100, 30, 50)
            TEAM31_CAPE(100, 30, 50)
            TEAM41_CAPE(100, 30, 50)
        }
    }
}