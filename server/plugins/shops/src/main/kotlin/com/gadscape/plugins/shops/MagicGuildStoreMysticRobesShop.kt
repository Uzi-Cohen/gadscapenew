package com.gadscape.plugins.shops

import com.zenyte.game.model.shop.*
import com.zenyte.game.model.shop.ShopPolicy.*
import com.zenyte.game.model.shop.ShopCurrency
import com.zenyte.game.model.shop.ShopCurrency.*
import com.zenyte.game.item.ItemId.*
import com.gadscape.game.content.universalshop.*
import com.gadscape.game.content.universalshop.UnivShopItem.*
import com.gadscape.scripts.shops.ShopScript

class MagicGuildStoreMysticRobesShop : ShopScript() {
    init {
        "Magic Guild Store (Mystic Robes)"(70, ShopCurrency.COINS, STOCK_ONLY) {
            MYSTIC_HAT(100, 3600, 9000)
            MYSTIC_ROBE_TOP(100, 28800, 72000)
            MYSTIC_ROBE_BOTTOM(100, 19200, 48000)
            MYSTIC_GLOVES(100, 2400, 6000)
            MYSTIC_BOOTS(100, 2400, 6000)
        }
    }
}