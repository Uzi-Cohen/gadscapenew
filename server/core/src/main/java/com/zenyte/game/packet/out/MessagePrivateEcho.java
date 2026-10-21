package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.masks.ChatMessage;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:38:04
 * @author Jire
 */
public final class MessagePrivateEcho implements GamePacketEncoder {

    private final String toUser;
    private final ChatMessage message;

    @Override
    public void log(@NotNull final Player player) {
        log(player, "To: " + toUser + ", message: " + message.getChatText());
    }

    public MessagePrivateEcho(String toUser, ChatMessage message) {
        this.toUser = toUser;
        message.retain();
        this.message = message;
    }

    @Override
    public LogLevel level() {
        return LogLevel.HIGH_PACKET;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        try (message) {
            return new net.rsprot.protocol.game.outgoing.social.MessagePrivateEcho(toUser, message.getChatText());
        }
    }

}
