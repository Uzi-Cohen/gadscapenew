package com.gadscape.scripts.npc.spawns

import com.gadscape.scripts.npc.NPCScriptCompilation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports

/**
 * @author Jire
 */
object NPCSpawnsCompilation : ScriptCompilationConfiguration(
    NPCScriptCompilation, body = {
        defaultImports("com.zenyte.game.util.Direction.*")
    })