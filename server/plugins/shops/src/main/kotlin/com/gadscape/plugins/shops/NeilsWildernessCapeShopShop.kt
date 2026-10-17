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

class NeilsWildernessCapeShopShop : ShopScript() {
    init {
        "Neil's Wilderness Cape Shop"(246, ShopCurrency.COINS, STOCK_ONLY) {
            TEAM7_CAPE(100, 30, 50)
            TEAM17_CAPE(100, 30, 50)
            TEAM27_CAPE(100, 30, 50)
            TEAM37_CAPE(100, 30, 50)
            TEAM47_CAPE(100, 30, 50)
        }
    }
}