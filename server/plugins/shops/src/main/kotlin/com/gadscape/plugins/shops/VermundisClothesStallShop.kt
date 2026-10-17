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

class VermundisClothesStallShop : ShopScript() {
    init {
        "Vermundi's Clothes Stall"(192, ShopCurrency.COINS, STOCK_ONLY) {
            SKIRT(3, 192, 455)
            TROUSERS(3, 302, 715)
            SHORTS(3, 154, 364)
            WOVEN_TOP(3, 275, 650)
            SHIRT(3, 247, 585)
            SILK(5, 16, 39)
        }
    }
}