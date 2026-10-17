package com.gadscape.game.packet.out.chat_channel

open class ChatChannelBannedMember(
    override val hash: Long,
    override val name: String,
) : ChatChannelMemberIdentifier
