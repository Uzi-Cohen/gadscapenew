package com.gadscape.scripts.interfaces.user

import com.gadscape.scripts.DefaultCompilation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports

/**
 * @author Jire
 */
object UserInterfaceCompilation : ScriptCompilationConfiguration(
    DefaultCompilation, body = {
        defaultImports(
            "com.zenyte.game.model.ui.InterfacePosition.*",
            "com.zenyte.game.GameInterface.*"
        )
    })