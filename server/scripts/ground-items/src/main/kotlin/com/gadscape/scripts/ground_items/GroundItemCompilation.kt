package com.gadscape.scripts.ground_items

import com.gadscape.scripts.DefaultCompilation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports

/**
 * @author Jire
 */
object GroundItemCompilation : ScriptCompilationConfiguration(
    DefaultCompilation, body = {
        defaultImports(
            "com.zenyte.game.item.ItemId",
            "com.zenyte.game.item.ItemId.*"
        )
    }
) {
    private fun readResolve(): Any = GroundItemCompilation
}