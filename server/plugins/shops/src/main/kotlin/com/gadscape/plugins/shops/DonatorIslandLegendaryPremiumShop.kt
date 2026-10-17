package com.gadscape.plugins.shops

import com.gadscape.scripts.shops.ShopScript
import com.zenyte.game.model.shop.ShopCurrency
import com.zenyte.game.model.shop.ShopPolicy.STOCK_ONLY

class DonatorIslandLegendaryPremiumShop : ShopScript() {
    init {
        "Legendary Premium Supplies"(ShopCurrency.COINS, STOCK_ONLY) {
            25104(250, 0, 15000, ironmanRestricted = false) // crystal of memories
        }
    }
}