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

class MythsGuildHerbalistShop : ShopScript() {
    init {
        "Myths' Guild Herbalist"(30, ShopCurrency.COINS, STOCK_ONLY) {
            PESTLE_AND_MORTAR(5, 2, 4)
            VIAL(50, 1, 2)
            EMPTY_VIAL_PACK(10, 140, 200)
            EYE_OF_NEWT(50, 2, 3)
            EYE_OF_NEWT_PACK(20, 210, 300)
        }
    }
}