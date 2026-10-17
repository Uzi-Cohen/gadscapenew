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

class FairyFixitsFairyEnchantmentShop : ShopScript() {
    init {
        "Fairy Fixit's Fairy Enchantment"(253, ShopCurrency.COINS, STOCK_ONLY) {
            FAIRY_ENCHANTMENT(4, 60, 100)
        }
    }
}