package com.gadscape.scripts.shops

import com.gadscape.scripts.DefaultCompilation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports

/**
 * @author Jire
 */
object ShopScriptCompilation : ScriptCompilationConfiguration(
    DefaultCompilation, body = {
        defaultImports(
            "com.zenyte.game.model.shop.*",

            "com.zenyte.game.model.shop.ShopPolicy",
            "com.zenyte.game.model.shop.ShopPolicy.*",

            "com.zenyte.game.model.shop.ShopCurrency",
            "com.zenyte.game.model.shop.ShopCurrency.*",

            "com.zenyte.game.item.ItemId",
            "com.zenyte.game.item.ItemId.*",

            "com.gadscape.game.content.universalshop.*",
            "com.gadscape.game.content.universalshop.UnivShopItem",
            "com.gadscape.game.content.universalshop.UnivShopItem.*"
        )
    }
)