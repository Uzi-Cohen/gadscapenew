package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;
import com.zenyte.game.GameConstants;

import com.gadscape.game.packet.out.chat_channel.ChatChannelType;
import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.masks.ChatMessage;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

public final class MessageClanChannelSystem implements GamePacketEncoder {

    private final ChatChannelType type;
    private final ChatMessage message;

    @Override
    public void log(@NotNull final Player player) {
        log(player, "Type: " + type + ", message: " + message.getChatText());
    }

    public MessageClanChannelSystem(ChatChannelType type, ChatMessage message) {
        this.type = type;
        message.retain();
        this.message = message;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        try (message) {
            return new net.rsprot.protocol.game.outgoing.clan.MessageClanChannelSystem(type.getPacketIdentifier(), GameConstants.WORLD_PROFILE.getNumber(),
                    com.gadscape.network.rsprot.RsprotOut.nextMessageCounter(), message.getChatText());
        }
    }

    @Override
    public LogLevel level() {
        return LogLevel.HIGH_PACKET;
    }

}
