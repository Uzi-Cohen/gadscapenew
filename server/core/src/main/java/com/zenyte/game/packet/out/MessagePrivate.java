package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;
import com.zenyte.game.GameConstants;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.masks.ChatMessage;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:36:21
 * @author Jire
 */
public final class MessagePrivate implements GamePacketEncoder {

    private final String sender;
    private final ChatMessage message;
    private final int icon;

    @Override
    public void log(@NotNull final Player player) {
        log(player, "Sender: " + sender + ", icon: " + icon + ", message: " + message.getChatText());
    }

    public MessagePrivate(String sender, ChatMessage message, int icon) {
        this.sender = sender;
        message.retain();
        this.message = message;
        this.icon = icon;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        try (message) {
            return new net.rsprot.protocol.game.outgoing.social.MessagePrivate(sender, GameConstants.WORLD_PROFILE.getNumber(),
                    com.gadscape.network.rsprot.RsprotOut.nextMessageCounter(), icon, message.getChatText());
        }
    }

    @Override
    public LogLevel level() {
        return LogLevel.HIGH_PACKET;
    }

}
