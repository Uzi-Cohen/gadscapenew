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

class OobapohksJavelinStoreShop : ShopScript() {
    init {
        "Oobapohk's Javelin Store"(225, ShopCurrency.COINS, STOCK_ONLY) {
            BRONZE_JAVELIN(500, 2, 4)
            IRON_JAVELIN(500, 3, 6)
            STEEL_JAVELIN(500, 14, 24)
            MITHRIL_JAVELIN(500, 38, 64)
            ADAMANT_JAVELIN(500, 96, 160)
            RUNE_JAVELIN(500, 240, 400)
        }
    }
}