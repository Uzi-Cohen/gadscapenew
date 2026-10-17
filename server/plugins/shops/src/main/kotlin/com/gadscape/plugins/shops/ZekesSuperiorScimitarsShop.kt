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

class ZekesSuperiorScimitarsShop : ShopScript() {
    init {
        "Zeke's Superior Scimitars"(128, ShopCurrency.COINS, STOCK_ONLY) {
            BRONZE_SCIMITAR(5, 17, 32)
            IRON_SCIMITAR(3, 61, 112)
            STEEL_SCIMITAR(2, 220, 400)
            MITHRIL_SCIMITAR(1, 572, 1040)
        }
    }
}