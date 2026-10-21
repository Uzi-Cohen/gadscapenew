package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;
import com.zenyte.game.GameConstants;

import com.gadscape.game.packet.out.chat_channel.ChatChannelType;
import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.masks.ChatMessage;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

public final class MessageClanChannel implements GamePacketEncoder {

    private final ChatChannelType type;
    private final String sender;
    private final ChatMessage message;

    @Override
    public void log(@NotNull final Player player) {
        log(player, "Type: " + type + ", Sender: " + sender + ", message: " + message.getChatText());
    }

    public MessageClanChannel(ChatChannelType type, String sender, ChatMessage message) {
        this.type = type;
        this.sender = sender;
        message.retain();
        this.message = message;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        try (message) {
            return new net.rsprot.protocol.game.outgoing.clan.MessageClanChannel(type.getPacketIdentifier(), sender, GameConstants.WORLD_PROFILE.getNumber(),
                    com.gadscape.network.rsprot.RsprotOut.nextMessageCounter(), 0, message.getChatText());
        }
    }

    @Override
    public LogLevel level() {
        return LogLevel.HIGH_PACKET;
    }

}
