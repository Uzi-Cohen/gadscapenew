package com.gadscape.game.packet.out.chat_channel

interface ChatChannelMemberIdentifier {
    val hash: Long
    val name: String
}
