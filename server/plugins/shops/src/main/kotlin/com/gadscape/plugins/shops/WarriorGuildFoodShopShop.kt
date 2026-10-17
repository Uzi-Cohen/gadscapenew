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

class WarriorGuildFoodShopShop : ShopScript() {
    init {
        "Warrior Guild Food Shop"(68, ShopCurrency.COINS, STOCK_ONLY) {
            TROUT(10, 3, 24)
            BASS(10, 18, 144)
            PLAIN_PIZZA(5, 6, 47)
            POTATO_WITH_CHEESE(10, 1, 9)
            STEW(10, 3, 24)
        }
    }
}