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

class ThirusUrkarsFineDynamiteStoreShop : ShopScript() {
    init {
        "Thirus Urkar's Fine Dynamite Store"(212, ShopCurrency.COINS, STOCK_ONLY) {
            DYNAMITE(1000, 50, 1150)
            TINDERBOX(2, -1, 11)
        }
    }
}