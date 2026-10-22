package com.gadscape.cache_tool.packing.custom

import com.zenyte.CacheManager
import mgi.types.component.ComponentDefinitions

/**
 * Diagnostic: scans every compiled interface component in the served cache for text
 * matching the reference blackjack screenshot (title, rules text, stats labels), to find
 * whether a ready-made Blackjack/Dice/Mines interface already ships in this cache but was
 * never wired up server-side, before authoring a brand new one from scratch.
 * Run via `./gradlew findBlackjackInterface`.
 */
object BlackjackInterfaceFinder {

    private val NEEDLES = listOf(
        "blackjack", "dealer must draw", "place bets", "insurance pays", "bj pays",
        "session stats", "bet amount", "hit", "stand", "mines", "dice"
    )

    @JvmStatic
    fun main(args: Array<String>) {
        CacheManager.loadDetached("cache/data/cache", true)
        ComponentDefinitions.reload()

        val hits = HashMap<Int, MutableList<ComponentDefinitions>>()
        for (component in ComponentDefinitions.definitions.values) {
            val text = component.text ?: continue
            val lower = text.lowercase()
            if (NEEDLES.any { lower.contains(it) }) {
                hits.getOrPut(component.interfaceId) { mutableListOf() }.add(component)
            }
        }

        for ((interfaceId, components) in hits.toSortedMap()) {
            println("=== interfaceId=$interfaceId (${components.size} matching text components) ===")
            for (c in components.sortedBy { it.componentId }) {
                println("  componentId=${c.componentId} name=\"${c.name}\" text=\"${c.text}\"")
            }
        }
        println("Scanned ${ComponentDefinitions.definitions.size} components total, ${hits.size} interfaces matched.")
    }
}
