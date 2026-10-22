package com.gadscape.game.model.ui.chat_channel

import com.zenyte.game.GameInterface
import com.zenyte.game.model.ui.Interface

/**
 * Handles the group ironman variant of the social tabs interface.
 *
 * @author Stan van der Bend
 */
@Suppress("UNUSED")
class GroupIronmanChatChannelInterface : Interface() {

    override fun attach() {
        // Build 241 side_channels_large (727) tabs; 211 ids 3-7.
        put(2, "Iron Group") // tab_gim
        put(3, "Clat-channel") // tab_0
        put(4, "Your Clan") // tab_1
        put(5, "View another clan") // tab_2
        put(6, "Grouping") // tab_3
    }

    override fun build() {
        bind("Iron Group", ChatChannelType.IronGroup)
        bind("Clat-channel", ChatChannelType.ChatChannel)
        bind("Your Clan", ChatChannelType.YourClan)
        bind("View another clan", ChatChannelType.ViewAnotherClan)
        bind("Grouping", ChatChannelType.Grouping)
    }

    private fun bind(componentName: String, tabType: ChatChannelType) =
        bind(componentName) { player -> player.selectedChatChannelType = tabType }

    override fun getInterface() =
        GameInterface.GIM_CHAT_CHANNELS
}
