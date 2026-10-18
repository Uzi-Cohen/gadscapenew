package com.gadscape.game.content.gamble.ui

import com.gadscape.game.content.ui.CachePack
import com.gadscape.game.content.ui.CacheUi
import mgi.tools.jagcached.cache.Cache

/** Roat Pkz's dice, mines and blackjack windows and their sprites, written into the cache at start-up. */
object GambleCache : CachePack {

    override val name = "gambling interfaces"

    val interfaces: List<RoatUi>
        get() = listOf(DiceUi.main, DiceUi.side, MinesUi.main, MinesUi.side, BlackjackUi.main, BlackjackUi.side)

    override fun pack(cache: Cache) {
        RoatSprites.pack(cache)
        for (ui in interfaces) CacheUi.packInterface(cache, ui.interfaceId, ui.components)
    }
}
