package com.gadscape.game.content.kits

import com.zenyte.game.GameInterface
import com.zenyte.game.model.ui.Interface
import com.zenyte.game.world.entity.player.Player

/** The Premade Kits side tab (interface 5250, KitsPatch), in the music tab's slot. */
@Suppress("unused")
class KitsTabInterface : Interface() {
    override fun attach() {}
    override fun build() {}
    override fun getDefaultHandler() = DefaultClickHandler { player, componentId, _, _, _ -> KitsTab.click(player, componentId) }
    override fun getInterface(): GameInterface = GameInterface.PREMADE_KITS_TAB
    override fun closeInCombat() = false

    override fun open(player: Player) {
        player.interfaceHandler.sendInterface(this)
        KitsTab.render(player)
    }
}
