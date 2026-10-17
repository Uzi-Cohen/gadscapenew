package com.gadscape.plugins.shops

import com.gadscape.scripts.shops.ShopScript
import com.zenyte.game.item.ItemId.CANDLE
import com.zenyte.game.model.shop.ShopCurrency
import com.zenyte.game.model.shop.ShopPolicy.STOCK_ONLY

class CandleShopShop : ShopScript() {
    init {
        "Candle Shop"(72, ShopCurrency.COINS, STOCK_ONLY) {
            CANDLE(5, 1, 3)
        }
    }
}