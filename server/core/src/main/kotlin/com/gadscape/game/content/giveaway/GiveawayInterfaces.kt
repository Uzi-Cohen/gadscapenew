package com.gadscape.game.content.giveaway

import com.zenyte.game.GameInterface
import com.zenyte.game.model.ui.Interface
import com.zenyte.game.world.entity.player.Player

/** The giveaway screen (5241): prizes, winner, Give prize, recent giveaways. */
@Suppress("unused")
class GiveawayInterface : Interface() {
    override fun attach() {}
    override fun build() {}
    override fun getDefaultHandler() = DefaultClickHandler { player, componentId, _, _, _ -> Giveaways.click(player, componentId) }
    override fun getInterface(): GameInterface = GameInterface.GIVEAWAY

    override fun open(player: Player) {
        if (!Giveaways.mayGive(player)) return
        player.interfaceHandler.sendInterface(this)
        Giveaways.render(player)
    }
}

/** The confirm screen (5242): nothing is given until Confirm. */
@Suppress("unused")
class GiveawayConfirmInterface : Interface() {
    override fun attach() {}
    override fun build() {}
    override fun getDefaultHandler() = DefaultClickHandler { player, componentId, _, _, _ -> Giveaways.clickConfirm(player, componentId) }
    override fun getInterface(): GameInterface = GameInterface.GIVEAWAY_CONFIRM

    override fun open(player: Player) {
        if (!Giveaways.mayGive(player)) return
        player.interfaceHandler.sendInterface(this)
        Giveaways.renderConfirm(player)
    }
}
