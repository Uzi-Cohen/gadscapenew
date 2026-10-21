package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:45:16
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class ResetFriendChannel implements GamePacketEncoder {

	@Override
	public void log(@NotNull final Player player) {
		this.log(player, "");
	}

	@Override
	public OutgoingGameMessage toMessage() {
		return new net.rsprot.protocol.game.outgoing.friendchat.UpdateFriendChatChannelFullV2(net.rsprot.protocol.game.outgoing.friendchat.UpdateFriendChatChannelFullV2.LeaveUpdate.INSTANCE);
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}

}
