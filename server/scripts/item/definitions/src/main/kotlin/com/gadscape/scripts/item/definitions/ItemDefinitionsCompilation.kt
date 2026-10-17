package com.gadscape.scripts.item.definitions

import com.gadscape.scripts.item.ItemScriptCompilation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports

/**
 * @author Jire
 */
object ItemDefinitionsCompilation : ScriptCompilationConfiguration(
    ItemScriptCompilation, body = {
        defaultImports(
            "com.zenyte.game.world.entity.player.SkillConstants.*",
            "com.zenyte.game.world.entity.player.container.impl.equipment.EquipmentType.*",
            "com.zenyte.game.world.entity.player.Bonuses.Bonus.*",
        )
    }
)