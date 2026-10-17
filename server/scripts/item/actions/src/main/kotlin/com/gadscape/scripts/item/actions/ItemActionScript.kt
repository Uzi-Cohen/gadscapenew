package com.gadscape.scripts.item.actions

import com.gadscape.plugins.item.actions.ItemActionPlugin
import kotlin.script.experimental.annotations.KotlinScript

/**
 * @author Jire
 */
@KotlinScript(
    "Item Action Script",
    fileExtension = "itemaction.kts",
    compilationConfiguration = ItemActionCompilation::class
)
abstract class ItemActionScript : ItemActionPlugin()