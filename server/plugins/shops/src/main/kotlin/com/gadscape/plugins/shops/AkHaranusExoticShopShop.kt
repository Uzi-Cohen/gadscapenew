package com.gadscape.plugins.shops

import com.gadscape.scripts.shops.ShopScript
import com.zenyte.game.item.ItemId.BOLT_RACK
import com.zenyte.game.model.shop.ShopCurrency
import com.zenyte.game.model.shop.ShopPolicy.STOCK_ONLY

class AkHaranusExoticShopShop : ShopScript() {
    init {
        "Ak-Haranu's Exotic Shop"(177, ShopCurrency.COINS, STOCK_ONLY) {
            BOLT_RACK(10_000_000, 27, 50)
        }
    }
}