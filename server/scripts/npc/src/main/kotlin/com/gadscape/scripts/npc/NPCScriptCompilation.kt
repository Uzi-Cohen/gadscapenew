package com.gadscape.scripts.npc

import com.gadscape.scripts.DefaultCompilation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports

/**
 * @author Jire
 */
object NPCScriptCompilation : ScriptCompilationConfiguration(
    DefaultCompilation, body = {
        defaultImports(
            "com.zenyte.game.world.entity.npc.NpcId",
            "com.zenyte.game.world.entity.npc.NpcId.*"
        )
    })