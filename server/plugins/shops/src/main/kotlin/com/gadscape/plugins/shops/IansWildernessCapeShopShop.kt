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

class IansWildernessCapeShopShop : ShopScript() {
    init {
        "Ian's Wilderness Cape Shop"(242, ShopCurrency.COINS, STOCK_ONLY) {
            TEAM2_CAPE(100, 30, 50)
            TEAM12_CAPE(100, 30, 50)
            TEAM22_CAPE(100, 30, 50)
            TEAM32_CAPE(100, 30, 50)
            TEAM42_CAPE(100, 30, 50)
        }
    }
}