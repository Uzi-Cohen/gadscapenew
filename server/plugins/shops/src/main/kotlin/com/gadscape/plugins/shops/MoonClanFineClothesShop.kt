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

class MoonClanFineClothesShop : ShopScript() {
    init {
        "Moon Clan Fine Clothes"(44, ShopCurrency.COINS, STOCK_ONLY) {
            MOONCLAN_HELM(9, 0, 1000)
            MOONCLAN_HAT(10, 0, 1000)
            MOONCLAN_ARMOUR(10, 0, 1000)
            MOONCLAN_SKIRT(10, 0, 1000)
            MOONCLAN_GLOVES(9, 0, 900)
            MOONCLAN_BOOTS(12, 0, 900)
            MOONCLAN_CAPE(15, 0, 200)
        }
    }
}