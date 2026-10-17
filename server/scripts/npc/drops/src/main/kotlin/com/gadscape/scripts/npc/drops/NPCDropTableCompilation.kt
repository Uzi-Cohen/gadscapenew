package com.gadscape.scripts.npc.drops

import com.gadscape.scripts.npc.NPCScriptCompilation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports

/**
 * Represents a [ScriptCompilationConfiguration] for [NPCDropTableScript].
 *
 * @author Stan van der Bend
 */
object NPCDropTableCompilation : ScriptCompilationConfiguration(
    NPCScriptCompilation, body = {
        defaultImports(
            "com.zenyte.game.item.ItemId",
            "com.zenyte.game.item.ItemId.*",
            "com.gadscape.scripts.npc.drops.table.DropTableType.*",

            "com.zenyte.game.world.entity.npc.drop.matrix.*",
            "com.zenyte.game.world.entity.npc.drop.matrix.Drop.*",
            "com.zenyte.game.world.entity.npc.drop.matrix.DropProcessor.*",
        )
    })
