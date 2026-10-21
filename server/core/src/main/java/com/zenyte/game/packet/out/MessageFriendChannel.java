package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.utils.TextUtils;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:33:11
 * @author Jire
 */
public final class MessageFriendChannel implements GamePacketEncoder {

    private final String channelName;
    private final int icon;

    private final String message;

    private final String senderName;
    private final int message_uid;

    @Override
    public void log(@NotNull final Player player) {
        log(player, "Channel: " + channelName + ", icon: " + icon + ", sender: " + senderName + ", message:" +
                " " + message);
    }

    public MessageFriendChannel(Player sender, String channelName, int icon,
                                String message) {
        this.channelName = channelName;
        this.icon = icon;

        this.message = message;

        senderName = sender.getTitleName();
        message_uid = sender.getSocialManager().getNextUniqueId();
    }

    @Override
    public OutgoingGameMessage toMessage() {
        return new net.rsprot.protocol.game.outgoing.friendchat.MessageFriendChannel(senderName, TextUtils.stringToLong(channelName), 1, message_uid, 0, message);
    }

    @Override
    public LogLevel level() {
        return LogLevel.HIGH_PACKET;
    }

}
