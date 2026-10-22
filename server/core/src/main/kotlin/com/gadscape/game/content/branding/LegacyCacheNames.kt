package com.gadscape.game.content.branding

import com.google.common.eventbus.Subscribe
import com.zenyte.plugins.events.ServerLaunchEvent
import mgi.types.config.items.ItemDefinitions
import org.slf4j.LoggerFactory

/**
 * Safety net for item names that a cache built before the GadScape rebrand still carries (for example the mystery
 * box 32080 / 33080): the server's copy of the definition gets the GadScape name, so chat lines, the drop viewer and
 * world broadcasts never show the old one. The cache itself is fixed by the cache converter's rebrand table
 * (content-work/branding-fodder/REBRAND_TABLE.tsv); with a rebranded cache nothing here matches and this does nothing.
 */
object LegacyCacheNames {

    private val logger = LoggerFactory.getLogger(LegacyCacheNames::class.java)

    /** Exact old cache name -> GadScape name. */
    private val itemNames = mapOf(
        "OSNR Mystery Box" to "GadScape Mystery Box",
    )

    @Subscribe
    @JvmStatic
    fun onServerLaunch(@Suppress("UNUSED_PARAMETER") event: ServerLaunchEvent) {
        val definitions = ItemDefinitions.getDefinitions() ?: return
        var renamed = 0
        for (definition in definitions) {
            val name = definition?.name ?: continue
            val newName = itemNames[name] ?: continue
            definition.name = newName
            renamed++
        }
        if (renamed > 0)
            logger.info("Renamed {} item definition(s) that still had a pre-GadScape name; rebrand the cache to fix them for the client too.", renamed)
    }
}
