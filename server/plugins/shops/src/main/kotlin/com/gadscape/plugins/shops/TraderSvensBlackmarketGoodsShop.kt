package com.gadscape.plugins.shops

import com.zenyte.game.model.shop.*
import com.zenyte.game.model.shop.ShopPolicy.*
import com.zenyte.game.model.shop.ShopCurrency
import com.zenyte.game.model.shop.ShopCurrency.*
import com.zenyte.game.item.ItemId.*
import com.gadscape.game.content.universalshop.*
import com.gadscape.game.content.universalshop.UnivShopItem.*
import com.gadscape.scripts.shops.ShopScript

class TraderSvensBlackmarketGoodsShop : ShopScript() {
    init {
        "Trader Sven's Black-market Goods"(173, ShopCurrency.COINS, STOCK_ONLY) {
            CITIZEN_TOP(10, 2, 6)
            CITIZEN_TROUSERS(10, 2, 6)
            CITIZEN_SHOES(10, 2, 6)
            VYREWATCH_TOP(10, 260, 650)
            VYREWATCH_LEGS(10, 260, 650)
            VYREWATCH_SHOES(10, 260, 650)
        }
    }
}