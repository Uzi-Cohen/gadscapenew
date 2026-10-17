package com.gadscape.scripts.item.actions

import com.gadscape.scripts.item.ItemScriptCompilation
import kotlin.script.experimental.api.ScriptCompilationConfiguration

/**
 * @author Jire
 */
object ItemActionCompilation : ScriptCompilationConfiguration(
    ItemScriptCompilation
) {

    private fun readResolve(): Any = ItemActionCompilation

}