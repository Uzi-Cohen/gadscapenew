package com.gadscape.scripts.item

import com.gadscape.scripts.DefaultCompilation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports

/**
 * @author Jire
 */
object ItemScriptCompilation : ScriptCompilationConfiguration(
    DefaultCompilation, body = {
        defaultImports(
            "com.zenyte.game.item.ItemId",
            "com.zenyte.game.item.ItemId.*",

            "com.zenyte.game.model.item.*"
        )
    }) {

    private fun readResolve(): Any = ItemScriptCompilation

}