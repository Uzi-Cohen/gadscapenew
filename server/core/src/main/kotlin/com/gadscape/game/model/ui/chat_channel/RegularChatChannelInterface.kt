package com.gadscape.game.model.ui.chat_channel

import com.zenyte.game.GameInterface
import com.zenyte.game.model.ui.Interface

/**
 * Formerly named `ChatHeaderTabInterface`
 *
 * @author Tommeh
 * @author Stan van der Bend
 */
@Suppress("UNUSED")
class RegularChatChannelInterface : Interface() {

    override fun attach() {
        put(2, "Chat-channel") // 211: 3
        put(3, "Your Clan") // 211: 4
        put(4, "View another clan") // 211: 5
        put(5, "Grouping") // 211: 6
    }

    override fun build() {
        bind("Chat-channel", ChatChannelType.ChatChannel)
        bind("Your Clan", ChatChannelType.YourClan)
        bind("View another clan", ChatChannelType.ViewAnotherClan)
        bind("Grouping", ChatChannelType.Grouping)
    }

    private fun bind(componentName: String, tabType: ChatChannelType) =
        bind(componentName) { player -> player.selectedChatChannelType = tabType }

    override fun getInterface() =
        GameInterface.REGULAR_CHAT_CHANNELS
}
