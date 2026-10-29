package com.gadscape.game.content.store

import com.zenyte.game.GameInterface
import com.zenyte.game.model.ui.Interface
import com.zenyte.game.world.entity.player.Player

/** The store screen (5265, StorePatch): cart, claim list, weekly offer, promotion checkpoints. ::donate. */
@Suppress("unused")
class StoreInterface : Interface() {
    override fun attach() {}
    override fun build() {}
    override fun getDefaultHandler() = DefaultClickHandler { player, componentId, _, _, _ -> StoreScreen.click(player, componentId) }
    override fun getInterface(): GameInterface = GameInterface.STORE

    override fun open(player: Player) {
        player.interfaceHandler.sendInterface(this)
        StoreScreen.opened(player)
    }
}
